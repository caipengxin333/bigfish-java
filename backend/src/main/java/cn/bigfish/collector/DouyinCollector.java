package cn.bigfish.collector;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;

/** Live transport adapter; requires an explicitly supplied, authorized room/signature provider. */
public final class DouyinCollector extends AbstractCollector {
    private final DouyinConnectionProvider provider;
    private final Supplier<CollectorSocket> sockets;

    public DouyinCollector(DouyinConnectionProvider provider) {
        this(provider, CollectorOptions.defaults(), Clock.systemUTC(), null);
    }
    DouyinCollector(DouyinConnectionProvider provider, CollectorOptions options, Clock clock, Supplier<CollectorSocket> sockets) {
        super(options, clock); this.provider = Objects.requireNonNull(provider);
        if (provider instanceof WebDouyinConnectionProvider web) diagnostics().inbound().credentials(web.credentialMode());
        this.sockets = sockets == null ? () -> new JdkCollectorSocket(options, diagnostics(), clock) : sockets;
    }
    @Override public String mode() { return "DOUYIN"; }

    @Override protected void collect(Run context) throws Exception {
        String webRoom = DouyinRoomInput.parse(context.requestedRoom).webRoomId();
        var protocol = new DouyinProtocol(options.maxFrameBytes(), diagnostics());
        int failures = 0;
        String resolvedRoom = null;
        while (context.active.get()) {
            long started = System.nanoTime();
            diagnostics().inbound().nextConnection();
            try {
                DouyinConnection connection;
                try { connection = provider.resolve(webRoom, resolvedRoom); }
                finally {
                    if (provider instanceof WebDouyinConnectionProvider web)
                        diagnostics().inbound().credentialEvidence(web.credentialEvidence());
                }
                if (!context.active.get()) return;
                // A new broadcast in the same web room requires a new backend session.
                if (resolvedRoom != null && !resolvedRoom.equals(connection.internalRoomId)) {
                    diagnostics().increment(ROOM_CHANGED);
                    context.accept(ended(resolvedRoom), clock.instant()); return;
                }
                resolvedRoom = connection.internalRoomId;
                if (connection.ended) { context.accept(ended(resolvedRoom), clock.instant()); return; }
                CollectorSocket socket = sockets.get(); context.attach(socket);
                if (!context.active.get()) return;
                diagnostics().increment(HANDSHAKE_ATTEMPT); socket.open(connection);
                if (!context.active.get()) return;
                context.transition(State.CONNECTED);
                diagnostics().increment(CONNECTED);
                long connectedAt = System.nanoTime(), nextHeartbeat = connectedAt;
                while (context.active.get()) {
                    var frame = socket.poll(Duration.ofMillis(Math.min(100, options.heartbeatInterval().toMillis())));
                    if (frame != null) {
                        consume(context, protocol, socket, frame, resolvedRoom);
                    } else if (!socket.isOpen()) throw new IOException("Transport closed");
                    diagnostics().inbound().flushLogs(false);
                    // Drain already received control frames even when onClose has arrived immediately after them.
                    if (!context.active.get()) return;
                    long now = System.nanoTime();
                    if (socket.isOpen() && now >= nextHeartbeat) {
                        socket.heartbeat(ProtoWire.heartbeat(), connection.heartbeat); diagnostics().increment(HEARTBEAT);
                        nextHeartbeat = now + options.heartbeatInterval().toNanos();
                    }
                    if (System.nanoTime() - socket.lastActivityNanos() >= options.idleTimeout().toNanos()) throw new IOException("Transport idle");
                    // Brief successful handshakes must not reset exponential backoff.
                    if (System.nanoTime() - connectedAt >= options.idleTimeout().toNanos()) failures = 0;
                }
            } catch (DouyinConnectionProvider.UnavailableException exception) { throw exception; }
            catch (InterruptedException exception) { throw exception; }
            catch (DouyinFailure failure) {
                if (!context.active.get()) return;
                if (!failure.code().retryable()) throw failure;
                diagnostics().error(failure.code(), (System.nanoTime() - started) / 1_000_000);
            }
            catch (Exception exception) {
                if (!context.active.get()) return;
                diagnostics().error(TRANSPORT_FAILURE, (System.nanoTime() - started) / 1_000_000);
            } finally { context.closeConnection(); }
            if (!context.active.get()) return;
            context.transition(State.RECONNECTING); diagnostics().increment(RECONNECT);
            long delay = reconnectDelayMillis(failures, ThreadLocalRandom.current().nextDouble());
            failures = Math.min(62, failures + 1);
            Thread.sleep(delay);
        }
    }

    private void consume(Run context, DouyinProtocol protocol, CollectorSocket socket,
                         CollectorSocket.Frame frame, String room) throws Exception {
        if (!frame.receivedAt().plus(options.queueTtl()).isAfter(clock.instant())) {
            diagnostics().increment(EXPIRED);
            diagnostics().inbound().record(InboundDiagnostics.Stage.QUEUE, InboundDiagnostics.Reason.TTL, null); return;
        }
        diagnostics().inbound().record(InboundDiagnostics.Stage.QUEUE, InboundDiagnostics.Reason.OK, null);
        DouyinProtocol.Batch batch;
        try { batch = protocol.decode(frame.bytes(), room); }
        catch (IOException | IllegalArgumentException exception) { diagnostics().error(MALFORMED, 0); return; }
        // When the peer already closed, an ACK cannot be sent; still consume its final control packet.
        if (batch.acknowledgement() != null && socket.isOpen()) {
            try (var frameScope = diagnostics().inbound().use(batch.frameTrace())) {
                try {
                    socket.acknowledge(batch.acknowledgement()); diagnostics().increment(ACK);
                    diagnostics().inbound().record(InboundDiagnostics.Stage.ACK_SEND, InboundDiagnostics.Reason.ACK_SENT, null);
                } catch (Exception exception) {
                    diagnostics().inbound().record(InboundDiagnostics.Stage.ACK_SEND, InboundDiagnostics.Reason.ACK_SEND_FAILED, null);
                    for (int index = 0; index < batch.messages().size(); index++) {
                        try (var scope = diagnostics().inbound().use(batch.traces().get(index))) {
                            diagnostics().inbound().record(InboundDiagnostics.Stage.DELIVERY_QUEUE, InboundDiagnostics.Reason.ACK_BLOCKED,
                                    InboundDiagnostics.eventKind(batch.messages().get(index).kind()), null);
                        }
                    }
                    throw exception;
                }
            }
        }
        for (int index = 0; index < batch.messages().size(); index++)
            context.accept(batch.messages().get(index), frame.receivedAt(), batch.traces().get(index));
    }

    long reconnectDelayMillis(int failure, double jitter) {
        long base = options.reconnectBase().toMillis(), maximum = options.reconnectMax().toMillis();
        long ceiling = base;
        for (int index = 0; index < Math.min(failure, 62) && ceiling < maximum; index++) {
            ceiling = ceiling > maximum / 2 ? maximum : Math.min(maximum, ceiling * 2);
        }
        return Math.max(1, (long) (ceiling * (0.75 + Math.max(0, Math.min(1, jitter)) * 0.25)));
    }
    private static IncomingMessage ended(String room) {
        return new IncomingMessage("ENDED", "", room, "", "", 0, "", "", "", 0, "", null, 0);
    }
}
