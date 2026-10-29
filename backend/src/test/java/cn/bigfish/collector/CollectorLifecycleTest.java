package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import cn.bigfish.contract.LiveEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static org.junit.jupiter.api.Assertions.*;

class CollectorLifecycleTest {
    @Test void pendingEndNotificationCannotOverwriteExplicitStop() throws Exception {
        try (var collector = new ManualCollector()) {
            collector.start("room", "s", event -> fail()); await(() -> collector.context != null);
            Thread pending;
            synchronized (collector) {
                pending = Thread.ofPlatform().daemon().start(() -> collector.emit("ENDED", "end"));
                // The control callback passed its active check and is waiting for the lifecycle monitor.
                await(() -> collector.diagnostics().count(ENDED) == 1);
                collector.stop();
            }
            pending.join(2000); assertFalse(pending.isAlive());
            assertEquals(CollectorPort.State.STOPPED, collector.state());
        }
    }

    @Test void stopDiscardsPendingEventsAndRefusesRestartWhileAnOldSinkIgnoresInterruption() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<LiveEvent>();
        try (var collector = new ManualCollector()) {
            collector.start("room", "old-session", event -> {
                events.add(event); entered.countDown();
                boolean done = false;
                while (!done) {
                    try { release.await(); done = true; }
                    catch (InterruptedException ignored) { /* Model an incorrectly blocking external callback. */ }
                }
                finished.countDown();
            });
            await(() -> collector.context != null); collector.emit("FOLLOW", "first");
            assertTrue(entered.await(2, TimeUnit.SECONDS)); collector.emit("FOLLOW", "queued");
            collector.stop();
            assertThrows(IllegalStateException.class, () -> collector.start("room", "new-session", events::add));
            collector.emit("FOLLOW", "late");
            release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS));
            // Retry only in this test until the interrupted old worker has returned; production uses a fresh instance per session.
            var restarted = new java.util.concurrent.atomic.AtomicBoolean();
            await(() -> {
                if (restarted.get()) return true;
                try { collector.start("room", "new-session", events::add); restarted.set(true); return true; }
                catch (IllegalStateException exception) { return false; }
            });
            await(() -> collector.context.sessionId.equals("new-session"));
            collector.emit("FOLLOW", "first"); await(() -> events.size() == 2);
            assertEquals("old-session", events.getFirst().sessionId());
            assertEquals("new-session", events.getLast().sessionId());
            assertEquals("first", events.getLast().eventId());
        } finally { release.countDown(); }
    }

    @Test void unexpectedSourceInterruptionFailsInsteadOfLeavingAConnectedZombie() throws Exception {
        try (var collector = new AbstractCollector(CollectorOptions.defaults(), Clock.systemUTC()) {
            @Override public String mode() { return "MOCK"; }
            @Override protected void collect(Run context) throws Exception { throw new InterruptedException(); }
        }) {
            collector.start("room", "s", event -> fail());
            await(() -> collector.state() == CollectorPort.State.FAILED);
            assertEquals(1, collector.diagnostics().count(CONNECT_FAILURE));
        }
    }

    private static final class ManualCollector extends AbstractCollector {
        volatile Run context;
        ManualCollector() { super(CollectorOptions.defaults(), Clock.systemUTC()); }
        @Override public String mode() { return "MOCK"; }
        @Override protected void collect(Run context) { context.transition(State.CONNECTED); this.context = context; }
        void emit(String kind, String id) {
            context.accept(new IncomingMessage(kind, id, "room", "user", "", 0, "", "", "", 0, "", null, 0), Instant.now());
        }
    }
}
