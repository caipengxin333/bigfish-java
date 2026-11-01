package cn.bigfish.core;

import cn.bigfish.collector.CollectorDiagnostics;
import cn.bigfish.configuration.*;
import cn.bigfish.contract.*;
import cn.bigfish.conversation.MockConversation;
import cn.bigfish.management.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SessionLifecycleTest {
    @TempDir Path directory;
    static StartSessionRequest request() { return new StartSessionRequest(StartSessionRequest.Mode.MOCK,StartSessionRequest.ConversationMode.MOCK); }
    @Test void concurrentStartAllocatesExactlyOneCollectorAndStopsAreRetryable() throws Exception {
        var calls=new AtomicInteger(); var owned=new AtomicReference<SessionCollectors.Handle>();
        SessionCollectors factory=new SessionCollectors() {
            @Override public Handle mock(byte[] input) { calls.incrementAndGet(); var h=super.mock(input); owned.set(h); return h; }
        };
        try(var rig=new CoreRig(directory,new MockConversation()); var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            rig.engine.stop(rig.session);
            var providers=new ProviderServices(new MockEnvironment(),new ObjectMapper(),rig.speech,directory.toString());
            var sessions=new SessionService(rig.engine,rig.configs,providers,factory);
            try {
                var gate=new CountDownLatch(1); var ready=new CountDownLatch(8); var attempts=new ArrayList<Future<String>>();
                for(int i=0;i<8;i++) attempts.add(threads.submit(() -> {
                    ready.countDown(); gate.await();
                    try { return sessions.start(request()).sessionId(); }
                    catch(SessionService.SessionException e) { assertThat(e.status).isEqualTo(409); return e.code; }
                }));
                assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue(); gate.countDown();
                var results=new ArrayList<String>(); for(var attempt:attempts) results.add(attempt.get(3,TimeUnit.SECONDS));
                assertThat(results.stream().filter("SESSION_ACTIVE"::equals).count()).isEqualTo(7);
                assertThat(calls).hasValue(1); String id=sessions.status().sessionId();
                assertThat(results).contains(id); assertThat(sessions.status().player().ready()).isFalse();
                var stops=new ArrayList<Future<RuntimeView>>();
                for(int i=0;i<8;i++) stops.add(threads.submit(() -> sessions.stop(id)));
                for(var stopped:stops) assertThat(stopped.get(3,TimeUnit.SECONDS).state()).isEqualTo("STOPPED");
                assertThat(owned.get().released().getAsBoolean()).isTrue();
                var next=sessions.start(request());
                assertThatThrownBy(() -> sessions.stop(id)).hasMessage("SESSION_MISMATCH");
                assertThat(sessions.status().sessionId()).isEqualTo(next.sessionId());
                assertThat(sessions.status().state()).isEqualTo("RUNNING");
            } finally { sessions.close(); providers.close(); }
        }
    }
    @Test void partiallyStartedCollectorClosesIngressBeforeWaitingForResourceExit() throws Exception {
        try(var rig=new CoreRig(directory,new MockConversation())) {
            rig.engine.stop(rig.session);
            var stopped=new AtomicBoolean(); var held=new AtomicBoolean(true); var creates=new AtomicInteger();
            var duringExit=new AtomicReference<RuntimeEngine.State>();
            SessionCollectors factory=new SessionCollectors() {
                @Override public Handle mock(byte[] input) {
                    creates.incrementAndGet();
                    CollectorPort partial=new CollectorPort() {
                        public void start(String room,String session,Consumer<LiveEvent> sink) { throw new IllegalStateException("private-start-detail"); }
                        public void stop() { stopped.set(true); }
                        public State state() { return stopped.get() ? State.STOPPED : State.CONNECTING; }
                    };
                    return new Handle(partial,new CollectorDiagnostics(),() -> {
                        duringExit.compareAndSet(null,rig.engine.status().state()); return !held.get();
                    });
                }
            };
            var providers=new ProviderServices(new MockEnvironment(),new ObjectMapper(),rig.speech,directory.toString());
            var sessions=new SessionService(rig.engine,rig.configs,providers,factory);
            try {
                assertThatThrownBy(() -> sessions.start(request())).hasMessage("COLLECTOR_START_FAILED");
                assertThat(duringExit.get()).isEqualTo(RuntimeEngine.State.FAILED);
                assertThat(stopped).isTrue(); assertThat(sessions.status().player().connected()).isFalse();
                assertThatThrownBy(() -> sessions.start(request())).hasMessage("COLLECTOR_STOPPING");
                assertThat(creates).hasValue(1);
            } finally { held.set(false); sessions.close(); providers.close(); }
        }
    }
}
