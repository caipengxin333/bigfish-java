package cn.bigfish.collector;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static org.junit.jupiter.api.Assertions.*;

class JdkCollectorSocketTest {
    @Test void rejectedHandshakeProducesFixedPermanentCodeWithoutResponseBody() throws Exception {
        try (var peer = new DouyinRoomResolverTest.LocalHttp(exchange -> {
            exchange.sendResponseHeaders(403, -1); exchange.close();
        }); var socket = new JdkCollectorSocket(CollectorOptions.defaults(), new CollectorDiagnostics(), Clock.systemUTC())) {
            var failure = assertThrows(DouyinFailure.class, () -> socket.openEndpoint(
                    URI.create(peer.uri().toString().replace("http:", "ws:")), Map.of()));
            assertEquals(DouyinFailure.Code.HANDSHAKE_REJECTED, failure.code()); assertNull(failure.getCause());
            assertFalse(failure.code().retryable()); assertFalse(socket.isOpen());
        }
    }

    @Test void realJdkTransportReassemblesFragmentsAndSendsPingAckAndAutomaticPong() throws Exception {
        var diagnostics = new CollectorDiagnostics();
        try (var peer = new LoopbackPeer(); var socket = new JdkCollectorSocket(CollectorOptions.defaults(), diagnostics, Clock.systemUTC())) {
            socket.openEndpoint(peer.uri(), Map.of());
            byte[] packet = WireFixtures.frame(true, true, WireFixtures.chat(1));
            peer.send(2, false, Arrays.copyOfRange(packet, 0, 7));
            peer.send(9, true, new byte[]{42});
            peer.send(0, true, Arrays.copyOfRange(packet, 7, packet.length));
            var received = socket.poll(Duration.ofSeconds(2));
            assertNotNull(received); assertArrayEquals(packet, received.bytes());
            var pong = peer.received.poll(2, TimeUnit.SECONDS);
            assertNotNull(pong); assertEquals(10, pong.opcode()); assertArrayEquals(new byte[]{42}, pong.bytes());
            byte[] heartbeat = HexFormat.of().parseHex("3a026862");
            socket.heartbeat(heartbeat, DouyinConnection.Heartbeat.WEBSOCKET_PING);
            var ping = peer.received.poll(2, TimeUnit.SECONDS);
            assertNotNull(ping); assertEquals(9, ping.opcode()); assertArrayEquals(heartbeat, ping.bytes());
            byte[] ack = HexFormat.of().parseHex("105b3a0361636b420178");
            socket.acknowledge(ack);
            var acknowledgement = peer.received.poll(2, TimeUnit.SECONDS);
            assertNotNull(acknowledgement); assertEquals(2, acknowledgement.opcode()); assertArrayEquals(ack, acknowledgement.bytes());
            socket.heartbeat(heartbeat, DouyinConnection.Heartbeat.BINARY_FRAME);
            var binaryHeartbeat = peer.received.poll(2, TimeUnit.SECONDS);
            assertNotNull(binaryHeartbeat); assertEquals(2, binaryHeartbeat.opcode());
            peer.send(8, true, new byte[]{3, (byte) 232}); // normal close code 1000
            await(() -> !socket.isOpen()); peer.assertHealthy();
        }
    }

    @Test void oversizedFrameAndQueueOverflowStayBoundedAndNextFrameStillWorks() throws Exception {
        var d = CollectorOptions.defaults();
        var options = new CollectorOptions(1, d.queueTtl(), d.cacheCapacity(), d.cacheTtl(), d.reconnectBase(),
                d.reconnectMax(), d.heartbeatInterval(), d.idleTimeout(), 128);
        var diagnostics = new CollectorDiagnostics();
        try (var peer = new LoopbackPeer(); var socket = new JdkCollectorSocket(options, diagnostics, Clock.systemUTC())) {
            socket.openEndpoint(peer.uri(), Map.of());
            peer.send(2, false, new byte[100]); peer.send(0, true, new byte[50]);
            peer.send(2, true, new byte[]{1}); peer.send(2, true, new byte[]{2});
            await(() -> diagnostics.count(QUEUE_FULL) == 1);
            assertEquals(1, diagnostics.count(MALFORMED));
            assertArrayEquals(new byte[]{1}, socket.poll(Duration.ofSeconds(1)).bytes());
            peer.send(2, true, new byte[]{3});
            assertArrayEquals(new byte[]{3}, socket.poll(Duration.ofSeconds(1)).bytes());
            assertTrue(socket.isOpen()); peer.assertHealthy();
        }
    }

    @Test void fragmentedMessageHasDeadlineAndNextMessageSurvives() throws Exception {
        var d = CollectorOptions.defaults();
        var options = new CollectorOptions(8, Duration.ofMillis(30), d.cacheCapacity(), d.cacheTtl(), d.reconnectBase(),
                d.reconnectMax(), d.heartbeatInterval(), d.idleTimeout(), d.maxFrameBytes());
        var diagnostics = new CollectorDiagnostics();
        try (var peer = new LoopbackPeer(); var socket = new JdkCollectorSocket(options, diagnostics, Clock.systemUTC())) {
            socket.openEndpoint(peer.uri(), Map.of());
            peer.send(2, false, new byte[]{1});
            // A peer Ping/Pong round trip ensures the initial fragment was consumed before waiting.
            peer.send(9, true, new byte[]{1}); assertNotNull(peer.received.poll(2, TimeUnit.SECONDS));
            Thread.sleep(60); peer.send(0, true, new byte[]{2}); peer.send(2, true, new byte[]{3});
            assertArrayEquals(new byte[]{3}, socket.poll(Duration.ofSeconds(2)).bytes());
            assertEquals(1, diagnostics.count(MALFORMED)); peer.assertHealthy();
        }
    }

    @Test void closingDuringHandshakeCancelsOpenAndRejectsFutureOpen() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var socket = new JdkCollectorSocket(CollectorOptions.defaults(), new CollectorDiagnostics(), Clock.systemUTC())) {
            var done = new CountDownLatch(1);
            var worker = Thread.ofPlatform().daemon().start(() -> {
                try { socket.openEndpoint(URI.create("ws://127.0.0.1:" + server.getLocalPort()), Map.of()); }
                catch (Exception expected) { /* A cancelled open must not expose its URI in diagnostics. */ }
                finally { done.countDown(); }
            });
            server.setSoTimeout(2000);
            try (Socket accepted = server.accept()) {
                socket.close(); assertTrue(done.await(2, TimeUnit.SECONDS)); assertFalse(socket.isOpen());
                assertThrows(InterruptedException.class, () -> socket.openEndpoint(URI.create("ws://127.0.0.1:1"), Map.of()));
                worker.join(1000);
            }
        }
    }

    /** Tiny RFC6455 loopback peer. No external network, cookies or platform services. */
    private static final class LoopbackPeer implements AutoCloseable {
        record ClientFrame(int opcode, byte[] bytes) { }
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final ArrayBlockingQueue<ClientFrame> received = new ArrayBlockingQueue<>(16);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch ready = new CountDownLatch(1);
        final Thread worker;
        volatile Socket accepted;
        volatile boolean closing;

        LoopbackPeer() throws Exception {
            worker = Thread.ofPlatform().daemon().start(() -> {
                try (Socket client = server.accept()) {
                    accepted = client; client.setSoTimeout(4000);
                    InputStream in = client.getInputStream();
                    var request = new ByteArrayOutputStream();
                    while (request.size() < 8192 && !request.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) request.write(read(in));
                    String key = Arrays.stream(request.toString(StandardCharsets.US_ASCII).split("\r\n"))
                            .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:"))
                            .findFirst().orElseThrow().split(":", 2)[1].trim();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    client.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                            + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush(); ready.countDown();
                    while (!closing) {
                        int head = in.read(); if (head == -1) break;
                        int size = read(in); if ((size & 128) == 0) throw new AssertionError("Client frames must be masked");
                        int length = size & 127;
                        if (length == 126) length = read(in) * 256 + read(in);
                        if (length == 127 || length > 4096) throw new AssertionError("Unexpected client payload size");
                        byte[] mask = in.readNBytes(4), payload = in.readNBytes(length);
                        if (mask.length != 4 || payload.length != length) throw new EOFException();
                        for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                        received.add(new ClientFrame(head & 15, payload));
                        if ((head & 15) == 9) send(10, true, payload);
                        if ((head & 15) == 8) break;
                    }
                } catch (Exception | AssertionError exception) { if (!closing) failure.set(exception); }
                finally { ready.countDown(); }
            });
        }
        URI uri() { return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/test"); }
        synchronized void send(int opcode, boolean last, byte[] bytes) throws Exception {
            if (!ready.await(2, TimeUnit.SECONDS)) throw new AssertionError("Handshake not ready");
            var out = accepted.getOutputStream(); out.write((last ? 128 : 0) | opcode);
            if (bytes.length < 126) out.write(bytes.length);
            else { out.write(126); out.write(bytes.length >>> 8); out.write(bytes.length & 255); }
            out.write(bytes); out.flush();
        }
        private static int read(InputStream input) throws Exception {
            int value = input.read(); if (value < 0) throw new EOFException(); return value;
        }
        void assertHealthy() { assertNull(failure.get(), () -> String.valueOf(failure.get())); }
        @Override public void close() throws Exception {
            closing = true; server.close();
            Socket client = accepted; if (client != null) client.close();
            worker.join(1000);
        }
    }
}
