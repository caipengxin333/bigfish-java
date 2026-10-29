package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuntimeEngineTest {
    @TempDir Path directory;
    @Test void speechDeadlineCancelsSynthesisAndPreventsLatePlayback() throws Exception {
        var entered = new CountDownLatch(1);
        var pending = new CompletableFuture<SpeechPort.AudioAsset>();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        SpeechPort blocked = new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id, String text, String voice, Duration timeout) {
                entered.countDown(); return pending;
            }
            public void cancel(String id) { cancelled.set(true); pending.cancel(true); }
        };
        try (var rig = new CoreRig(directory,new MockConversation(),blocked)) {
            rig.engine.accept(rig.event("g",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200)); rig.engine.tick();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            rig.clock.advance(Duration.ofSeconds(6)); rig.engine.tick();
            assertThat(cancelled).isTrue(); assertThat(rig.engine.status().speechActive()).isFalse();
            assertThat(rig.peer.tasks).isEmpty();
        }
    }
    static final class SlowModel implements ConversationPort {
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<Void> result = new CompletableFuture<>();
        volatile Consumer<String> sink;
        volatile ConfigSnapshot captured;
        volatile String cancelled;
        public CompletionStage<Void> reply(String id, LiveEvent e, ConfigSnapshot c, Duration d, Consumer<String> sink) {
            this.sink = sink; captured = c; entered.countDown(); return result;
        }
        public void cancel(String id) { cancelled = id; result.cancel(true); }
    }
    @Test void slowModelDoesNotBlockGiftAndSameComboIncrementsMerge() throws Exception {
        var slow = new SlowModel();
        try (var rig = new CoreRig(directory,slow)) {
            assertThat(rig.engine.accept(rig.event("c",LiveEvent.Type.COMMENT))).isEqualTo(EventIngress.Result.ACCEPTED);
            rig.engine.tick(); assertThat(slow.entered.await(1,TimeUnit.SECONDS)).isTrue();
            var gift = rig.event("g",LiveEvent.Type.GIFT);
            assertThat(rig.engine.accept(gift)).isEqualTo(EventIngress.Result.ACCEPTED);
            assertThat(rig.engine.accept(gift)).isEqualTo(EventIngress.Result.DUPLICATE);
            rig.engine.accept(rig.event("g2",LiveEvent.Type.GIFT));
            rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> !rig.peer.tasks.isEmpty());
            assertThat(rig.peer.tasks.getFirst().type()).isEqualTo("GIFT");
            assertThat(rig.peer.tasks.getFirst().text()).contains("2个小心心");
            assertThat(slow.result).isNotDone();
            rig.engine.stop(rig.session);
            assertThat(slow.cancelled).isNotNull();
        }
    }
    @Test void acceptedTasksKeepSnapshotWhileNewTasksApplySavedVersion() throws Exception {
        var slow = new SlowModel();
        try (var rig = new CoreRig(directory,slow)) {
            rig.engine.accept(rig.event("c",LiveEvent.Type.COMMENT)); rig.engine.tick();
            assertThat(slow.entered.await(1,TimeUnit.SECONDS)).isTrue();
            var original = rig.configs.current();
            var saved = rig.configs.update(new UpdateConfigRequest(original.version(),original.settings()));
            assertThat(rig.engine.status().savedConfigVersion()).isEqualTo(saved.version());
            assertThat(rig.engine.status().appliedConfigVersion()).isEqualTo(original.version());
            slow.sink.accept("旧快照回复"); slow.result.complete(null);
            rig.await(() -> !rig.peer.tasks.isEmpty());
            assertThat(rig.peer.tasks.getFirst().configVersion()).isEqualTo(original.version());
            rig.engine.accept(rig.event("g",LiveEvent.Type.GIFT));
            assertThat(rig.engine.status().appliedConfigVersion()).isEqualTo(saved.version());
        }
    }
    @Test void floodBoundExpiryWelcomeThrottleAndOldSessionIsolation() throws Exception {
        try (var rig = new CoreRig(directory,new MockConversation())) {
            for (int i=0;i<500;i++) rig.engine.accept(rig.event("c"+i,LiveEvent.Type.COMMENT));
            assertThat(rig.engine.status().comments()).isEqualTo(8);
            rig.engine.accept(rig.event("welcome",LiveEvent.Type.ENTRANCE));
            assertThat(rig.engine.status().templates()).isZero();
            rig.clock.advance(Duration.ofSeconds(61)); rig.engine.tick();
            assertThat(rig.engine.status().comments()).isZero();
            assertThat(rig.peer.tasks).isEmpty();
            rig.engine.stop(rig.session);
            var next = rig.engine.startMock();
            rig.engine.stop(rig.session); // stale stop cannot stop the new session
            assertThat(rig.engine.status().sessionId()).isEqualTo(next);
            assertThat(rig.engine.accept(rig.event("old",LiveEvent.Type.GIFT))).isEqualTo(EventIngress.Result.REJECTED_SESSION);
        }
    }
    @Test void welcomeAtMostOncePerIntervalEvenWhenIdle() throws Exception {
        try (var rig = new CoreRig(directory,new MockConversation())) {
            rig.engine.accept(rig.event("w1",LiveEvent.Type.ENTRANCE));
            rig.await(() -> rig.peer.tasks.size() == 1);
            rig.ack(rig.peer.tasks.getFirst(),PlaybackAck.State.STARTED);
            rig.ack(rig.peer.tasks.getFirst(),PlaybackAck.State.COMPLETED);
            rig.engine.accept(rig.event("w2",LiveEvent.Type.FOLLOW)); rig.engine.tick();
            assertThat(rig.engine.status().templates()).isZero();
            assertThat(rig.peer.tasks).hasSize(1);
        }
    }
    @Test void disconnectCancelsWorkAndLateReplyCannotReachNewPlayer() throws Exception {
        var slow = new SlowModel();
        try (var rig = new CoreRig(directory,slow)) {
            rig.engine.accept(rig.event("c",LiveEvent.Type.COMMENT)); rig.engine.tick();
            assertThat(slow.entered.await(1,TimeUnit.SECONDS)).isTrue();
            rig.playback.disconnected(rig.peer); rig.engine.tick();
            assertThat(slow.cancelled).isNotNull();
            var second = new CoreRig.Peer(); rig.connect(second);
            slow.sink.accept("迟到回复");
            rig.engine.tick();
            assertThat(second.tasks).isEmpty();
            assertThat(rig.engine.status().synthesis()).isZero();
        }
    }
    @Test void engineEnforcesOverallDeadlineEvenForNoncompliantPort() throws Exception {
        var slow = new SlowModel();
        try (var rig = new CoreRig(directory,slow)) {
            rig.engine.accept(rig.event("c",LiveEvent.Type.COMMENT)); rig.engine.tick();
            assertThat(slow.entered.await(1,TimeUnit.SECONDS)).isTrue();
            rig.clock.advance(Duration.ofSeconds(21)); rig.engine.tick();
            assertThat(slow.cancelled).isNotNull();
            assertThat(rig.engine.status().modelActive()).isFalse();
        }
    }
}
