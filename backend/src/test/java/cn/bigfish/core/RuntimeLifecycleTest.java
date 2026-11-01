package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.management.RuntimeEngine;
import cn.bigfish.speech.LocalTestSpeech;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RuntimeLifecycleTest {
    @TempDir Path directory;
    static final class Model implements ConversationPort {
        final CountDownLatch entered=new CountDownLatch(1);
        final CompletableFuture<Void> pending=new CompletableFuture<>();
        final AtomicInteger cancels=new AtomicInteger();
        volatile Consumer<String> sink;
        boolean throwOnCancel;
        public CompletionStage<Void> reply(String id,LiveEvent event,ConfigSnapshot config,Duration deadline,Consumer<String> sink) {
            this.sink=sink; entered.countDown(); return pending;
        }
        public void cancel(String id) { cancels.incrementAndGet(); if(throwOnCancel) throw new IllegalStateException("private-model-detail"); }
    }
    static final class Speech implements SpeechPort {
        final CountDownLatch entered=new CountDownLatch(1);
        final CompletableFuture<AudioAsset> pending=new CompletableFuture<>();
        final AtomicInteger cancels=new AtomicInteger();
        boolean throwOnCancel;
        public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration deadline) { entered.countDown(); return pending; }
        public void cancel(String id) { cancels.incrementAndGet(); if(throwOnCancel) throw new IllegalStateException("private-speech-detail"); }
    }
    @Test void cancellationExceptionsCannotAbortStopOrLeaveQueuedAudioAndWorkers() throws Exception {
        var model=new Model(); model.throwOnCancel=true; var speech=new Speech(); speech.throwOnCancel=true;
        try(var rig=new CoreRig(directory,model,speech)) {
            rig.engine.accept(rig.event("comment",LiveEvent.Type.COMMENT));
            rig.engine.accept(rig.event("gift",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200)); rig.engine.tick();
            assertThat(model.entered.await(1,TimeUnit.SECONDS)).isTrue(); assertThat(speech.entered.await(1,TimeUnit.SECONDS)).isTrue();
            rig.engine.accept(rig.event("queued",LiveEvent.Type.COMMENT));
            assertThatCode(() -> rig.engine.stop(rig.session)).doesNotThrowAnyException();
            assertThat(rig.engine.status().state().name()).isEqualTo("STOPPED");
            assertThat(rig.engine.status().comments()).isZero(); assertThat(rig.engine.status().templates()).isZero();
            assertThat(rig.engine.status().synthesis()).isZero(); assertThat(rig.assets.size()).isZero();
            assertThat(rig.playback.status().connected()).isFalse(); assertThat(rig.playback.status().ready()).isFalse();
            assertThat(model.cancels.get()).isPositive(); assertThat(speech.cancels.get()).isPositive();
            rig.await(() -> rig.engine.diagnostics().modelWorker().active()==0 && rig.engine.diagnostics().speechWorker().active()==0);
            assertThat(rig.engine.status().lastError()).doesNotContain("private-");
            assertThat(rig.engine.status().failures()).isEqualTo(2); // One failure per job, including async cancellation races.
            model.sink.accept("late"); model.pending.complete(null);
            speech.pending.complete(rig.speech.synthesize("late","fixture","voice",Duration.ofSeconds(1)).toCompletableFuture().get());
            rig.engine.tick(); assertThat(rig.peer.tasks).isEmpty();
        }
    }
    @Test void notReadyNeverInvokesProvidersAndWaitingQueuesStillExpire() throws Exception {
        var model=new Model(); var speech=new Speech();
        try(var rig=new CoreRig(directory,model,speech)) {
            rig.playback.disconnected(rig.peer);
            for(int i=0;i<100;i++) {
                rig.engine.accept(rig.event("c"+i,LiveEvent.Type.COMMENT));
                rig.engine.accept(rig.event("g"+i,LiveEvent.Type.GIFT)); rig.engine.tick();
            }
            assertThat(rig.engine.status().comments()).isEqualTo(8);
            assertThat(rig.engine.status().templates()).isBetween(1,8);
            assertThat(model.entered.getCount()).isEqualTo(1); assertThat(speech.entered.getCount()).isEqualTo(1);
            rig.clock.advance(Duration.ofSeconds(121)); rig.engine.tick();
            assertThat(rig.engine.status().comments()).isZero(); assertThat(rig.engine.status().templates()).isZero();
            var peer=new CoreRig.Peer(); rig.playback.hello(peer,peer.id()); // HELLO alone is not READY.
            rig.engine.accept(rig.event("fresh",LiveEvent.Type.COMMENT)); rig.engine.tick();
            assertThat(model.entered.getCount()).isEqualTo(1); assertThat(speech.entered.getCount()).isEqualTo(1);
            rig.playback.ready(peer,rig.session,peer.id(),rig.playback.status().generation()); rig.engine.tick();
            assertThat(model.entered.await(1,TimeUnit.SECONDS)).isTrue(); model.sink.accept("fixture"); model.pending.complete(null);
            rig.await(() -> speech.entered.getCount()==0);
        }
    }
    @Test void allocatedModelIsClosedWhenLaterStartupStepFails() throws Exception {
        var closes=new AtomicInteger();
        class AllocatedModel extends ModelPort implements AutoCloseable {
            public void close() { closes.incrementAndGet(); }
        }
        try(var rig=new CoreRig(directory,new Model())) {
            rig.engine.stop(rig.session);
            var engine=new RuntimeEngine(rig.configs,new Model(),AllocatedModel::new,rig.speech,rig.playback,rig.assets,
                rig.clock,Duration.ofSeconds(20),Duration.ofSeconds(5));
            try {
                assertThatThrownBy(() -> engine.startMock(true,null,"LOCAL_TEST_TONE")).hasMessage("PROVIDER_UNAVAILABLE");
                assertThat(closes).hasValue(1); assertThat(engine.status().state()).isEqualTo(RuntimeEngine.State.FAILED);
                assertThat(engine.status().playback().connected()).isFalse(); assertThat(rig.assets.size()).isZero();
                engine.stop(engine.status().sessionId()); engine.stop(engine.status().sessionId());
                assertThat(closes).hasValue(1);
                assertThat(engine.startMock()).isNotBlank();
            } finally { engine.close(); }
        }
    }
    static class ModelPort implements ConversationPort {
        public CompletionStage<Void> reply(String id,LiveEvent event,ConfigSnapshot config,Duration deadline,Consumer<String> sink) {
            sink.accept("fresh"); return CompletableFuture.completedFuture(null);
        }
        public void cancel(String id) {}
    }
    @Test void oldModelAndSynthesisCompletionCannotPublishIntoRestartedSession() throws Exception {
        var firstModel=new Model(); var modelCalls=new AtomicInteger();
        ConversationPort model=new ModelPort() {
            @Override public CompletionStage<Void> reply(String id,LiveEvent e,ConfigSnapshot c,Duration d,Consumer<String> sink) {
                return modelCalls.incrementAndGet()==1 ? firstModel.reply(id,e,c,d,sink) : super.reply(id,e,c,d,sink);
            }
        };
        var releaseEntered=new CountDownLatch(1); var allowRelease=new CountDownLatch(1); var releases=new AtomicInteger();
        var tone=new LocalTestSpeech(directory.resolve("audio"));
        SpeechPort speech=new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration deadline) { return tone.synthesize(id,text,voice,deadline); }
            public void cancel(String id) {}
            public boolean release(AudioAsset asset) {
                if(releases.incrementAndGet()==1) {
                    releaseEntered.countDown(); boolean done=false;
                    while(!done) { try { done=allowRelease.await(3,TimeUnit.SECONDS); if(!done) throw new AssertionError("release gate timeout"); }
                        catch(InterruptedException ignored) { /* Deliberately finish cleanup after cancellation. */ } }
                }
                return false; // LocalTestSpeech uses a reusable tone file.
            }
        };
        try(var rig=new CoreRig(directory,model,speech)) {
            rig.engine.accept(rig.event("old-model",LiveEvent.Type.COMMENT));
            rig.engine.accept(rig.event("old-speech",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200)); rig.engine.tick();
            assertThat(firstModel.entered.await(1,TimeUnit.SECONDS)).isTrue(); assertThat(releaseEntered.await(1,TimeUnit.SECONDS)).isTrue();
            rig.engine.stop(rig.session); rig.playback.disconnected(rig.peer);
            String next=rig.engine.startMock(false,speech,"LOCAL_TEST_TONE"); var peer=new CoreRig.Peer(); rig.connect(peer);
            firstModel.sink.accept("old late text"); firstModel.pending.complete(null); allowRelease.countDown();
            rig.await(() -> rig.engine.diagnostics().modelWorker().active()==0 && rig.engine.diagnostics().speechWorker().active()==0);
            assertThat(peer.tasks).isEmpty(); assertThat(rig.assets.size()).isZero();
            assertThat(rig.engine.diagnostics().trace().history()).isEmpty();
            var e=rig.event("new",LiveEvent.Type.COMMENT);
            rig.engine.accept(new LiveEvent(1,e.eventId(),next,e.roomId(),e.type(),e.userId(),e.username(),e.receivedAt(),e.payload()));
            rig.await(() -> peer.tasks.size()==1);
            assertThat(peer.tasks.getFirst().text()).isEqualTo("fresh"); assertThat(peer.tasks.getFirst().sessionId()).isEqualTo(next);
            assertThat(releases).hasValue(2);
        } finally { allowRelease.countDown(); }
    }
}
