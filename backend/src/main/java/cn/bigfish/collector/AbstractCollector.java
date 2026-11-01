package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import cn.bigfish.contract.LiveEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;

/** Two dedicated workers: protocol/replay and bounded business dispatch. No common executor queue. */
abstract class AbstractCollector implements CollectorPort {
    protected final CollectorOptions options;
    protected final Clock clock;
    private final CollectorDiagnostics diagnostics = new CollectorDiagnostics();
    private volatile State state = State.STOPPED;
    private Run run;

    AbstractCollector(CollectorOptions options, Clock clock) {
        this.options = Objects.requireNonNull(options); this.clock = Objects.requireNonNull(clock);
    }

    public final CollectorDiagnostics diagnostics() { return diagnostics; }
    public final synchronized CollectorResources resources() {
        if (run == null) return new CollectorResources(0, 0, 0, 0);
        return new CollectorResources(run.source.isAlive() ? 1 : 0, run.dispatcher.isAlive() ? 1 : 0,
                run.events.size(), run.hasConnection() ? 1 : 0);
    }
    public abstract String mode();
    @Override public final State state() { return state; }

    @Override public final synchronized void start(String roomId, String sessionId, Consumer<LiveEvent> sink) {
        if (roomId == null || roomId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Room and session are required");
        }
        Objects.requireNonNull(sink);
        if (run != null && (run.active.get() || run.source.isAlive() || run.dispatcher.isAlive())) {
            throw new IllegalStateException("Previous collector workers have not stopped");
        }
        diagnostics.inbound().begin(sessionId, clock, mode().equals("DOUYIN"));
        run = new Run(roomId, sessionId, sink);
        state = State.CONNECTING;
        run.dispatcher.start(); run.source.start();
    }

    @Override public final synchronized void stop() { finish(run, State.STOPPED); }

    private synchronized void finish(Run context, State terminal) {
        if (context == null) { state = terminal; return; }
        if (context != run) return;
        // A control packet/error already waiting for this monitor must not undo an explicit stop.
        if (!context.active.get() && terminal != State.STOPPED) return;
        context.active.set(false);
        QueuedEvent discarded;
        while ((discarded = context.events.poll()) != null)
            context.outcome(discarded, InboundDiagnostics.Stage.DELIVERY_QUEUE, InboundDiagnostics.Reason.CANCELLED);
        context.source.interrupt(); context.dispatcher.interrupt();
        context.closeConnection();
        state = terminal;
    }

    protected abstract void collect(Run context) throws Exception;

    private record QueuedEvent(LiveEvent event, InboundDiagnostics.Trace trace) { }

    protected final class Run {
        final String requestedRoom;
        final String sessionId;
        final AtomicBoolean active = new AtomicBoolean(true);
        private final ArrayBlockingQueue<QueuedEvent> events = new ArrayBlockingQueue<>(options.queueCapacity());
        private final EventNormalizer normalizer;
        private final Thread source;
        private final Thread dispatcher;
        private AutoCloseable connection;

        Run(String roomId, String sessionId, Consumer<LiveEvent> sink) {
            this.requestedRoom = roomId; this.sessionId = sessionId;
            normalizer = new EventNormalizer(sessionId, options, diagnostics);
            source = Thread.ofPlatform().daemon().name("bigfish-collector-source").unstarted(() -> {
                long started = System.nanoTime();
                try { collect(this); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    if (active.get()) { diagnostics.error(CONNECT_FAILURE, elapsedMillis(started)); finish(this, State.FAILED); }
                }
                catch (DouyinFailure failure) {
                    if (active.get()) { diagnostics.error(failure.code(), elapsedMillis(started)); finish(this, State.FAILED); }
                }
                catch (Exception exception) {
                    if (active.get()) { diagnostics.error(CONNECT_FAILURE, elapsedMillis(started)); finish(this, State.FAILED); }
                } finally { diagnostics.inbound().flushLogs(true); }
            });
            dispatcher = Thread.ofPlatform().daemon().name("bigfish-collector-dispatch").unstarted(() -> {
                try {
                    while (active.get()) {
                        try {
                            QueuedEvent queued = events.take();
                            LiveEvent event = queued.event();
                            if (!active.get()) { outcome(queued, InboundDiagnostics.Stage.DELIVERY_QUEUE, InboundDiagnostics.Reason.CANCELLED); break; }
                            if (!event.receivedAt().plus(options.queueTtl()).isAfter(clock.instant())) {
                                diagnostics.increment(EXPIRED);
                                outcome(queued, InboundDiagnostics.Stage.DELIVERY_QUEUE, InboundDiagnostics.Reason.TTL); continue;
                            }
                            long started = System.nanoTime();
                            try {
                                sink.accept(event); diagnostics.increment(EMITTED);
                                outcome(queued, InboundDiagnostics.Stage.DISPATCH, InboundDiagnostics.Reason.SINK_RETURNED);
                            } catch (RuntimeException exception) {
                                diagnostics.error(SINK_FAILURE, elapsedMillis(started));
                                outcome(queued, InboundDiagnostics.Stage.DISPATCH, InboundDiagnostics.Reason.SINK_FAILED);
                            }
                        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
                    }
                } finally { diagnostics.inbound().flushLogs(true); }
            });
        }

        private void outcome(QueuedEvent queued, InboundDiagnostics.Stage stage, InboundDiagnostics.Reason reason) {
            try (var scope = diagnostics.inbound().use(queued.trace())) {
                diagnostics.inbound().record(stage, reason, InboundDiagnostics.eventKind(queued.event().type().name()), null);
            }
        }

        void accept(IncomingMessage input, Instant receivedAt) {
            accept(input, receivedAt, diagnostics.inbound().trace());
        }

        void accept(IncomingMessage input, Instant receivedAt, InboundDiagnostics.Trace trace) {
            if (!active.get()) return;
            if (input.kind().equals("ENDED")) { diagnostics.increment(ENDED); finish(this, State.ENDED); return; }
            long started = System.nanoTime();
            try (var scope = diagnostics.inbound().use(trace)) {
                try {
                    normalizer.normalize(input, receivedAt).ifPresent(event -> {
                        diagnostics.inbound().record(InboundDiagnostics.Stage.NORMALIZE, InboundDiagnostics.Reason.OK,
                                InboundDiagnostics.eventKind(event.type().name()), null);
                        var queued = new QueuedEvent(event, trace);
                        synchronized (AbstractCollector.this) {
                            if (!active.get()) outcome(queued, InboundDiagnostics.Stage.DELIVERY_QUEUE, InboundDiagnostics.Reason.CANCELLED);
                            else if (!events.offer(queued)) {
                                diagnostics.increment(QUEUE_FULL);
                                outcome(queued, InboundDiagnostics.Stage.DELIVERY_QUEUE, InboundDiagnostics.Reason.QUEUE_FULL);
                            } else outcome(queued, InboundDiagnostics.Stage.DELIVERY_QUEUE, InboundDiagnostics.Reason.ENQUEUED);
                        }
                    });
                } catch (RuntimeException exception) {
                    diagnostics.inbound().failure(InboundDiagnostics.Stage.NORMALIZE, InboundDiagnostics.eventKind(input.kind()), exception, 0);
                    diagnostics.error(MALFORMED, (System.nanoTime() - started) / 1_000_000);
                }
            }
        }

        void transition(State next) {
            synchronized (AbstractCollector.this) { if (this == run && active.get()) state = next; }
        }

        synchronized void attach(AutoCloseable resource) throws Exception {
            if (!active.get()) resource.close(); else connection = resource;
        }

        synchronized void closeConnection() {
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { diagnostics.error(TRANSPORT_FAILURE, 0); }
                connection = null;
            }
        }
        synchronized boolean hasConnection() { return connection != null; }
    }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
