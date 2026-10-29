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
        context.events.clear();
        context.source.interrupt(); context.dispatcher.interrupt();
        context.closeConnection();
        state = terminal;
    }

    protected abstract void collect(Run context) throws Exception;

    protected final class Run {
        final String requestedRoom;
        final String sessionId;
        final AtomicBoolean active = new AtomicBoolean(true);
        private final ArrayBlockingQueue<LiveEvent> events = new ArrayBlockingQueue<>(options.queueCapacity());
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
                catch (Exception exception) {
                    if (active.get()) { diagnostics.error(CONNECT_FAILURE, elapsedMillis(started)); finish(this, State.FAILED); }
                }
            });
            dispatcher = Thread.ofPlatform().daemon().name("bigfish-collector-dispatch").unstarted(() -> {
                while (active.get()) {
                    try {
                        LiveEvent event = events.take();
                        if (!active.get()) break;
                        if (!event.receivedAt().plus(options.queueTtl()).isAfter(clock.instant())) {
                            diagnostics.increment(EXPIRED); continue;
                        }
                        long started = System.nanoTime();
                        try { sink.accept(event); diagnostics.increment(EMITTED); }
                        catch (RuntimeException exception) { diagnostics.error(SINK_FAILURE, elapsedMillis(started)); }
                    } catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
                }
            });
        }

        void accept(IncomingMessage input, Instant receivedAt) {
            if (!active.get()) return;
            if (input.kind().equals("ENDED")) { diagnostics.increment(ENDED); finish(this, State.ENDED); return; }
            long started = System.nanoTime();
            try {
                normalizer.normalize(input, receivedAt).ifPresent(event -> {
                    if (active.get() && !events.offer(event)) diagnostics.increment(QUEUE_FULL);
                });
            } catch (RuntimeException exception) {
                diagnostics.error(MALFORMED, (System.nanoTime() - started) / 1_000_000);
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
    }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
