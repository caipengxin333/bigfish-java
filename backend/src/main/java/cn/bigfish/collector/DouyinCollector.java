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
            try {
                DouyinConnection connection = provider.resolve(webRoom, resolvedRoom);
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
        if (!frame.receivedAt().plus(options.queueTtl()).isAfter(clock.instant())) { diagnostics().increment(EXPIRED); return; }
        DouyinProtocol.Batch batch;
        try { batch = protocol.decode(frame.bytes(), room); }
        catch (IOException | IllegalArgumentException exception) { diagnostics().error(MALFORMED, 0); return; }
        // When the peer already closed, an ACK cannot be sent; still consume its final control packet.
        if (batch.acknowledgement() != null && socket.isOpen()) {
            socket.acknowledge(batch.acknowledgement()); diagnostics().increment(ACK);
        }
        for (IncomingMessage message : batch.messages()) context.accept(message, frame.receivedAt());
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
