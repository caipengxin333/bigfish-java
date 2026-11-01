package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.*;
import cn.bigfish.management.RuntimeEngine.WaitReason;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.*;
import cn.bigfish.speech.LocalTestSpeech;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuntimeStabilityTest {
    @TempDir Path directory;
    enum Mode { OK, NEVER, RATE_LIMIT, SERVER_ERROR, EMPTY }
    static final class Model implements ConversationPort {
        volatile Mode mode=Mode.OK;
        volatile Consumer<String> oldSink;
        volatile CompletableFuture<Void> pending;
        final AtomicInteger calls=new AtomicInteger(), cancels=new AtomicInteger();
        public CompletionStage<Void> reply(String id,LiveEvent event,ConfigSnapshot config,Duration deadline,Consumer<String> sink) {
            calls.incrementAndGet();
            return switch(mode) {
                case NEVER -> { oldSink=sink; pending=new CompletableFuture<>(); yield pending; }
                case RATE_LIMIT -> CompletableFuture.failedFuture(new DeepSeekFailure(DeepSeekFailure.Code.RATE_LIMITED));
                case SERVER_ERROR -> CompletableFuture.failedFuture(new DeepSeekFailure(DeepSeekFailure.Code.SERVER_ERROR));
                case EMPTY -> CompletableFuture.completedFuture(null);
                case OK -> { sink.accept("short fixture reply"); yield CompletableFuture.completedFuture(null); }
            };
        }
        public void cancel(String id) { cancels.incrementAndGet(); if(pending!=null) pending.cancel(false); }
    }
    static final class Speech implements SpeechPort {
        final LocalTestSpeech delegate;
        volatile boolean hang;
        volatile CompletableFuture<AudioAsset> pending;
        final AtomicInteger calls=new AtomicInteger(), cancels=new AtomicInteger();
        Speech(Path root) throws Exception { delegate=new LocalTestSpeech(root); }
        public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration deadline) {
            calls.incrementAndGet();
            if(hang) { pending=new CompletableFuture<>(); return pending; }
            return delegate.synthesize(id,text,voice,deadline);
        }
        public void cancel(String id) { cancels.incrementAndGet(); if(pending!=null) pending.cancel(false); }
    }
    private static LiveEvent event(CoreRig rig,String id,LiveEvent.Type type) {
        var e=rig.event(id,type);
        return new LiveEvent(1,e.eventId(),rig.engine.status().sessionId(),e.roomId(),e.type(),e.userId(),e.username(),e.receivedAt(),e.payload());
    }
    private static void ack(CoreRig rig,CoreRig.Peer peer,PlaybackTask task,PlaybackAck.State state) {
        rig.playback.acknowledge(peer,new PlaybackAck(task.taskId(),rig.engine.status().sessionId(),peer.id(),rig.playback.status().generation(),state,rig.clock.instant(),null));
    }
    private static void finish(CoreRig rig,CoreRig.Peer peer) {
        var task=peer.tasks.getLast(); ack(rig,peer,task,PlaybackAck.State.STARTED); ack(rig,peer,task,PlaybackAck.State.COMPLETED);
    }
    private static void advance(CoreRig rig,CoreRig.Peer peer,int seconds) {
        while(seconds>0) {
            int step=Math.min(seconds,5); rig.clock.advance(Duration.ofSeconds(step)); seconds-=step;
            rig.playback.heartbeat(peer,rig.engine.status().sessionId(),peer.id(),rig.playback.status().generation()); rig.engine.tick();
        }
    }
    private static void idleWorkers(CoreRig rig) throws Exception {
        rig.await(() -> rig.engine.diagnostics().modelWorker().active()==0 && rig.engine.diagnostics().speechWorker().active()==0);
    }
    private static void bounded(CoreRig rig) {
        var d=rig.engine.diagnostics(); var s=d.runtime();
        assertThat(s.comments()).isBetween(0,8); assertThat(s.templates()).isBetween(0,8);
        assertThat(s.synthesis()).isBetween(0,8); assertThat(s.playback().queued()).isBetween(0,8);
        assertThat(d.audioAssets()).isLessThanOrEqualTo(9); assertThat(d.dedupEntries()).isLessThanOrEqualTo(4096);
        assertThat(d.modelWorker().threads()).isLessThanOrEqualTo(1); assertThat(d.speechWorker().threads()).isLessThanOrEqualTo(1);
        assertThat(d.modelWorker().queued()+d.speechWorker().queued()).isZero();
        assertThat(d.trace().tasks().size()).isLessThanOrEqualTo(RuntimeDiagnostics.MAX_TASKS);
        assertThat(d.trace().history().size()).isLessThanOrEqualTo(RuntimeDiagnostics.MAX_HISTORY);
    }
    @Test void thirtyVirtualMinutesRecoverAfterRepeatedFailuresWithoutAccumulation() throws Exception {
        var model=new Model(); var speech=new Speech(directory.resolve("audio"));
        try(var rig=new CoreRig(directory,model,speech)) {
            Instant begin=rig.clock.instant();
            rig.playback.disconnected(rig.peer);
            for(int i=0;i<2000;i++) { rig.engine.accept(event(rig,"burst-"+i,LiveEvent.Type.COMMENT)); if(i%100==0) bounded(rig); }
            assertThat(rig.engine.status().comments()).isEqualTo(8);
            rig.clock.advance(Duration.ofSeconds(61)); rig.engine.tick();
            assertThat(rig.engine.status().comments()).isZero();
            var peer=new CoreRig.Peer(); rig.connect(peer);
            for(int cycle=0;cycle<30;cycle++) {
                final var player=peer;
                long failures=rig.engine.status().failures();
                model.mode=Mode.NEVER; int modelCalls=model.calls.get();
                rig.engine.accept(event(rig,"hang-model-"+cycle,LiveEvent.Type.COMMENT)); rig.engine.tick();
                rig.await(() -> model.calls.get()>modelCalls && model.pending!=null && !model.pending.isDone());
                int sent=player.tasks.size();
                rig.engine.accept(event(rig,"gift-a-"+cycle,LiveEvent.Type.GIFT));
                rig.engine.accept(event(rig,"gift-b-"+cycle,LiveEvent.Type.GIFT));
                rig.engine.accept(event(rig,"follow-"+cycle,LiveEvent.Type.FOLLOW));
                rig.engine.accept(event(rig,"enter-"+cycle,LiveEvent.Type.ENTRANCE));
                rig.clock.advance(Duration.ofMillis(200)); rig.await(() -> player.tasks.size()>sent);
                assertThat(player.tasks.getLast().type()).isEqualTo("GIFT");
                assertThat(player.tasks.getLast().text()).contains("2个"); assertThat(model.pending).isNotDone(); finish(rig,player);
                advance(rig,player,21); idleWorkers(rig);
                assertThat(rig.engine.status().failures()).as("one model timeout per task, cycle %s",cycle).isEqualTo(failures+1);
                assertThat(rig.engine.status().lastError()).isEqualTo("MODEL_OVERALL_TIMEOUT");
                model.mode=cycle%2==0 ? Mode.RATE_LIMIT : Mode.SERVER_ERROR;
                rig.engine.accept(event(rig,"provider-fault-"+cycle,LiveEvent.Type.COMMENT));
                rig.await(() -> rig.engine.status().failures()==failures+2); idleWorkers(rig);
                assertThat(rig.engine.diagnostics().trace().lastFailure().reason()).isEqualTo(cycle%2==0 ? Reason.RATE_LIMITED : Reason.SERVER_ERROR);
                model.mode=Mode.OK; int before=player.tasks.size();
                rig.engine.accept(event(rig,"model-recovered-"+cycle,LiveEvent.Type.COMMENT)); rig.await(() -> player.tasks.size()>before); finish(rig,player);
                idleWorkers(rig); speech.hang=true; int speechCalls=speech.calls.get();
                rig.engine.accept(event(rig,"hang-speech-"+cycle,LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
                rig.await(() -> speech.calls.get()>speechCalls && speech.pending!=null && !speech.pending.isDone());
                advance(rig,player,6); idleWorkers(rig);
                assertThat(rig.engine.status().failures()).as("one speech timeout per task").isEqualTo(failures+3);
                assertThat(rig.engine.status().lastError()).isEqualTo("SPEECH_OVERALL_TIMEOUT");
                speech.hang=false; int count=player.tasks.size();
                rig.engine.accept(event(rig,"speech-recovered-"+cycle,LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
                rig.await(() -> player.tasks.size()>count); finish(rig,player); idleWorkers(rig);
                // Invalidate queued work and the actual lease, then prove a new player receives new work.
                rig.engine.accept(event(rig,"disconnect-"+cycle,LiveEvent.Type.COMMENT));
                rig.playback.disconnected(player); rig.engine.tick();
                assertThat(rig.engine.status().comments()).isZero();
                if(cycle%5==4) { rig.engine.stop(rig.engine.status().sessionId()); rig.engine.startMock(); }
                peer=new CoreRig.Peer(); rig.connect(peer);
                advance(rig,peer,33); bounded(rig);
            }
            assertThat(Duration.between(begin,rig.clock.instant())).isGreaterThan(Duration.ofMinutes(30));
            assertThat(model.cancels.get()).isGreaterThanOrEqualTo(30); assertThat(speech.cancels.get()).isGreaterThanOrEqualTo(30);
            rig.engine.accept(event(rig,"final",LiveEvent.Type.COMMENT)); final var last=peer;
            rig.await(() -> !last.tasks.isEmpty()); finish(rig,last); idleWorkers(rig);
            assertThat(rig.engine.diagnostics().trace().lastPlaybackSuccessAt()).isEqualTo(rig.clock.instant());
            rig.engine.stop(rig.engine.status().sessionId()); rig.engine.tick();
            assertThat(rig.engine.status().playback().playing()).isFalse(); assertThat(rig.assets.size()).isZero(); bounded(rig);
            rig.engine.close(); rig.await(() -> rig.engine.diagnostics().modelWorker().threads()+rig.engine.diagnostics().speechWorker().threads()==0);
        }
    }
    @Test void blockedPortDoesNotConsumeNewTasksOrCreateMoreWorkersAndLateResultIsFenced() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var count=new AtomicInteger();
        ConversationPort port=new ConversationPort() {
            public CompletionStage<Void> reply(String id,LiveEvent e,ConfigSnapshot c,Duration d,Consumer<String> sink) {
                if(count.incrementAndGet()==1) {
                    entered.countDown(); boolean done=false;
                    while(!done) { try { release.await(); done=true; } catch(InterruptedException ignored) {} }
                    sink.accept("late-old-session");
                } else sink.accept("next-ok");
                return CompletableFuture.completedFuture(null);
            }
            public void cancel(String id) {}
        };
        try(var rig=new CoreRig(directory,port)) {
            rig.engine.accept(rig.event("blocked",LiveEvent.Type.COMMENT)); rig.engine.tick(); assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            advance(rig,rig.peer,21);
            assertThat(rig.engine.status().failures()).isEqualTo(1);
            assertThat(rig.engine.diagnostics().waiting()).contains(WaitReason.WORKER_BUSY);
            rig.engine.stop(rig.session); rig.playback.disconnected(rig.peer); String session=rig.engine.startMock();
            var next=new CoreRig.Peer(); rig.connect(next);
            rig.engine.accept(event(rig,"pending",LiveEvent.Type.COMMENT));
            for(int i=0;i<100;i++) rig.engine.tick();
            assertThat(rig.engine.status().comments()).isEqualTo(1); assertThat(count.get()).isEqualTo(1); bounded(rig);
            release.countDown(); rig.await(() -> !next.tasks.isEmpty()); finish(rig,next); idleWorkers(rig);
            assertThat(next.tasks).extracting(PlaybackTask::text).containsExactly("next-ok");
            assertThat(rig.engine.diagnostics().trace().history()).allMatch(t -> session.equals(t.sessionId()));
            assertThat(rig.engine.diagnostics().trace().lastFailure()).isNull();
            assertThat(rig.engine.status().failures()).isEqualTo(1);
        } finally { release.countDown(); }
    }
    @Test void emptyReplyAndAudioCleanupFailureAreVisibleOnceAndNextTaskRecovers() throws Exception {
        var model=new Model(); model.mode=Mode.EMPTY;
        var tone=new LocalTestSpeech(directory.resolve("audio")); var releases=new AtomicInteger();
        SpeechPort speech=new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration deadline) { return tone.synthesize(id,text,voice,deadline); }
            public void cancel(String id) {}
            public boolean release(AudioAsset asset) { if(releases.incrementAndGet()==1) throw new IllegalStateException("unsafe body"); return false; }
        };
        try(var rig=new CoreRig(directory,model,speech)) {
            rig.engine.accept(rig.event("empty",LiveEvent.Type.COMMENT)); rig.await(() -> rig.engine.status().failures()==1); idleWorkers(rig);
            assertThat(rig.engine.diagnostics().trace().lastFailure().reason()).isEqualTo(Reason.MODEL_EMPTY_OUTPUT);
            model.mode=Mode.OK;
            rig.engine.accept(rig.event("cleanup",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> rig.engine.status().failures()==2); idleWorkers(rig);
            assertThat(releases.get()).isEqualTo(1); assertThat(rig.peer.tasks).isEmpty();
            assertThat(rig.engine.diagnostics().toString()).doesNotContain("unsafe body");
            rig.engine.accept(rig.event("recovered",LiveEvent.Type.COMMENT)); rig.await(() -> !rig.peer.tasks.isEmpty()); finish(rig,rig.peer); idleWorkers(rig);
            assertThat(rig.engine.status().failures()).isEqualTo(2); assertThat(releases.get()).isEqualTo(2);
        }
    }
}
