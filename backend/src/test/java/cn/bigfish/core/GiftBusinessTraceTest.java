package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.MockConversation;
import cn.bigfish.management.diagnostics.GiftTrace;
import cn.bigfish.speech.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class GiftBusinessTraceTest {
    @TempDir Path directory;
    final List<GiftTrace> rows=new CopyOnWriteArrayList<>();
    List<GiftTrace> phase(String phase) { return rows.stream().filter(t -> t.phase().equals(phase)).toList(); }
    List<GiftTrace> reason(String reason) { return rows.stream().filter(t -> t.reason().equals(reason)).toList(); }
    void deliver(CoreRig rig,String id,int count) throws Exception {
        rig.engine.accept(rig.event(id,LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
        rig.await(() -> rig.peer.tasks.size()==count);
    }
    @Test void mergeAndDuplicateFollowReceiptToTemplateSpeechAndAckWithoutModelOrPrivateText() throws Exception {
        var model=new RuntimeEngineTest.SlowModel();
        try(var rig=new CoreRig(directory,model,null,rows::add)) {
            var gift=rig.event("private-upstream-event",LiveEvent.Type.GIFT);
            assertThat(rig.engine.accept(gift)).isEqualTo(EventIngress.Result.ACCEPTED);
            assertThat(rig.engine.accept(gift)).isEqualTo(EventIngress.Result.DUPLICATE);
            rig.engine.accept(rig.event("private-second-event",LiveEvent.Type.GIFT));
            String primary=phase("RECEIVED").getFirst().taskId();
            assertThat(phase("RECEIVED")).hasSize(3);
            assertThat(reason("DUPLICATE")).hasSize(1);
            assertThat(phase("MERGED_INTO")).singleElement().satisfies(t -> {
                assertThat(t.relatedTaskId()).isEqualTo(primary); assertThat(t.taskId()).isNotEqualTo(primary);
                assertThat(t.quantity()).isEqualTo(2);
            });
            rig.clock.advance(Duration.ofMillis(200)); rig.await(() -> rig.peer.tasks.size()==1);
            var task=rig.peer.tasks.getFirst(); assertThat(task.text()).contains("2个小心心");
            rig.ack(task,PlaybackAck.State.STARTED); rig.ack(task,PlaybackAck.State.COMPLETED);
            assertThat(rows.stream().filter(t -> t.taskId().equals(primary)).map(GiftTrace::phase)).containsSubsequence(
                "RECEIVED","VALIDATED","DEDUP_PASSED","MERGE_CHECKED","TEMPLATE_QUEUED",
                "SPEECH_WAIT","TEMPLATE_RENDERED","SYNTHESIS_QUEUED","SPEECH_STARTED","SPEECH_COMPLETED",
                "PLAYBACK_WAIT","PLAYBACK_SENT","STARTED","COMPLETED");
            assertThat(phase("TEMPLATE_RENDERED").getFirst().quantity()).isEqualTo(2);
            assertThat(phase("COMPLETED").getFirst().taskMs()).isGreaterThanOrEqualTo(200);
            assertThat(model.entered.getCount()).isEqualTo(1);
            assertThat(rows).allMatch(t -> t.sessionId().equals(rig.session));
            assertThat(rows.stream().map(t -> t.json(1)).reduce("",String::concat))
                .doesNotContain("private-","小心心","测试观众","combo","giftId","userId","cookie");
        }
    }
    @Test void invalidRoomAndOldSessionHaveExplicitReasonsWithoutPollutingCurrentDiagnostics() throws Exception {
        try(var rig=new CoreRig(directory,new MockConversation(),null,rows::add)) {
            var e=rig.event("wrong-room",LiveEvent.Type.GIFT);
            assertThat(rig.engine.accept(new LiveEvent(1,e.eventId(),e.sessionId(),"other-room",e.type(),e.userId(),e.username(),e.receivedAt(),e.payload())))
                .isEqualTo(EventIngress.Result.INVALID);
            assertThat(reason("ROOM_MISMATCH")).hasSize(1);
            var bad=new HashMap<>(e.payload()); bad.put("quantity",0);
            assertThat(rig.engine.accept(new LiveEvent(1,"bad",e.sessionId(),e.roomId(),e.type(),e.userId(),e.username(),e.receivedAt(),bad)))
                .isEqualTo(EventIngress.Result.INVALID);
            assertThat(reason("INVALID_EVENT")).hasSize(2);
            rig.engine.stop(rig.session); rig.engine.accept(e);
            assertThat(reason("SESSION_NOT_RUNNING")).hasSize(1);
            var next=rig.engine.startMock();
            rig.engine.accept(e); rig.engine.collectorIngressClosed(rig.session,LiveEvent.Type.GIFT);
            assertThat(reason("SESSION_MISMATCH")).hasSize(1);
            assertThat(rig.engine.diagnostics().trace().history()).isEmpty();
            assertThat(rows).noneMatch(t -> t.sessionId().equals(next));
        }
    }
    @Test void queueRejectExpiryAndLikePolicyAreObservableWithoutQueueGrowth() throws Exception {
        try(var rig=new CoreRig(directory,new MockConversation(),null,rows::add)) {
            var config=rig.configs.current(); var s=config.settings();
            rig.configs.update(new UpdateConfigRequest(config.version(),new RuntimeSettings(s.profileName(),s.persona(),s.roomId(),s.model(),
                s.maxOutputTokens(),s.voice(),s.readComment(),s.welcomeIntervalSeconds(),1,1,new RuntimeSettings.Priorities(1,50,100,5))));
            rig.engine.accept(rig.event("follow",LiveEvent.Type.FOLLOW));
            for(int i=0;i<50;i++) assertThat(rig.engine.accept(rig.event("gift"+i,LiveEvent.Type.GIFT))).isEqualTo(EventIngress.Result.REJECTED_FULL);
            assertThat(rig.engine.status().templates()).isEqualTo(1);
            assertThat(reason("QUEUE_FULL")).hasSize(50);
            assertThat(phase("TEMPLATE_QUEUED")).isEmpty();
            int before=rows.size(); rig.engine.accept(rig.event("like",LiveEvent.Type.LIKE)); assertThat(rows).hasSize(before);
            rig.clock.advance(Duration.ofSeconds(41)); rig.engine.tick();
            rig.engine.accept(rig.event("expires",LiveEvent.Type.GIFT));
            rig.clock.advance(Duration.ofSeconds(121)); rig.engine.tick();
            assertThat(reason("TASK_EXPIRED")).hasSize(1); assertThat(rig.engine.status().templates()).isZero();
        }
    }
    @Test void speechFailureAndThrowingCancelHaveOneTerminalCauseThenNextGiftRecovers() throws Exception {
        var calls=new AtomicInteger(); var tone=new LocalTestSpeech(directory.resolve("audio"));
        SpeechPort speech=new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration timeout) {
                return calls.incrementAndGet()==1 ? CompletableFuture.failedFuture(new SpeechFailure(SpeechFailure.Code.RATE_LIMITED))
                    : tone.synthesize(id,text,voice,timeout);
            }
            public void cancel(String id) { throw new IllegalStateException("private-cleanup-response"); }
        };
        try(var rig=new CoreRig(directory,new MockConversation(),speech,rows::add)) {
            rig.engine.accept(rig.event("failed",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> rig.engine.status().failures()==1); TaskFailureRecoveryTest.idle(rig);
            assertThat(phase("FAILED")).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo("RATE_LIMITED"));
            assertThat(phase("CANCELLED")).isEmpty();
            rig.clock.advance(Duration.ofSeconds(1)); deliver(rig,"recovered",1); TaskFailureRecoveryTest.complete(rig);
            assertThat(phase("COMPLETED")).hasSize(1); assertThat(phase("FAILED")).hasSize(1);
            assertThat(rig.engine.diagnostics().trace().lastPlaybackSuccessAt()).isAfter(phase("FAILED").getFirst().at());
        }
    }
    @ParameterizedTest @EnumSource(value=PlaybackAck.State.class,names={"FAILED","CANCELLED"})
    void playbackFailureAndCancellationAreDistinctAndDuplicateAckCannotDoubleCount(PlaybackAck.State state) throws Exception {
        try(var rig=new CoreRig(directory,new MockConversation(),null,rows::add)) {
            deliver(rig,"playback",1); var task=rig.peer.tasks.getFirst();
            rig.ack(task,state); rig.ack(task,state);
            assertThat(phase(state.name())).singleElement().satisfies(t -> assertThat(t.reason()).isEqualTo("PLAYER_"+state));
            if(state==PlaybackAck.State.CANCELLED) assertThat(rig.engine.diagnostics().trace().lastFailure()).isNull();
            else assertThat(rig.engine.diagnostics().trace().lastFailure().reason().name()).isEqualTo("PLAYER_FAILED");
        }
    }
    @Test void stopCancelsInFlightGiftAndLateSpeechCannotChangeNewSessionOrProduceAudio() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var cancelled=new AtomicInteger();
        SpeechPort speech=new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration timeout) {
                entered.countDown();
                boolean released=false;
                while(!released) try { released=release.await(3,TimeUnit.SECONDS); if(!released) throw new AssertionError("gate timeout"); }
                    catch(InterruptedException ignored) { }
                return CompletableFuture.failedFuture(new SpeechFailure(SpeechFailure.Code.PROVIDER_UNAVAILABLE));
            }
            public void cancel(String id) { cancelled.incrementAndGet(); }
        };
        try(var rig=new CoreRig(directory,new MockConversation(),speech,rows::add)) {
            rig.engine.accept(rig.event("inflight",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200)); rig.engine.tick();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); rig.engine.stop(rig.session);
            assertThat(reason("SESSION_STOPPED")).hasSize(1); assertThat(cancelled).hasValue(1);
            rig.playback.disconnected(rig.peer); String next=rig.engine.startMock(); var peer=new CoreRig.Peer(); rig.connect(peer);
            int recorded=rows.size(); release.countDown(); TaskFailureRecoveryTest.idle(rig);
            assertThat(rows).hasSize(recorded); assertThat(phase("FAILED")).isEmpty();
            assertThat(rig.engine.diagnostics().trace().history()).isEmpty(); assertThat(peer.tasks).isEmpty();
            assertThat(rows).noneMatch(t -> t.sessionId().equals(next));
        } finally { release.countDown(); }
    }
    @Test void disconnectedGiftAndBrokenLogSinkDoNotCreateSessionFailure() throws Exception {
        try(var rig=new CoreRig(directory,new MockConversation(),null,trace -> {
            rows.add(trace); throw new IllegalStateException("private-log-error");
        })) {
            deliver(rig,"disconnect",1); rig.playback.disconnected(rig.peer); rig.engine.tick();
            assertThat(reason("PLAYER_DISCONNECTED")).hasSize(1);
            assertThat(rig.engine.status().state().name()).isEqualTo("RUNNING");
            assertThat(rig.playback.status().ready()).isFalse();
        }
    }
}
