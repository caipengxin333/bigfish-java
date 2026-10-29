package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import cn.bigfish.contract.LiveEvent;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static cn.bigfish.collector.WireFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class DouyinCollectorTest {
    static DouyinConnection connection(String room) {
        return new DouyinConnection(room, URI.create("wss://webcast100-ws-web-lq.douyin.com/webcast/im/push/v2/"),
                Map.of(), DouyinConnection.Heartbeat.WEBSOCKET_PING);
    }
    static CollectorOptions quick() {
        var d = CollectorOptions.defaults();
        return new CollectorOptions(8, Duration.ofSeconds(1), d.cacheCapacity(), d.cacheTtl(),
                Duration.ofMillis(20), Duration.ofMillis(80), Duration.ofMillis(10), Duration.ofMillis(300), d.maxFrameBytes());
    }

    @Test void reconnectRetainsDedupAndComboStateAndMalformedFrameDoesNotDisconnect() throws Exception {
        var sockets = new CopyOnWriteArrayList<FakeSocket>();
        var events = new CopyOnWriteArrayList<LiveEvent>();
        try (var collector = new DouyinCollector(room -> connection("123"), quick(), Clock.systemUTC(), () -> {
            var socket = new FakeSocket(); sockets.add(socket); return socket;
        })) {
            collector.start("999", "session-1", events::add);
            await(() -> sockets.size() == 1 && sockets.getFirst().open);
            var first = sockets.getFirst();
            first.add(new byte[]{0}); first.add(frame(true, true, gift(1, 7, 55, 1, false)));
            await(() -> events.size() == 1);
            assertEquals(1, sockets.size()); assertEquals(1, collector.diagnostics().count(MALFORMED));
            first.open = false;
            await(() -> sockets.size() == 2 && sockets.getLast().open);
            sockets.getLast().add(frame(true, true, gift(1, 7, 55, 1, false), gift(2, 7, 55, 3, true), gift(3, 8, 55, 1, false)));
            await(() -> events.size() == 3);
            assertEquals(List.of(1, 2, 1), events.stream().map(event -> event.payload().get("quantity")).toList());
            assertTrue(events.stream().allMatch(event -> event.roomId().equals("123") && event.sessionId().equals("session-1")));
            assertEquals(2, collector.diagnostics().count(ACK));
            assertEquals(1, collector.diagnostics().count(RECONNECT));
            assertEquals("DOUYIN", collector.mode());
        }
        assertTrue(sockets.stream().allMatch(socket -> socket.closed));
    }

    @Test void slowBusinessSinkDoesNotBlockAckHeartbeatOrReceiving() throws Exception {
        var socket = new FakeSocket(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var collector = new DouyinCollector(room -> connection("123"), quick(), Clock.systemUTC(), () -> socket)) {
            collector.start("999", "s", event -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            await(() -> socket.open); socket.add(frame(false, true, chat(1)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            socket.add(frame(false, true, chat(2)));
            await(() -> collector.diagnostics().count(ACK) == 2 && collector.diagnostics().count(HEARTBEAT) >= 2);
            assertEquals(2, collector.diagnostics().count(RECEIVED));
        } finally { release.countDown(); }
    }

    @Test void endControlStopsWithoutReconnectAndLateFramesAreNotDispatched() throws Exception {
        var socket = new FakeSocket(); var deliveries = new AtomicInteger(); var resolutions = new AtomicInteger();
        try (var collector = new DouyinCollector(room -> { resolutions.incrementAndGet(); return connection("123"); }, quick(),
                Clock.systemUTC(), () -> socket)) {
            collector.start("999", "s", event -> deliveries.incrementAndGet());
            await(() -> socket.open); socket.add(frame(false, false, end(123), chat(1)));
            await(() -> collector.state() == CollectorPort.State.ENDED);
            assertTrue(socket.closed); assertEquals(0, deliveries.get());
            assertEquals(0, collector.diagnostics().count(RECONNECT)); assertEquals(1, resolutions.get());
            collector.stop(); collector.stop(); assertEquals(CollectorPort.State.STOPPED, collector.state());
        }
    }

    @Test void providerCanReportEndedAndAChangedInternalRoomEndsOldSession() throws Exception {
        var created = new AtomicInteger();
        try (var ended = new DouyinCollector(room -> DouyinConnection.ended("123"), quick(), Clock.systemUTC(), () -> {
            created.incrementAndGet(); return new FakeSocket();
        })) {
            ended.start("999", "s", event -> fail()); await(() -> ended.state() == CollectorPort.State.ENDED);
            assertEquals(0, created.get());
        }
        var calls = new AtomicInteger(); var first = new FakeSocket();
        try (var changed = new DouyinCollector(room -> connection(calls.incrementAndGet() == 1 ? "123" : "124"),
                quick(), Clock.systemUTC(), () -> first)) {
            changed.start("999", "s", event -> fail()); await(() -> first.open); first.open = false;
            await(() -> changed.state() == CollectorPort.State.ENDED); assertEquals(2, calls.get());
        }
    }

    @Test void endQueuedBeforePeerCloseWinsOverReconnect() throws Exception {
        var socket = new FakeSocket() {
            @Override public void open(DouyinConnection connection) {
                add(frame(false, true, end(123))); open = false;
            }
        };
        try (var collector = new DouyinCollector(room -> connection("123"), quick(), Clock.systemUTC(), () -> socket)) {
            collector.start("999", "s", event -> fail());
            await(() -> collector.state() == CollectorPort.State.ENDED);
            assertEquals(0, collector.diagnostics().count(RECONNECT));
            assertEquals(0, collector.diagnostics().count(ACK));
        }
    }

    @Test void reconnectBackoffIsCappedAndStopCancelsWaitingRetry() throws Exception {
        var calls = new AtomicInteger(); var d = quick();
        var options = new CollectorOptions(d.queueCapacity(), d.queueTtl(), d.cacheCapacity(), d.cacheTtl(),
                Duration.ofMillis(200), Duration.ofMillis(800), d.heartbeatInterval(), d.idleTimeout(), d.maxFrameBytes());
        try (var collector = new DouyinCollector(room -> { calls.incrementAndGet(); throw new IOException("redacted-test-failure"); },
                options, Clock.systemUTC(), FakeSocket::new)) {
            assertEquals(200, collector.reconnectDelayMillis(0, 1));
            assertEquals(400, collector.reconnectDelayMillis(1, 1));
            assertEquals(800, collector.reconnectDelayMillis(2, 1));
            assertEquals(800, collector.reconnectDelayMillis(1000, 1));
            assertEquals(600, collector.reconnectDelayMillis(1000, 0));
            collector.start("999", "s", event -> fail()); await(() -> collector.state() == CollectorPort.State.RECONNECTING);
            collector.stop(); Thread.sleep(250); assertEquals(1, calls.get());
            assertEquals(CollectorPort.State.STOPPED, collector.state());
        }
    }

    @Test void stopInterruptsPendingOpenAndPermanentProviderFailureDoesNotRetry() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var socket = new FakeSocket() {
            @Override public void open(DouyinConnection connection) throws InterruptedException {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException exception) { interrupted.countDown(); throw exception; }
            }
        };
        try (var collector = new DouyinCollector(room -> connection("123"), quick(), Clock.systemUTC(), () -> socket)) {
            collector.start("999", "s", event -> fail()); assertTrue(entered.await(2, TimeUnit.SECONDS));
            collector.stop(); assertTrue(interrupted.await(2, TimeUnit.SECONDS)); assertTrue(socket.closed);
            assertEquals(0, collector.diagnostics().count(RECONNECT));
        }
        try (var unavailable = new DouyinCollector(room -> { throw new DouyinConnectionProvider.UnavailableException(); })) {
            unavailable.start("999", "s", event -> fail()); await(() -> unavailable.state() == CollectorPort.State.FAILED);
            assertEquals(0, unavailable.diagnostics().count(RECONNECT));
            assertEquals("CONNECT_FAILURE", unavailable.diagnostics().lastError());
        }
    }

    @Test void idleTimeoutReconnectsAndStaleFramesExpireBeforeDecoding() throws Exception {
        var sockets = new CopyOnWriteArrayList<FakeSocket>();
        try (var collector = new DouyinCollector(room -> connection("123"), quick(), Clock.systemUTC(), () -> {
            var socket = new FakeSocket(); sockets.add(socket); return socket;
        })) {
            collector.start("999", "s", event -> fail()); await(() -> !sockets.isEmpty() && sockets.getFirst().open);
            sockets.getFirst().frames.add(new CollectorSocket.Frame(frame(false, true, chat(1)), Instant.now().minusSeconds(10)));
            await(() -> collector.diagnostics().count(EXPIRED) == 1);
            await(() -> sockets.size() >= 2);
            assertEquals(0, collector.diagnostics().count(ACK)); assertEquals(0, collector.diagnostics().count(RECEIVED));
        }
    }

    @Test void connectionMaterialHasRedactedRenderingAndStrictDestinationValidation() {
        assertEquals("DouyinConnection[redacted]", connection("123").toString());
        for (String endpoint : List.of("ws://webcast100-ws-web-lq.douyin.com/webcast/im/push/v2/",
                "wss://evil.example/webcast/im/push/v2/", "wss://webcast100-ws-web-lq.douyin.com.evil.example/webcast/im/push/v2/")) {
            var exception = assertThrows(IllegalArgumentException.class, () -> new DouyinConnection("123", URI.create(endpoint),
                    Map.of(), DouyinConnection.Heartbeat.WEBSOCKET_PING));
            assertEquals("Unsupported Douyin endpoint", exception.getMessage());
        }
    }

    static class FakeSocket implements CollectorSocket {
        final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(16);
        volatile boolean open, closed;
        volatile long activity = System.nanoTime();
        @Override public void open(DouyinConnection connection) throws Exception { activity = System.nanoTime(); open = true; }
        void add(byte[] bytes) { activity = System.nanoTime(); frames.add(new Frame(bytes, Instant.now())); }
        @Override public Frame poll(Duration wait) throws InterruptedException { return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS); }
        @Override public boolean isOpen() { return open; }
        @Override public long lastActivityNanos() { return activity; }
        @Override public void acknowledge(byte[] frame) { if (!open) throw new IllegalStateException("closed"); }
        @Override public void heartbeat(byte[] frame, DouyinConnection.Heartbeat kind) { if (!open) throw new IllegalStateException("closed"); }
        @Override public void close() { open = false; closed = true; frames.clear(); }
    }
}
