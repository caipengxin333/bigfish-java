package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.*;
import cn.bigfish.management.RuntimeEngine;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.*;
import cn.bigfish.speech.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TaskFailureRecoveryTest {
    @TempDir Path directory;
    static void idle(CoreRig rig) throws Exception {
        rig.await(() -> !rig.engine.status().modelActive() && !rig.engine.status().speechActive()
            && rig.engine.diagnostics().modelWorker().active()==0 && rig.engine.diagnostics().speechWorker().active()==0);
    }
    static void advance(CoreRig rig,int seconds) {
        while(seconds>0) {
            int step=Math.min(5,seconds); seconds-=step; rig.clock.advance(Duration.ofSeconds(step));
            rig.playback.heartbeat(rig.peer,rig.session,rig.peer.id(),rig.playback.status().generation()); rig.engine.tick();
        }
    }
    static void complete(CoreRig rig) {
        var task=rig.peer.tasks.getLast(); rig.ack(task,PlaybackAck.State.STARTED); rig.ack(task,PlaybackAck.State.COMPLETED);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void modelFailureOrTimeoutKeepsHistoryWhileNextTaskRecordsSuccess(boolean timeout) throws Exception {
        var calls=new AtomicInteger(); var entered=new CountDownLatch(1);
        ConversationPort model=new ConversationPort() {
            public CompletionStage<Void> reply(String id,LiveEvent e,ConfigSnapshot c,Duration d,Consumer<String> sink) {
                if(calls.incrementAndGet()==1) {
                    entered.countDown(); return timeout ? new CompletableFuture<>()
                        : CompletableFuture.failedFuture(new DeepSeekFailure(DeepSeekFailure.Code.RATE_LIMITED));
                }
                sink.accept("recovered fixture"); return CompletableFuture.completedFuture(null);
            }
            public void cancel(String id) { throw new IllegalStateException("private-cleanup-response"); }
        };
        try(var rig=new CoreRig(directory,model)) {
            rig.engine.accept(rig.event("bad",LiveEvent.Type.COMMENT)); rig.engine.tick();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); if(timeout) advance(rig,21);
            rig.await(() -> rig.engine.status().failures()==1); idle(rig);
            var failed=rig.engine.diagnostics().trace().lastFailure(); var error=rig.engine.status().lastError(); var at=rig.engine.status().lastErrorAt();
            assertThat(failed.reason()).isEqualTo(timeout ? Reason.OVERALL_TIMEOUT : Reason.RATE_LIMITED);
            assertThat(rig.engine.status().state()).isEqualTo(RuntimeEngine.State.RUNNING);
            rig.clock.advance(Duration.ofSeconds(1)); rig.engine.accept(rig.event("good",LiveEvent.Type.COMMENT));
            rig.await(() -> rig.peer.tasks.size()==1); complete(rig); idle(rig);
            var recovered=rig.engine.diagnostics().trace();
            assertThat(recovered.lastFailure()).isEqualTo(failed); assertThat(recovered.history()).contains(failed);
            assertThat(recovered.lastModelSuccessAt()).isAfter(failed.at());
            assertThat(recovered.lastSpeechSuccessAt()).isAfter(failed.at());
            assertThat(recovered.lastPlaybackSuccessAt()).isAfter(failed.at());
            assertThat(rig.engine.status().lastError()).isEqualTo(error); assertThat(rig.engine.status().lastErrorAt()).isEqualTo(at);
            assertThat(rig.engine.status().failures()).isEqualTo(1);
            assertThat(recovered.toString()).doesNotContain("private-","recovered fixture");
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void synthesisFailureOrTimeoutRecoversWithoutCallingModelOrFailingSession(boolean timeout) throws Exception {
        var calls=new AtomicInteger(); var entered=new CountDownLatch(1); var tone=new LocalTestSpeech(directory.resolve("audio"));
        SpeechPort speech=new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration d) {
                if(calls.incrementAndGet()==1) {
                    entered.countDown(); return timeout ? new CompletableFuture<>()
                        : CompletableFuture.failedFuture(new SpeechFailure(SpeechFailure.Code.RATE_LIMITED));
                }
                return tone.synthesize(id,text,voice,d);
            }
            public void cancel(String id) { throw new IllegalStateException("private-speech-cleanup"); }
        };
        try(var rig=new CoreRig(directory,new MockConversation(),speech)) {
            rig.engine.accept(rig.event("bad",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200)); rig.engine.tick();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); if(timeout) advance(rig,6);
            rig.await(() -> rig.engine.status().failures()==1); idle(rig);
            var failed=rig.engine.diagnostics().trace().lastFailure();
            assertThat(failed.reason()).isEqualTo(timeout ? Reason.OVERALL_TIMEOUT : Reason.RATE_LIMITED);
            assertThat(rig.engine.status().state()).isEqualTo(RuntimeEngine.State.RUNNING);
            rig.clock.advance(Duration.ofSeconds(1)); rig.engine.accept(rig.event("good",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> rig.peer.tasks.size()==1); complete(rig); idle(rig);
            var recovered=rig.engine.diagnostics().trace();
            assertThat(recovered.lastFailure()).isEqualTo(failed); assertThat(recovered.lastModelSuccessAt()).isNull();
            assertThat(recovered.lastSpeechSuccessAt()).isAfter(failed.at()); assertThat(recovered.lastPlaybackSuccessAt()).isAfter(failed.at());
            assertThat(rig.engine.status().failures()).isEqualTo(1); assertThat(rig.engine.status().state()).isEqualTo(RuntimeEngine.State.RUNNING);
        }
    }
    @Test void invalidAudioCancelAndReleaseCountOneRootFailureThenRecover() throws Exception {
        var calls=new AtomicInteger(); var releases=new AtomicInteger(); var tone=new LocalTestSpeech(directory.resolve("audio"));
        SpeechPort speech=new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration d) {
                return calls.incrementAndGet()==1 ? CompletableFuture.completedFuture(new AudioAsset(directory.resolve("unused"),"audio/wav",Duration.ofSeconds(31)))
                    : tone.synthesize(id,text,voice,d);
            }
            public void cancel(String id) { throw new IllegalStateException("private-cancel"); }
            public boolean release(AudioAsset asset) {
                if(releases.incrementAndGet()==1) throw new IllegalStateException("private-release"); return false;
            }
        };
        try(var rig=new CoreRig(directory,new MockConversation(),speech)) {
            rig.engine.accept(rig.event("bad",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> rig.engine.status().failures()==1); idle(rig);
            assertThat(releases).hasValue(1); assertThat(rig.engine.status().lastError()).isEqualTo("NLS_AUDIO_TOO_LONG");
            assertThat(rig.engine.diagnostics().trace().lastFailure().reason()).isEqualTo(Reason.INVALID_AUDIO);
            rig.clock.advance(Duration.ofSeconds(1)); rig.engine.accept(rig.event("good",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> rig.peer.tasks.size()==1); complete(rig); idle(rig);
            assertThat(releases).hasValue(2); assertThat(rig.engine.status().failures()).isEqualTo(1);
            assertThat(rig.engine.status().lastError()).isEqualTo("NLS_AUDIO_TOO_LONG");
        }
    }
    @Test void userStopAndOldLateFailureDoNotBecomeNewSessionServiceFailure() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var calls=new AtomicInteger();
        ConversationPort model=new ConversationPort() {
            public CompletionStage<Void> reply(String id,LiveEvent e,ConfigSnapshot c,Duration d,Consumer<String> sink) {
                if(calls.incrementAndGet()==1) {
                    entered.countDown(); boolean done=false;
                    while(!done) { try { done=release.await(3,TimeUnit.SECONDS); if(!done) throw new AssertionError("late gate timeout"); }
                        catch(InterruptedException ignored) {} }
                    throw new DeepSeekFailure(DeepSeekFailure.Code.SERVER_ERROR);
                }
                sink.accept("new session success"); return CompletableFuture.completedFuture(null);
            }
            public void cancel(String id) {
                if(Thread.currentThread().getName().equals("bigfish-conversation")) throw new IllegalStateException("private-late-cancel");
            }
        };
        try(var rig=new CoreRig(directory,model)) {
            rig.engine.accept(rig.event("old",LiveEvent.Type.COMMENT)); rig.engine.tick();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); rig.engine.stop(rig.session);
            assertThat(rig.engine.status().failures()).isZero(); assertThat(rig.engine.status().lastError()).isEmpty();
            assertThat(rig.engine.diagnostics().trace().lastFailure()).isNull();
            assertThat(rig.engine.diagnostics().trace().history().getLast().reason()).isEqualTo(Reason.SESSION_STOPPED);
            rig.playback.disconnected(rig.peer); String next=rig.engine.startMock(); var peer=new CoreRig.Peer(); rig.connect(peer);
            release.countDown(); idle(rig);
            assertThat(rig.engine.status().failures()).isZero(); assertThat(rig.engine.status().lastError()).isEmpty();
            assertThat(rig.engine.diagnostics().trace().history()).isEmpty(); assertThat(rig.engine.diagnostics().trace().lastFailure()).isNull();
            var e=rig.event("new",LiveEvent.Type.COMMENT);
            rig.engine.accept(new LiveEvent(1,e.eventId(),next,e.roomId(),e.type(),e.userId(),e.username(),e.receivedAt(),e.payload()));
            rig.await(() -> peer.tasks.size()==1);
            assertThat(peer.tasks.getFirst().text()).isEqualTo("new session success");
            assertThat(rig.engine.diagnostics().trace().history()).allMatch(t -> t.sessionId().equals(next));
        } finally { release.countDown(); }
    }
}
