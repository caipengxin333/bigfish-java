package cn.bigfish.core;

import cn.bigfish.collector.*;
import cn.bigfish.configuration.*;
import cn.bigfish.contract.*;
import cn.bigfish.conversation.MockConversation;
import cn.bigfish.management.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DouyinSessionTest {
    @TempDir Path directory;
    static final StartSessionRequest DOUYIN=new StartSessionRequest(StartSessionRequest.Mode.DOUYIN,StartSessionRequest.ConversationMode.MOCK);
    static final StartSessionRequest MOCK=new StartSessionRequest(StartSessionRequest.Mode.MOCK,StartSessionRequest.ConversationMode.MOCK);

    static final class Controlled implements CollectorPort {
        State state=State.STOPPED;
        boolean held, stopped, throwOnStart;
        String requested, session;
        Consumer<LiveEvent> sink;
        public void start(String room,String id,Consumer<LiveEvent> sink) {
            requested=room; session=id; this.sink=sink; state=State.CONNECTING;
            if (throwOnStart) throw new IllegalStateException("untrusted-provider-detail");
        }
        public void stop() { stopped=true; state=State.STOPPED; }
        public State state() { return state; }
    }
    static class Factory extends SessionCollectors {
        int mockCalls, douyinCalls;
        Controlled current;
        Predicate<String> binding;
        boolean throwOnStart;
        @Override public Handle mock(byte[] input) { mockCalls++; return super.mock(input); }
        @Override public Handle douyin(Predicate<String> bind) {
            douyinCalls++; binding=bind; current=new Controlled(); current.throwOnStart=throwOnStart;
            var captured=current;
            return new Handle(captured,new CollectorDiagnostics(),() -> captured.stopped && !captured.held);
        }
    }
    final class Harness implements AutoCloseable {
        final CoreRig rig;
        final ProviderServices providers;
        final SessionService sessions;
        final Factory factory;
        Harness() throws Exception { this(new Factory()); }
        Harness(Factory factory) throws Exception {
            this.factory=factory; rig=new CoreRig(directory,new MockConversation()); rig.engine.stop(rig.session);
            providers=new ProviderServices(new MockEnvironment(),new ObjectMapper(),rig.speech,directory.toString());
            sessions=new SessionService(rig.engine,rig.configs,providers,factory);
        }
        void saveRoom(String room) {
            var c=rig.configs.current(); var s=c.settings();
            rig.configs.update(new UpdateConfigRequest(c.version(),new RuntimeSettings(s.profileName(),s.persona(),room,
                s.model(),s.maxOutputTokens(),s.voice(),s.readComment(),s.welcomeIntervalSeconds(),
                s.synthesisQueueCapacity(),s.playbackQueueCapacity(),s.priorities())));
        }
        RuntimeView start() { saveRoom("549511642024"); return sessions.start(DOUYIN); }
        void ready() {
            // A real new socket first observes the old transport close, then obtains a new lease.
            rig.playback.disconnected(rig.peer); rig.peer.closed=false; rig.connect(rig.peer);
            assertThat(rig.playback.status().ready()).isTrue();
        }
        LiveEvent event(String id,String session,String room,LiveEvent.Type type) {
            var e=rig.event(id,type);
            return new LiveEvent(1,id,session,room,type,e.userId(),e.username(),rig.clock.instant(),e.payload());
        }
        public void close() { sessions.close(); providers.close(); rig.close(); }
    }

    @Test void savingPollingAndMockNeverCreateRealCollectorAndInvalidRoomsFailBeforeFactory() throws Exception {
        try(var h=new Harness()) {
            for(String input:new String[]{"","bad-room","https://evil.invalid/123"}) {
                h.saveRoom(input);
                assertThatThrownBy(() -> h.sessions.start(DOUYIN)).isInstanceOf(SessionService.SessionException.class)
                    .hasMessage(input.isEmpty() ? "DOUYIN_ROOM_REQUIRED" : "DOUYIN_UNSUPPORTED_ROOM_URL");
            }
            h.saveRoom("549511642024");
            var controller=new ManagementController(h.rig.configs,h.rig.engine,h.sessions,h.providers);
            for(int i=0;i<5;i++) {
                controller.bootstrap(); controller.config(); controller.status(); controller.diagnostics();
                assertThat(controller.providers().douyin().state()).isEqualTo("UNVERIFIED");
            }
            assertThat(h.factory.douyinCalls).isZero(); assertThat(h.factory.mockCalls).isZero();
            var view=h.sessions.start(MOCK);
            assertThat(view.mode()).isEqualTo("MOCK"); assertThat(view.roomId()).isEqualTo("mock-room");
            assertThat(h.factory.mockCalls).isEqualTo(1); assertThat(h.factory.douyinCalls).isZero();
            h.sessions.stop(view.sessionId());
        }
    }
    @Test void strictBindingAndConfigSnapshotSurviveReconnectButNeverSwitchBroadcast() throws Exception {
        try(var h=new Harness()) {
            var started=h.start(); var source=h.factory.current;
            assertThat(started.mode()).isEqualTo("DOUYIN"); assertThat(started.roomId()).isEmpty();
            assertThat(started.collector().state()).isEqualTo("CONNECTING");
            assertThat(source.requested).isEqualTo("549511642024");
            assertThat(h.factory.mockCalls).isZero();
            source.sink.accept(h.event("early",source.session,"901",LiveEvent.Type.COMMENT));
            assertThat(h.rig.engine.status().comments()).isZero();
            assertThat(h.factory.binding.test("901")).isTrue();
            source.state=CollectorPort.State.CONNECTED;
            assertThat(h.sessions.douyinStatus().reason()).isEqualTo("LIVE_RECEIVE_NOT_VERIFIED");
            source.sink.accept(h.event("wrong-web",source.session,"549511642024",LiveEvent.Type.COMMENT));
            source.sink.accept(h.event("wrong-room",source.session,"902",LiveEvent.Type.COMMENT));
            source.sink.accept(h.event("valid",source.session,"901",LiveEvent.Type.COMMENT));
            assertThat(h.rig.engine.status().comments()).isEqualTo(1);
            assertThat(h.sessions.status().collector().rejected()).isEqualTo(3);
            assertThat(h.sessions.douyinStatus().reason()).isEqualTo("LIVE_EVENT_OBSERVED");
            h.saveRoom("777"); source.state=CollectorPort.State.RECONNECTING; h.sessions.reconcile();
            assertThat(h.sessions.status().collector().state()).isEqualTo("RECONNECTING");
            assertThat(source.requested).isEqualTo("549511642024");
            assertThat(h.factory.binding.test("901")).isTrue(); assertThat(h.factory.binding.test("902")).isFalse();
            source.state=CollectorPort.State.CONNECTED;
            assertThat(h.sessions.status().roomId()).isEqualTo("901");
            assertThatThrownBy(() -> h.sessions.start(DOUYIN)).hasMessage("SESSION_ACTIVE");
            assertThatThrownBy(() -> h.sessions.replay(source.session)).hasMessage("MOCK_ONLY");
            assertThatThrownBy(() -> h.sessions.simulate(h.event("fake",source.session,"901",LiveEvent.Type.GIFT))).hasMessage("MOCK_ONLY");
            h.sessions.stop(source.session);
            h.sessions.start(DOUYIN);
            assertThat(h.factory.current.requested).isEqualTo("777");
        }
    }
    @Test void endedOrFailedClosesPlaybackAndQueuesWithoutLosingTerminalEvidence() throws Exception {
        try(var h=new Harness()) {
            for(var terminal:new CollectorPort.State[]{CollectorPort.State.ENDED,CollectorPort.State.FAILED}) {
                var view=h.start(); var source=h.factory.current; h.factory.binding.test("901");
                h.ready();
                source.sink.accept(h.event("gift",source.session,"901",LiveEvent.Type.GIFT));
                h.rig.clock.advance(Duration.ofMillis(200));
                int before=h.rig.peer.tasks.size();
                h.rig.await(() -> h.rig.peer.tasks.size()>before);
                assertThat(h.rig.assets.size()).isPositive();
                source.sink.accept(h.event("pending",source.session,"901",LiveEvent.Type.COMMENT));
                source.state=terminal; h.sessions.reconcile(); h.sessions.reconcile();
                assertThat(h.sessions.status().collector().state()).isEqualTo(terminal.name());
                assertThat(h.sessions.status().state()).isEqualTo(terminal==CollectorPort.State.FAILED ? "FAILED" : "STOPPED");
                assertThat(h.rig.engine.status().comments()).isZero(); assertThat(h.rig.assets.size()).isZero();
                assertThat(h.rig.playback.status().playing()).isFalse(); assertThat(source.stopped).isTrue();
                int stoppedTasks=h.rig.peer.tasks.size();
                source.sink.accept(h.event("late",source.session,"901",LiveEvent.Type.GIFT)); h.rig.engine.tick();
                assertThat(h.rig.peer.tasks).hasSize(stoppedTasks);
                assertThat(h.rig.engine.status().templates()).isZero();
                if (terminal==CollectorPort.State.FAILED) {
                    long failures=h.rig.engine.status().failures(); h.sessions.reconcile();
                    assertThat(h.rig.engine.status().failures()).isEqualTo(failures);
                }
            }
        }
    }
    @Test void stopFencesOldCallbacksAndRefusesNewCollectorUntilEveryResourceExits() throws Exception {
        try(var h=new Harness()) {
            var first=h.start(); var old=h.factory.current; var oldBinding=h.factory.binding;
            h.factory.binding.test("901"); old.held=true;
            h.sessions.stop(first.sessionId());
            assertThat(old.stopped).isTrue(); assertThat(oldBinding.test("901")).isFalse();
            assertThatThrownBy(() -> h.sessions.start(DOUYIN)).hasMessage("COLLECTOR_STOPPING");
            assertThat(h.factory.douyinCalls).isEqualTo(1);
            old.held=false; var next=h.sessions.start(DOUYIN);
            assertThat(next.sessionId()).isNotEqualTo(first.sessionId()); h.factory.binding.test("902");
            old.sink.accept(h.event("old",old.session,"901",LiveEvent.Type.GIFT));
            old.sink.accept(h.event("forged",next.sessionId(),"902",LiveEvent.Type.GIFT));
            assertThat(oldBinding.test("901")).isFalse();
            assertThat(h.rig.engine.status().roomId()).isEqualTo("902");
            assertThat(h.rig.engine.status().templates()).isZero();
            assertThat(h.sessions.douyinStatus().reason()).isEqualTo("LIVE_RECEIVE_NOT_VERIFIED");
            assertThatThrownBy(() -> h.sessions.stop(first.sessionId())).hasMessage("SESSION_MISMATCH");
            assertThat(h.sessions.status().state()).isEqualTo("RUNNING");
        }
    }
    @Test void startupFailureIsFixedCodeAndNextSessionCanRecoverWithoutMockFallback() throws Exception {
        try(var h=new Harness()) {
            h.factory.throwOnStart=true;
            assertThatThrownBy(h::start).hasMessage("COLLECTOR_START_FAILED");
            assertThat(h.sessions.status().state()).isEqualTo("FAILED");
            assertThat(h.sessions.status().collector().state()).isEqualTo("FAILED");
            assertThat(h.sessions.status().collector().lastError()).isEqualTo("COLLECTOR_START_FAILED");
            assertThat(h.factory.current.stopped).isTrue(); assertThat(h.factory.mockCalls).isZero();
            h.factory.throwOnStart=false;
            assertThat(h.sessions.start(DOUYIN).state()).isEqualTo("RUNNING");
        }
    }
    @Test void actualDouyinEndedCollectorReleasesBothWorkersAndDoesNotOpenWss() throws Exception {
        Factory factory=new Factory() {
            @Override public Handle douyin(Predicate<String> bind) {
                var actual=new DouyinCollector(room -> DouyinConnection.ended("901"));
                return new Handle(actual,actual.diagnostics(),() -> actual.resources().released());
            }
        };
        try(var h=new Harness(factory)) {
            h.start();
            h.rig.await(() -> { h.sessions.reconcile(); return h.sessions.status().collector().state().equals("ENDED"); });
            assertThat(h.sessions.status().state()).isEqualTo("STOPPED");
            // A subsequent start would fail COLLECTOR_STOPPING if either old worker still lived.
            h.sessions.start(DOUYIN);
        }
    }
    @Test void actualCollectorRetryBackoffIsVisibleAndStopCancelsItBeforeNextResolve() throws Exception {
        var attempts=new AtomicInteger();
        Factory factory=new Factory() {
            @Override public Handle douyin(Predicate<String> bind) {
                var actual=new DouyinCollector(room -> {
                    attempts.incrementAndGet(); throw new DouyinFailure(DouyinFailure.Code.HTTP_UNAVAILABLE);
                });
                return new Handle(actual,actual.diagnostics(),() -> actual.resources().released());
            }
        };
        try(var h=new Harness(factory)) {
            var view=h.start();
            h.rig.await(() -> h.sessions.status().collector().state().equals("RECONNECTING"));
            assertThat(h.sessions.status().collector().lastError()).isEqualTo("HTTP_UNAVAILABLE");
            h.sessions.stop(view.sessionId());
            int stoppedAttempts=attempts.get();
            // Both source and dispatcher must have exited; a later retry is then impossible.
            h.sessions.start(MOCK); assertThat(attempts.get()).isEqualTo(stoppedAttempts);
            assertThat(h.sessions.status().collector().mode()).isEqualTo("MOCK");
        }
    }
    @Test void permanentRealCollectorFailureKeepsSafeCodeAndReleasesResources() throws Exception {
        Factory factory=new Factory() {
            @Override public Handle douyin(Predicate<String> bind) {
                var actual=new DouyinCollector(room -> { throw new DouyinFailure(DouyinFailure.Code.PAGE_STRUCTURE_CHANGED); });
                return new Handle(actual,actual.diagnostics(),() -> actual.resources().released());
            }
        };
        try(var h=new Harness(factory)) {
            h.start();
            h.rig.await(() -> { h.sessions.reconcile(); return h.sessions.status().state().equals("FAILED"); });
            assertThat(h.sessions.status().collector().lastError()).isEqualTo("PAGE_STRUCTURE_CHANGED");
            assertThat(h.sessions.douyinStatus().state()).isEqualTo("FAILED");
            assertThat(h.sessions.status().lastError()).isEqualTo("COLLECTOR_FAILED");
            assertThat(h.factory.mockCalls).isZero();
            h.sessions.start(MOCK); // Explicit user choice, never an automatic fallback.
        }
    }
    @Test void factoryFailureCannotExposePreviousSessionCollectorAsCurrent() throws Exception {
        Factory factory=new Factory() {
            @Override public Handle douyin(Predicate<String> bind) { throw new IllegalStateException("private-detail"); }
        };
        try(var h=new Harness(factory)) {
            var mock=h.sessions.start(MOCK); h.sessions.stop(mock.sessionId());
            assertThatThrownBy(h::start).hasMessage("COLLECTOR_START_FAILED");
            var failed=h.sessions.status();
            assertThat(failed.sessionId()).isNotEqualTo(mock.sessionId());
            assertThat(failed.mode()).isEqualTo("DOUYIN"); assertThat(failed.collector().state()).isEqualTo("FAILED");
            assertThat(failed.collector().lastError()).isEqualTo("COLLECTOR_START_FAILED");
            assertThat(failed.collector().received()).isZero();
            assertThat(h.sessions.start(MOCK).state()).isEqualTo("RUNNING");
        }
    }
    @Test void asynchronousCollectorDispatchReachesMockReplyAndLocalAudioViaBoundInternalRoom() throws Exception {
        Factory factory=new Factory() {
            @Override public Handle douyin(Predicate<String> bind) {
                assertThat(bind.test("901")).isTrue();
                String data="""
                    {"kind":"COMMENT","messageId":"chat","roomId":"901","userId":"u","username":"fixture","content":"你好"}
                    {"kind":"GIFT","messageId":"gift","roomId":"901","userId":"u","username":"fixture","giftId":"heart","giftName":"小心心","comboId":"c","cumulativeQuantity":1}
                    """;
                return super.mock(data.getBytes(StandardCharsets.UTF_8));
            }
        };
        try(var h=new Harness(factory)) {
            var started=h.start(); h.ready();
            h.rig.await(() -> h.sessions.status().collector().emitted()==2);
            assertThat(h.sessions.status().collector().received()).isEqualTo(2);
            assertThat(h.sessions.status().collector().rejected()).isZero();
            h.rig.clock.advance(Duration.ofMillis(200));
            h.rig.await(() -> h.rig.peer.tasks.size()==1);
            var task=h.rig.peer.tasks.getFirst();
            h.rig.playback.acknowledge(h.rig.peer,new PlaybackAck(task.taskId(),started.sessionId(),h.rig.peer.id(),
                h.rig.playback.status().generation(),PlaybackAck.State.STARTED,h.rig.clock.instant(),null));
            h.rig.playback.acknowledge(h.rig.peer,new PlaybackAck(task.taskId(),started.sessionId(),h.rig.peer.id(),
                h.rig.playback.status().generation(),PlaybackAck.State.COMPLETED,h.rig.clock.instant(),null));
            h.rig.await(() -> h.rig.peer.tasks.size()==2);
            assertThat(h.rig.peer.tasks).extracting(PlaybackTask::type).containsExactlyInAnyOrder("GIFT","COMMENT");
            assertThat(h.rig.peer.tasks).allSatisfy(t -> assertThat(t.sessionId()).isEqualTo(started.sessionId()));
            h.sessions.stop(started.sessionId()); assertThat(h.rig.assets.size()).isZero();
        }
    }
}
