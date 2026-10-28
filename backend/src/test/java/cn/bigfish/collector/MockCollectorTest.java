package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import cn.bigfish.contract.LiveEvent;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;
import static org.junit.jupiter.api.Assertions.*;

class MockCollectorTest {
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }

    @Test void replaysSyntheticSchemaAndContinuesAfterMalformedAndUnknownPackets() throws Exception {
        var received = new CopyOnWriteArrayList<LiveEvent>();
        try (var collector = new MockCollector(() -> getClass().getResourceAsStream("/collector/events.jsonl"))) {
            collector.start("web-number", "mock-session", received::add);
            await(() -> received.size() == 9);
            assertEquals("MOCK", collector.mode());
            assertEquals(CollectorPort.State.CONNECTED, collector.state());
            assertEquals(List.of(1, 1, 2, 1, 1), received.stream().filter(e -> e.type() == LiveEvent.Type.GIFT)
                    .map(e -> e.payload().get("quantity")).toList());
            assertTrue(received.stream().allMatch(e -> e.schemaVersion() == 1 && e.sessionId().equals("mock-session")
                    && e.roomId().equals("mock-room") && !e.eventId().isBlank() && e.receivedAt() != null));
            assertEquals(1, collector.diagnostics().count(MALFORMED));
            assertEquals(1, collector.diagnostics().count(UNKNOWN));
            assertEquals(2, collector.diagnostics().count(DUPLICATE));
        }
    }

    @Test void endedStopsSourceAndStopIsIdempotent() throws Exception {
        String fixture = "{\"kind\":\"ENDED\"}\n{\"kind\":\"FOLLOW\",\"messageId\":\"late\",\"roomId\":\"room\"}\n";
        try (var collector = from(fixture, CollectorOptions.defaults(), Clock.systemUTC())) {
            collector.start("web-number", "s", event -> fail("No delivery after end"));
            await(() -> collector.state() == CollectorPort.State.ENDED);
            assertEquals(1, collector.diagnostics().count(ENDED));
            collector.stop(); collector.stop();
            assertEquals(CollectorPort.State.STOPPED, collector.state());
        }
    }

    @Test void slowConsumerDoesNotBlockSourceAndQueueIsBoundedAndExpires() throws Exception {
        var clock = new MutableClock();
        var options = optionsWithQueue(1, Duration.ofMillis(100));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<LiveEvent>();
        // Feed the remaining packets only once the dispatcher is blocked in the first callback.
        var fixture = new java.io.PipedInputStream();
        var writer = new java.io.PipedOutputStream(fixture);
        try (var collector = new MockCollector(() -> fixture, options, clock)) {
            collector.start("room", "s", event -> {
                delivered.add(event); entered.countDown();
                try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
            writer.write(line("1").getBytes(StandardCharsets.UTF_8)); writer.flush();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            writer.write((line("2") + line("3")).getBytes(StandardCharsets.UTF_8)); writer.flush();
            await(() -> collector.diagnostics().count(RECEIVED) == 3);
            assertEquals(1, collector.diagnostics().count(QUEUE_FULL));
            clock.now.set(clock.instant().plusSeconds(1)); release.countDown();
            await(() -> collector.diagnostics().count(EXPIRED) == 1);
            assertEquals(1, delivered.size());
        } finally { release.countDown(); writer.close(); }
    }

    @Test void throwingSinkDoesNotKillDispatchAndDiagnosticsDoNotRetainExceptionText() throws Exception {
        try (var collector = from(line("1") + line("2"), CollectorOptions.defaults(), Clock.systemUTC())) {
            collector.start("room", "s", event -> { throw new IllegalStateException("sensitive-context-not-for-logs"); });
            await(() -> collector.diagnostics().count(SINK_FAILURE) == 2);
            assertEquals("SINK_FAILURE", collector.diagnostics().lastError());
            assertEquals(CollectorPort.State.CONNECTED, collector.state());
        }
    }

    @Test void oversizedLineDoesNotPreventNextPacket() throws Exception {
        var d = CollectorOptions.defaults();
        var options = new CollectorOptions(8, d.queueTtl(), d.cacheCapacity(), d.cacheTtl(), d.reconnectBase(),
                d.reconnectMax(), d.heartbeatInterval(), d.idleTimeout(), 128);
        try (var collector = from("x".repeat(129) + "\n" + line("after"), options, Clock.systemUTC())) {
            var events = new CopyOnWriteArrayList<LiveEvent>(); collector.start("room", "s", events::add);
            await(() -> events.size() == 1); assertEquals(1, collector.diagnostics().count(MALFORMED));
        }
    }

    private static String line(String id) {
        return "{\"kind\":\"FOLLOW\",\"messageId\":\"" + id + "\",\"roomId\":\"room\"}\n";
    }
    private static MockCollector from(String text, CollectorOptions options, Clock clock) {
        return new MockCollector(() -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), options, clock);
    }
    private static CollectorOptions optionsWithQueue(int capacity, Duration ttl) {
        var d = CollectorOptions.defaults();
        return new CollectorOptions(capacity, ttl, d.cacheCapacity(), d.cacheTtl(), d.reconnectBase(),
                d.reconnectMax(), d.heartbeatInterval(), d.idleTimeout(), d.maxFrameBytes());
    }
    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-29T00:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
