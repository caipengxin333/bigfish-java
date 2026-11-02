package cn.bigfish.collector;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cn.bigfish.contract.CollectorPort;
import cn.bigfish.contract.LiveEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static cn.bigfish.collector.InboundDiagnostics.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static cn.bigfish.collector.WireFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class GiftDiagnosticsTest {
    private static final String SESSION = "00000000-0000-4000-8000-000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void mainCollectorLogsGiftStagesBadGiftIsolationAckDedupAndDispatchWithoutProbe() throws Exception {
        try (var capture = new Capture()) {
            var socket = new DouyinCollectorTest.FakeSocket(); var events = new CopyOnWriteArrayList<LiveEvent>();
            try (var collector = new DouyinCollector(room -> DouyinCollectorTest.connection("123"),
                    CollectorOptions.defaults(), Clock.systemUTC(), () -> socket)) {
                collector.start("123456", SESSION, events::add);
                await(() -> collector.state() == CollectorPort.State.CONNECTED);
                var missingId = envelope("WebcastGiftMessage", new WireFixtures().bytes(1, common(10, 123)).build());
                var wrongWire = envelope("WebcastGiftMessage", new WireFixtures().bytes(1, common(11, 123))
                        .number(2, 8).number(15, 1).build());
                byte[] terminal = gift(3, 7, 9, 3, false, true);
                socket.add(frame(true, true, gift(1, 7, 9, 1, false), gift(2, 7, 9, 3, false),
                        terminal, terminal, gift(4, 8, 9, 1, false), missingId, wrongWire,
                        envelope("WebcastFutureGiftMessage", new byte[]{0}), chat(5)));
                await(() -> events.size() == 4);
                await(() -> collector.diagnostics().count(CollectorDiagnostics.Counter.MALFORMED) == 2);
                collector.stop(); await(() -> collector.resources().released());
                var d = collector.diagnostics().inbound().snapshot();
                assertEquals(7L, d.methods().get("WebcastGiftMessage"));
                assertEquals(1L, d.methods().get("WebcastFutureGiftMessage"));
                assertEquals(1, collector.diagnostics().count(CollectorDiagnostics.Counter.ACK));
                assertEquals(List.of(1, 2, 1), events.stream().filter(e -> e.type() == LiveEvent.Type.GIFT)
                        .map(e -> e.payload().get("quantity")).toList());
                assertEquals(1, total(d, Stage.GIFT, Reason.WIRE_MISMATCH, Kind.GIFT));
                assertEquals(1, total(d, Stage.NORMALIZE, Reason.MISSING_GIFT_ID, Kind.GIFT));
                assertEquals(2, total(d, Stage.DEDUP, Reason.DUPLICATE_COMBO, Kind.GIFT));
                assertEquals(3, total(d, Stage.DISPATCH, Reason.SINK_RETURNED, Kind.GIFT));
                var lines = capture.json();
                var failure = lines.stream().filter(n -> n.path("code").asText().equals("WIRE_MISMATCH")).findFirst().orElseThrow();
                assertEquals(SESSION, failure.path("sessionId").asText());
                assertEquals("WebcastGiftMessage", failure.path("method").asText());
                assertEquals(1, failure.path("generation").asLong()); assertEquals(1, failure.path("frame").asLong());
                assertEquals(7, failure.path("message").asInt()); assertEquals(15, failure.path("field").asInt());
                assertEquals(0, failure.path("wire").asInt()); assertTrue(failure.path("dataLength").asInt() > 0);
                var summary = lines.stream().filter(n -> n.path("stage").asText().equals("WINDOW")).reduce((a,b) -> b).orElseThrow();
                assertEquals(7, summary.path("giftMethod").asInt()); assertEquals(1, summary.path("giftParseFailed").asInt());
                assertEquals(1, summary.path("giftNormalizeFailed").asInt()); assertEquals(3, summary.path("giftSinkReturned").asInt());
                assertFalse(capture.text().contains("测试观众")); assertFalse(capture.text().contains("你好"));
                assertFalse(capture.text().contains("synthetic-extension"));
            }
        }
    }

    @Test void firstErrorIsLoggedThenSummarizedWithoutLosingCountsOrExecutingWriterInRecord() throws Exception {
        var clock = new MutableClock(); var rows = new ArrayList<JsonNode>();
        var d = new InboundDiagnostics((level, text) -> rows.add(parse(text)));
        d.begin(SESSION, clock, true); d.nextConnection();
        try (var scope = d.use(d.nextFrame().message(1).method("WebcastGiftMessage"))) {
            d.failure(Stage.GIFT, Kind.GIFT, ProtoWire.fault(Reason.WIRE_MISMATCH, 15, 0, 0), 35);
            assertTrue(rows.isEmpty()); // JDK callbacks/record only mutate bounded data.
            d.flushLogs(false); assertEquals(1, rows.size()); assertEquals(1, rows.getFirst().path("total").asInt());
            for (int i = 0; i < 100; i++) d.failure(Stage.GIFT, Kind.GIFT,
                    new IllegalArgumentException("secret-cookie-url-body", new RuntimeException("secret-cause")), 42);
            d.flushLogs(false); // Different fixed reason: first INVALID_EVENT warning.
            assertEquals(2, rows.size());
            for (int i = 0; i < 100; i++) d.failure(Stage.GIFT, Kind.GIFT, new IllegalArgumentException("secret"), 42);
            d.flushLogs(false); assertEquals(2, rows.size());
            clock.advance(10); d.flushLogs(false); assertEquals(3, rows.size());
            assertEquals(200, rows.getLast().path("total").asInt()); assertEquals(100, rows.getLast().path("delta").asInt());
            assertTrue(rows.getLast().path("summary").asBoolean());
            assertEquals(200, total(d.snapshot(), Stage.GIFT, Reason.INVALID_EVENT, Kind.GIFT));
            assertFalse(rows.toString().contains("secret"));
        }
    }

    @Test void methodCountsPrecedeFilteringAndSafeUnknownNamesHaveDifferentSeverityFromFailures() throws Exception {
        try (var capture = new Capture()) {
            var d = new CollectorDiagnostics(); d.inbound().begin(SESSION, Clock.systemUTC(), true); d.inbound().nextConnection();
            new DouyinProtocol(4096, d).decode(frame(false, false,
                    envelope("WebcastRoomStatsMessage", new byte[]{0}), envelope("WebcastNewGiftMessage", new byte[]{0}),
                    envelope("WebcastCookieSecretMessage", new byte[]{0}), envelope("bad\nCookie=secret", new byte[]{0}),
                    envelope("WebcastGiftMessage", new byte[]{0})), "123");
            d.inbound().flushLogs(true);
            assertEquals(1L, d.inbound().snapshot().methods().get("WebcastGiftMessage"));
            assertEquals(2L, d.inbound().snapshot().methods().get("INVALID_METHOD"));
            assertEquals(1, d.count(CollectorDiagnostics.Counter.MALFORMED));
            assertTrue(capture.events.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                    && e.getFormattedMessage().contains("WebcastRoomStatsMessage")));
            assertTrue(capture.events.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                    && e.getFormattedMessage().contains("WebcastNewGiftMessage")));
            assertTrue(capture.events.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains("INVALID_TAG")));
            assertFalse(capture.text().contains("Secret")); assertFalse(capture.text().contains("Cookie="));
        }
    }

    @Test void boundedMethodNamesAndSampleCapsNeverLoseAggregateCountsOrHideGiftZero() {
        var clock = new MutableClock(); var rows = new ArrayList<JsonNode>();
        var d = new InboundDiagnostics((level, line) -> rows.add(parse(line)));
        d.begin("Cookie=sensitive-session", clock, true); d.nextConnection();
        for (int i = 0; i < 100; i++) {
            String suffix = "" + (char)('A' + i / 26) + (char)('A' + i % 26);
            d.methodSeen("WebcastFuture" + suffix + "Message");
        }
        assertTrue(d.snapshot().methods().size() <= 64);
        assertEquals(100, d.snapshot().methods().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(0L, d.snapshot().methods().get("WebcastGiftMessage"));
        for (var stage : Stage.values()) for (var reason : Reason.values()) for (var kind : Kind.values())
            d.record(stage, reason, kind, Shape.bytes(1));
        long expected = (long) Stage.values().length * Reason.values().length * Kind.values().length + 100;
        assertEquals(expected, d.snapshot().totals().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(d.snapshot().counts().size() <= 128); assertTrue(d.snapshot().samples().size() <= 64);
        d.flushLogs(true); assertTrue(rows.size() <= 257);
        assertFalse(rows.toString().contains("sensitive-session"));
        assertTrue(rows.stream().anyMatch(n -> n.path("code").asText().equals("LOG_CAPACITY")));
    }

    @Test void deliveryQueueFullExpiryAndSinkFailureAreSeparateGiftOutcomes() throws Exception {
        var clock = new MutableClock(); var defaults = CollectorOptions.defaults();
        var options = new CollectorOptions(1, Duration.ofSeconds(5), defaults.cacheCapacity(), defaults.cacheTtl(),
                defaults.reconnectBase(), defaults.reconnectMax(), defaults.heartbeatInterval(), defaults.idleTimeout(), defaults.maxFrameBytes());
        var socket = new DouyinCollectorTest.FakeSocket(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var collector = new DouyinCollector(room -> DouyinCollectorTest.connection("123"), options, clock, () -> socket)) {
            collector.start("123456", SESSION, event -> {
                entered.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                throw new IllegalStateException("secret-sink-content");
            });
            await(() -> socket.open);
            socket.frames.add(new CollectorSocket.Frame(frame(false, true, gift(1, 7, 1, 1, false)), clock.instant()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            socket.frames.add(new CollectorSocket.Frame(frame(false, true, gift(2, 7, 2, 1, false), gift(3, 7, 3, 1, false)), clock.instant()));
            await(() -> collector.diagnostics().count(CollectorDiagnostics.Counter.QUEUE_FULL) == 1);
            clock.advance(6); release.countDown();
            await(() -> collector.diagnostics().count(CollectorDiagnostics.Counter.EXPIRED) == 1);
            collector.stop(); await(() -> collector.resources().released());
            var snap = collector.diagnostics().inbound().snapshot();
            assertEquals(1, total(snap, Stage.DELIVERY_QUEUE, Reason.QUEUE_FULL, Kind.GIFT));
            assertEquals(1, total(snap, Stage.DELIVERY_QUEUE, Reason.TTL, Kind.GIFT));
            assertEquals(1, total(snap, Stage.DISPATCH, Reason.SINK_FAILED, Kind.GIFT));
            assertEquals(0, total(snap, Stage.DISPATCH, Reason.SINK_RETURNED, Kind.GIFT));
        } finally { release.countDown(); }
    }

    @Test void delayedDispatchKeepsOriginalConnectionAndSessionStartResetsMethodWindow() {
        var clock = new MutableClock(); var rows = new ArrayList<JsonNode>();
        var d = new InboundDiagnostics((level, line) -> rows.add(parse(line)));
        d.begin(SESSION, clock, true); d.nextConnection();
        var old = d.nextFrame().message(4).method("WebcastGiftMessage");
        d.nextConnection();
        try (var scope = d.use(old)) { d.record(Stage.DISPATCH, Reason.SINK_RETURNED, Kind.GIFT, null); }
        d.flushLogs(false);
        assertEquals(1, rows.getFirst().path("generation").asInt()); assertEquals(4, rows.getFirst().path("message").asInt());
        try (var scope = d.use(d.nextFrame().message(2).method("WebcastGiftMessage"))) {
            d.record(Stage.DISPATCH, Reason.SINK_RETURNED, Kind.GIFT, null);
        }
        clock.advance(10); d.flushLogs(false);
        assertEquals(2, rows.getLast().path("generation").asInt()); assertEquals(2, rows.getLast().path("total").asInt());
        d.begin("00000000-0000-4000-8000-000000000002", clock, true); d.flushLogs(true);
        assertEquals(0, rows.getLast().path("giftSinkReturned").asInt());
        assertTrue(rows.getLast().path("sessionId").asText().endsWith("0002"));
    }

    @Test void ackFailureIsVisibleAndDoesNotPretendParsedGiftReachedSink() throws Exception {
        var socket = new DouyinCollectorTest.FakeSocket() {
            @Override public void acknowledge(byte[] bytes) { throw new IllegalStateException("secret-ack-url"); }
        };
        var defaults = CollectorOptions.defaults();
        var options = new CollectorOptions(8, defaults.queueTtl(), defaults.cacheCapacity(), defaults.cacheTtl(),
                Duration.ofSeconds(10), Duration.ofSeconds(10), defaults.heartbeatInterval(), defaults.idleTimeout(), defaults.maxFrameBytes());
        try (var capture = new Capture();
             var collector = new DouyinCollector(room -> DouyinCollectorTest.connection("123"), options, Clock.systemUTC(), () -> socket)) {
            collector.start("123456", SESSION, event -> fail("ACK failed before dispatch")); await(() -> socket.open);
            socket.add(frame(true, true, gift(1, 7, 1, 1, false)));
            await(() -> collector.state() == CollectorPort.State.RECONNECTING);
            collector.stop(); await(() -> collector.resources().released());
            var d = collector.diagnostics().inbound().snapshot();
            assertEquals(1L, d.methods().get("WebcastGiftMessage"));
            assertEquals(1, total(d, Stage.ACK_SEND, Reason.ACK_SEND_FAILED, Kind.NONE));
            assertEquals(1, total(d, Stage.DELIVERY_QUEUE, Reason.ACK_BLOCKED, Kind.GIFT));
            assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.ACK));
            assertEquals(0, total(d, Stage.DISPATCH, Reason.SINK_RETURNED, Kind.GIFT));
            var ackFailure = capture.json().stream().filter(n -> n.path("code").asText().equals("ACK_SEND_FAILED"))
                    .findFirst().orElseThrow();
            assertEquals(1, ackFailure.path("generation").asInt());
            assertEquals(1, ackFailure.path("frame").asInt());
            assertEquals("FRAME", ackFailure.path("method").asText());
            assertFalse(capture.text().contains("secret-ack-url"));
        }
    }

    private static long total(Snapshot d, Stage stage, Reason reason, Kind kind) {
        return d.totals().getOrDefault(new Key(stage, reason, kind), 0L);
    }
    private static JsonNode parse(String text) {
        try { return JSON.readTree(text); } catch (Exception e) { throw new AssertionError("Invalid diagnostic JSON"); }
    }
    static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-02T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    private static class Capture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        final Logger logger = (Logger) LoggerFactory.getLogger(InboundDiagnostics.class);
        final ch.qos.logback.classic.Level previous = logger.getLevel();
        Capture() { start(); logger.addAppender(this); logger.setLevel(ch.qos.logback.classic.Level.DEBUG); }
        protected void append(ILoggingEvent event) { events.add(event); }
        String text() { return events.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a,b) -> a + b); }
        List<JsonNode> json() { return events.stream().map(e -> parse(e.getFormattedMessage().substring("collector_inbound ".length()))).toList(); }
        public void close() { logger.detachAppender(this); logger.setLevel(previous); stop(); }
    }
}
