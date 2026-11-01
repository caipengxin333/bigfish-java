package cn.bigfish.core;

import cn.bigfish.collector.*;
import cn.bigfish.configuration.*;
import cn.bigfish.contract.*;
import cn.bigfish.conversation.*;
import cn.bigfish.management.*;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.*;
import cn.bigfish.speech.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class FailureConsistencyTest {
    @TempDir Path directory;
    static StartSessionRequest request(StartSessionRequest.Mode mode,StartSessionRequest.ConversationMode conversation) {
        return new StartSessionRequest(mode,conversation);
    }
    final class SessionRig implements AutoCloseable {
        final CoreRig core;
        final ProviderServices providers;
        final SessionService sessions;
        final ManagementController controller;
        final CountDownLatch failSource=new CountDownLatch(1);
        final AtomicReference<SessionCollectors.Handle> source=new AtomicReference<>();
        SessionRig(ConversationPort model) throws Exception {
            core=new CoreRig(directory,model); core.engine.stop(core.session); core.playback.disconnected(core.peer);
            var c=core.configs.current(); var s=c.settings();
            core.configs.update(new UpdateConfigRequest(c.version(),new RuntimeSettings(s.profileName(),s.persona(),"123",
                s.model(),s.maxOutputTokens(),s.voice(),s.readComment(),s.welcomeIntervalSeconds(),s.synthesisQueueCapacity(),s.playbackQueueCapacity(),s.priorities())));
            var factory=new SessionCollectors() {
                @Override public Handle douyin(Predicate<String> bind) {
                    assertThat(bind.test("901")).isTrue();
                    var collector=new DouyinCollector(room -> {
                        if(!failSource.await(3,TimeUnit.SECONDS)) throw new AssertionError("source gate timed out");
                        throw new DouyinFailure(DouyinFailure.Code.PAGE_STRUCTURE_CHANGED);
                    });
                    var handle=new Handle(collector,collector.diagnostics(),() -> collector.resources().released());
                    source.set(handle); return handle;
                }
            };
            providers=new ProviderServices(new MockEnvironment(),new ObjectMapper(),core.speech,directory.toString());
            sessions=new SessionService(core.engine,core.configs,providers,factory);
            controller=new ManagementController(core.configs,core.engine,sessions,providers);
        }
        String start() { return sessions.start(request(StartSessionRequest.Mode.DOUYIN,StartSessionRequest.ConversationMode.MOCK)).sessionId(); }
        void fail() throws Exception {
            failSource.countDown();
            core.await(() -> source.get().port().state()==CollectorPort.State.FAILED);
        }
        LiveEvent event(String id,LiveEvent.Type type) {
            var e=core.event(id,type);
            return new LiveEvent(1,id,core.engine.status().sessionId(),"901",type,e.userId(),e.username(),core.clock.instant(),e.payload());
        }
        public void close() { failSource.countDown(); sessions.close(); providers.close(); core.close(); }
    }
    @Test void pageFailureIsConsistentOnReadWithoutWaitingForScheduledReconcile() throws Exception {
        try(var rig=new SessionRig(new MockConversation())) {
            String session=rig.start(); rig.fail();
            var view=rig.controller.status();
            assertThat(view.sessionId()).isEqualTo(session); assertThat(view.state()).isEqualTo("FAILED");
            assertThat(view.collector().state()).isEqualTo("FAILED");
            assertThat(view.collector().lastError()).isEqualTo("PAGE_STRUCTURE_CHANGED");
            assertThat(view.lastError()).isEqualTo("COLLECTOR_FAILED");
            assertThat(view.collector().received()).isZero(); assertThat(view.collector().emitted()).isZero(); assertThat(view.collector().rejected()).isZero();
            assertThat(view.player().connected() || view.player().ready() || view.player().playing()).isFalse();
            var trace=rig.controller.diagnostics();
            assertThat(trace.sessionId()).isEqualTo(session); assertThat(trace.state()).isEqualTo("FAILED");
            assertThat(trace.recent()).isEmpty(); assertThat(trace.lastFailure()).isNull();
            assertThat(trace.lastReceivedAt()).isNull(); assertThat(trace.lastModelSuccessAt()).isNull();
            assertThat(trace.lastSpeechSuccessAt()).isNull(); assertThat(trace.lastPlaybackSuccessAt()).isNull();
            for(int i=0;i<10;i++) {
                assertThat(rig.controller.status().lastErrorAt()).isEqualTo(view.lastErrorAt());
                assertThat(rig.controller.status().failures()).isEqualTo(1);
                assertThat(rig.controller.bootstrap().get("sessionState")).isEqualTo("FAILED");
                assertThat(rig.controller.providers().douyin().reason()).isEqualTo("PAGE_STRUCTURE_CHANGED");
                rig.controller.config();
            }
            assertThat(rig.source.get().released().getAsBoolean()).isTrue();
        }
    }
    @Test void collectorRootOutlivesDelayedCancellationErrorAndPlayerClose() throws Exception {
        var entered=new CountDownLatch(1); var lateCancel=new CountDownLatch(1); var releaseCancel=new CountDownLatch(1);
        ConversationPort model=new ConversationPort() {
            public CompletionStage<Void> reply(String id,LiveEvent e,ConfigSnapshot c,Duration d,Consumer<String> sink) {
                entered.countDown(); return new CompletableFuture<>();
            }
            public void cancel(String id) {
                if(Thread.currentThread().getName().equals("bigfish-conversation")) {
                    lateCancel.countDown(); boolean done=false;
                    while(!done) { try { done=releaseCancel.await(3,TimeUnit.SECONDS); if(!done) throw new AssertionError("cancel gate timeout"); }
                        catch(InterruptedException ignored) {} }
                    throw new IllegalStateException("private-cancel-detail");
                }
            }
        };
        try(var rig=new SessionRig(model)) {
            String session=rig.start(); var peer=new CoreRig.Peer(); rig.core.connect(peer);
            rig.core.engine.accept(rig.event("comment",LiveEvent.Type.COMMENT)); rig.core.engine.tick();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            rig.core.engine.accept(rig.event("gift",LiveEvent.Type.GIFT)); rig.core.clock.advance(Duration.ofMillis(200));
            rig.core.await(() -> peer.tasks.size()==1);
            rig.fail(); rig.sessions.reconcile();
            assertThat(lateCancel.await(1,TimeUnit.SECONDS)).isTrue();
            var failed=rig.sessions.status(); assertThat(failed.lastError()).isEqualTo("COLLECTOR_FAILED");
            rig.core.clock.advance(Duration.ofSeconds(1)); releaseCancel.countDown(); rig.core.playback.disconnected(peer);
            rig.core.await(() -> rig.core.engine.diagnostics().modelWorker().active()==0);
            var after=rig.sessions.status();
            assertThat(after.lastError()).isEqualTo("COLLECTOR_FAILED"); assertThat(after.lastErrorAt()).isEqualTo(failed.lastErrorAt());
            assertThat(after.collector().lastError()).isEqualTo("PAGE_STRUCTURE_CHANGED");
            assertThat(after.failures()).isEqualTo(1); assertThat(after.state()).isEqualTo("FAILED");
            assertThat(after.player().connected() || after.player().ready() || after.player().playing()).isFalse();
            assertThat(rig.core.assets.size()).isZero();
            assertThat(rig.controller.diagnostics().recent()).allSatisfy(t -> assertThat(t.reason()).doesNotContain("private-"));
            rig.sessions.stop(session); assertThat(rig.sessions.status().lastError()).isEqualTo("COLLECTOR_FAILED");
        } finally { releaseCancel.countDown(); }
    }
    @Test void failedNewProviderStartHasFreshIdentityAndNoPreviousCollectorOrTrace() throws Exception {
        try(var rig=new SessionRig(new MockConversation())) {
            String old=rig.start(); rig.fail(); rig.sessions.reconcile();
            assertThat(rig.sessions.status().lastError()).isEqualTo("COLLECTOR_FAILED");
            assertThatThrownBy(() -> rig.sessions.start(request(StartSessionRequest.Mode.MOCK,StartSessionRequest.ConversationMode.DEEPSEEK)))
                .hasMessage("PROVIDER_UNAVAILABLE");
            var next=rig.sessions.status(); var trace=rig.controller.diagnostics();
            assertThat(next.sessionId()).isNotEqualTo(old); assertThat(next.state()).isEqualTo("FAILED");
            assertThat(next.lastError()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(next.collector().mode()).isEqualTo("MOCK"); assertThat(next.collector().state()).isEqualTo("STOPPED");
            assertThat(next.collector().lastError()).isEmpty(); assertThat(next.collector().received()).isZero();
            assertThat(trace.sessionId()).isEqualTo(next.sessionId()); assertThat(trace.recent()).isEmpty(); assertThat(trace.lastFailure()).isNull();
            assertThat(rig.controller.providers().douyin().state()).isEqualTo("UNVERIFIED");
        }
    }
    @Test void explicitStopObservesAlreadyFailedCollectorBeforeChangingStateToStopped() throws Exception {
        try(var rig=new SessionRig(new MockConversation())) {
            String session=rig.start(); rig.fail();
            var stopped=rig.sessions.stop(session);
            assertThat(stopped.state()).isEqualTo("STOPPED"); assertThat(stopped.collector().state()).isEqualTo("STOPPED");
            assertThat(stopped.lastError()).isEqualTo("COLLECTOR_FAILED");
            assertThat(stopped.collector().lastError()).isEqualTo("PAGE_STRUCTURE_CHANGED");
            assertThat(stopped.failures()).isEqualTo(1);
            rig.core.clock.advance(Duration.ofSeconds(1));
            var retry=rig.sessions.stop(session);
            assertThat(retry.lastErrorAt()).isEqualTo(stopped.lastErrorAt()); assertThat(retry.failures()).isEqualTo(1);
        }
    }
    @Test void providerCancellationsAreTerminalCancellationsNotServiceFailures() throws Exception {
        var modelError=new AtomicReference<RuntimeException>(); var speechError=new AtomicReference<RuntimeException>();
        var calls=new AtomicInteger(); var tone=new LocalTestSpeech(directory.resolve("audio"));
        ConversationPort model=new ConversationPort() {
            public CompletionStage<Void> reply(String id,LiveEvent e,ConfigSnapshot c,Duration d,Consumer<String> sink) {
                calls.incrementAndGet(); var failure=modelError.get();
                if(failure!=null) return CompletableFuture.failedFuture(failure);
                sink.accept("fixture reply"); return CompletableFuture.completedFuture(null);
            }
            public void cancel(String id) {}
        };
        SpeechPort speech=new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration d) {
                var failure=speechError.get(); return failure==null ? tone.synthesize(id,text,voice,d) : CompletableFuture.failedFuture(failure);
            }
            public void cancel(String id) {}
        };
        try(var rig=new CoreRig(directory,model,speech)) {
            RuntimeException[] cancellations={new CancellationException(),new DeepSeekFailure(DeepSeekFailure.Code.CANCELLED),
                new CompletionException(new InterruptedException()),new SpeechFailure(SpeechFailure.Code.CANCELLED)};
            for(int i=0;i<cancellations.length;i++) {
                if(i<3) modelError.set(cancellations[i]); else { modelError.set(null); speechError.set(cancellations[i]); }
                rig.engine.accept(rig.event("cancel-"+i,LiveEvent.Type.COMMENT)); final int expected=i+1;
                rig.await(() -> calls.get()==expected && rig.engine.diagnostics().modelWorker().active()==0
                    && rig.engine.diagnostics().speechWorker().active()==0 && !rig.engine.status().modelActive()
                    && !rig.engine.status().speechActive() && rig.engine.status().synthesis()==0);
                assertThat(rig.engine.status().failures()).isZero(); assertThat(rig.engine.status().lastError()).isEmpty();
                var snapshot=rig.engine.diagnostics().trace(); assertThat(snapshot.lastFailure()).isNull();
                assertThat(snapshot.history().getLast().stage()).isEqualTo(Stage.CANCELLED);
                assertThat(snapshot.history().getLast().reason()).isEqualTo(Reason.PROVIDER_CANCELLED);
                assertThat(rig.engine.status().state()).isEqualTo(RuntimeEngine.State.RUNNING);
            }
        }
    }
}
