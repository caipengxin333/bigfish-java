package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.MockConversation;
import cn.bigfish.management.RuntimeEngine.WaitReason;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuntimeDiagnosticsTest {
    @TempDir Path directory;
    @Test void historyTaskTableRetentionAndSessionFenceAreBounded() {
        var clock=new CoreRig.MutableClock(); var d=new RuntimeDiagnostics(clock); d.begin("s");
        for(int i=0;i<10_000;i++) {
            d.received("s","task-"+i,LiveEvent.Type.COMMENT,1);
            d.mark("s","task-"+i,Stage.FAILED,Reason.RATE_LIMITED);
        }
        var snapshot=d.snapshot();
        assertThat(snapshot.tasks()).hasSize(RuntimeDiagnostics.MAX_TASKS);
        assertThat(snapshot.history()).hasSize(RuntimeDiagnostics.MAX_HISTORY);
        assertThat(snapshot.taskEvictions()).isPositive(); assertThat(snapshot.historyEvictions()).isPositive();
        assertThat(snapshot.transitions().get(Stage.FAILED)).isEqualTo(10_000);
        assertThatThrownBy(() -> snapshot.history().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.reasons().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(d.mark("s","task-9999",Stage.COMPLETED,Reason.NONE)).isFalse();
        clock.advance(RuntimeDiagnostics.RETENTION); var expired=d.snapshot();
        assertThat(expired.tasks()).isEmpty(); assertThat(expired.history()).isEmpty(); assertThat(expired.lastFailure()).isNull();
        d.begin("new");
        d.received("s","late",LiveEvent.Type.GIFT,1); d.mark("s","task-9999",Stage.COMPLETED,Reason.NONE);
        assertThat(d.snapshot().lastPlaybackSuccessAt()).isNull(); assertThat(d.snapshot().history()).isEmpty();
    }
    @Test void stageDurationsAreServerTimedAndNoUserOrAckTextIsRetained() throws Exception {
        try(var rig=new CoreRig(directory,new MockConversation())) {
            assertThat(rig.engine.diagnostics().waiting()).contains(WaitReason.NO_MESSAGES);
            var event=new LiveEvent(1,"external-event-secret",rig.session,"mock-room",LiveEvent.Type.COMMENT,
                "user-secret","name-secret",Instant.MIN,Map.of("content","comment-secret"));
            rig.engine.accept(event); rig.await(() -> !rig.peer.tasks.isEmpty());
            var task=rig.peer.tasks.getFirst();
            assertThat(rig.engine.diagnostics().waiting()).contains(WaitReason.PLAYBACK_WAIT).doesNotContain(WaitReason.PLAYING);
            rig.clock.advance(Duration.ofMillis(250)); rig.ack(task,PlaybackAck.State.STARTED);
            assertThat(rig.engine.diagnostics().waiting()).contains(WaitReason.PLAYING);
            rig.clock.advance(Duration.ofMillis(300)); rig.ack(task,PlaybackAck.State.COMPLETED);
            var trace=rig.engine.diagnostics().trace();
            assertThat(trace.history()).extracting(Trace::stage).containsSubsequence(Stage.RECEIVED,Stage.MODEL_WAIT,
                Stage.MODEL_STARTED,Stage.MODEL_COMPLETED,Stage.SPEECH_WAIT,Stage.SPEECH_STARTED,Stage.SPEECH_COMPLETED,
                Stage.PLAYBACK_WAIT,Stage.PLAYBACK_SENT,Stage.STARTED,Stage.COMPLETED);
            assertThat(trace.history()).allMatch(t -> t.sessionId().equals(rig.session) && t.taskId().equals(task.taskId()));
            assertThat(trace.history().getLast().previousStageMs()).isEqualTo(300);
            assertThat(trace.lastPlaybackSuccessAt()).isEqualTo(rig.clock.instant());
            assertThat(trace.toString()).doesNotContain("secret",task.text());
            rig.engine.accept(rig.event("next",LiveEvent.Type.COMMENT)); rig.await(() -> rig.peer.tasks.size()==2);
            var second=rig.peer.tasks.getLast();
            rig.playback.acknowledge(rig.peer,new PlaybackAck(second.taskId(),rig.session,rig.peer.id(),rig.playback.status().generation(),
                PlaybackAck.State.FAILED,Instant.MAX,"unsafe-http-body-secret"));
            var failed=rig.engine.diagnostics().trace();
            assertThat(failed.lastFailure().reason()).isEqualTo(Reason.PLAYER_FAILED);
            assertThat(failed.lastFailure().at()).isEqualTo(rig.clock.instant());
            assertThat(failed.toString()).doesNotContain("unsafe-http");
            assertThat(failed.lastPlaybackSuccessAt()).isEqualTo(trace.lastPlaybackSuccessAt());
        }
    }
    @Test void queueExpiryAndNoReadyPlayerHaveDistinctDiagnostics() throws Exception {
        try(var rig=new CoreRig(directory,new MockConversation())) {
            rig.playback.disconnected(rig.peer); // ingest after epoch change, so this task waits for a new player
            rig.engine.accept(rig.event("pending",LiveEvent.Type.COMMENT)); rig.engine.tick();
            assertThat(rig.engine.diagnostics().waiting()).contains(WaitReason.PLAYER_NOT_READY,WaitReason.MODEL_WAIT);
            rig.clock.advance(Duration.ofSeconds(61)); rig.engine.tick();
            assertThat(rig.engine.diagnostics().trace().history().getLast().reason()).isEqualTo(Reason.TASK_EXPIRED);
            assertThat(rig.engine.status().comments()).isZero();
            var next=new CoreRig.Peer(); rig.connect(next);
            rig.engine.accept(rig.event("works",LiveEvent.Type.COMMENT)); rig.await(() -> !next.tasks.isEmpty());
            assertThat(rig.engine.status().failures()).isZero();
        }
    }
}
