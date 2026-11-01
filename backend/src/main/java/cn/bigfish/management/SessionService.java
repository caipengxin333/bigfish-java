package cn.bigfish.management;

import cn.bigfish.collector.DouyinRoomInput;
import cn.bigfish.collector.DouyinFailure;
import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.configuration.ProviderServices;
import cn.bigfish.configuration.SessionCollectors;
import cn.bigfish.contract.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;

/** Owns the collector lifecycle; callbacks only enter the non-blocking engine ingress. */
@Service
public final class SessionService {
    public static final class SessionException extends RuntimeException {
        public final String code;
        public final int status;
        public SessionException(String code, int status) { super(code); this.code = code; this.status = status; }
    }
    private final RuntimeEngine engine;
    private final ConfigStore configs;
    private final ProviderServices providers;
    private final SessionCollectors collectors;
    // Keep the retired handle until ALL resources exit. Never create an unbounded list of old runs.
    private Run run;
    private static final class Run {
        final String sessionId, mode, requestedRoom;
        SessionCollectors.Handle handle;
        final AtomicBoolean open;
        final AtomicLong rejected = new AtomicLong(), accepted = new AtomicLong();
        CollectorPort.State terminal;
        String startupError = "";
        Run(String id, String mode, String room, AtomicBoolean open) {
            this.sessionId=id; this.mode=mode; this.requestedRoom=room; this.open=open;
        }
        CollectorPort.State state() { return terminal != null ? terminal : handle == null ? CollectorPort.State.CONNECTING : handle.port().state(); }
        String error() { return !startupError.isEmpty() ? startupError : handle == null ? "" : handle.diagnostics().lastError(); }
    }

    public SessionService(RuntimeEngine engine, ConfigStore configs, ProviderServices providers, SessionCollectors collectors) {
        this.engine=engine; this.configs=configs; this.providers=providers; this.collectors=collectors;
    }

    public synchronized RuntimeView start(StartSessionRequest request) {
        reconcile();
        if (engine.status().state() != RuntimeEngine.State.STOPPED && engine.status().state() != RuntimeEngine.State.FAILED)
            throw new SessionException("SESSION_ACTIVE", 409);
        ensureReleased();
        var settings=configs.snapshot().settings();
        String room="mock-room";
        if (request.mode()==StartSessionRequest.Mode.DOUYIN) {
            if (settings.roomId()==null || settings.roomId().isBlank()) throw new SessionException("DOUYIN_ROOM_REQUIRED",422);
            try { room=DouyinRoomInput.parse(settings.roomId()).webRoomId(); }
            catch (DouyinFailure failure) { throw new SessionException("DOUYIN_"+failure.code().name(),422); }
        }
        SpeechPort selected=providers.speech(request.speechMode(),settings.voice());
        try { engine.start(request.mode(),request.conversationMode() == StartSessionRequest.ConversationMode.DEEPSEEK,selected,request.speechMode().name()); }
        catch (IllegalStateException error) { throw new SessionException("PROVIDER_UNAVAILABLE", 503); }
        // Empty source waits for an explicit replay; starting a session never invents viewer messages.
        startCollector(request.mode(),room,new byte[0]);
        return status();
    }
    public synchronized RuntimeView stop(String sessionId) {
        if (!engine.status().sessionId().equals(sessionId)) throw new SessionException("SESSION_MISMATCH", 409);
        // Close the engine ingress before cancelling dispatch; any racing callback is rejected.
        if (run!=null) run.open.set(false);
        engine.stop(sessionId);
        retire(CollectorPort.State.STOPPED);
        return status();
    }
    public synchronized EventIngress.Result simulate(LiveEvent event) {
        if (!engine.status().mode().equals("MOCK")) throw new SessionException("MOCK_ONLY",409);
        return engine.accept(event);
    }

    public synchronized RuntimeView replay(String sessionId) {
        var current = engine.status();
        if (!current.sessionId().equals(sessionId) || current.state() != RuntimeEngine.State.RUNNING)
            throw new SessionException("SESSION_MISMATCH", 409);
        if (!current.mode().equals("MOCK")) throw new SessionException("MOCK_ONLY",409);
        if (!current.playback().ready()) throw new SessionException("PLAYER_NOT_READY", 409);
        String id = UUID.randomUUID().toString();
        // Original synthetic input, not copied from test resources or a platform recording.
        String data = """
            {"kind":"COMMENT","messageId":"%s-chat","roomId":"mock-room","userId":"demo-a","username":"模拟观众","content":"你好，大肥鱼"}
            {"kind":"GIFT","messageId":"%s-g1","roomId":"mock-room","userId":"demo-b","username":"模拟礼物观众","giftId":"heart","giftName":"小心心","comboId":"%s","cumulativeQuantity":1}
            {"kind":"GIFT","messageId":"%s-g3","roomId":"mock-room","userId":"demo-b","username":"模拟礼物观众","giftId":"heart","giftName":"小心心","comboId":"%s","cumulativeQuantity":3}
            {"kind":"GIFT","messageId":"%s-end","roomId":"mock-room","userId":"demo-b","username":"模拟礼物观众","giftId":"heart","giftName":"小心心","comboId":"%s","cumulativeQuantity":3}
            """.formatted(id,id,id,id,id,id,id);
        retire(CollectorPort.State.STOPPED);
        ensureReleased();
        startCollector(StartSessionRequest.Mode.MOCK,"mock-room",data.getBytes(StandardCharsets.UTF_8));
        return status();
    }
    private void startCollector(StartSessionRequest.Mode mode, String requestedRoom, byte[] input) {
        String id=engine.status().sessionId();
        var open=new AtomicBoolean(true);
        Run context=new Run(id,mode.name(),requestedRoom,open); run=context;
        try {
            context.handle=mode==StartSessionRequest.Mode.MOCK ? collectors.mock(input)
                : collectors.douyin(room -> open.get() && engine.bindDouyinRoom(id,room));
            context.handle.port().start(requestedRoom,id,event -> {
                // No SessionService lock here: stop can wait for these workers to exit.
                if (!context.open.get()) return;
                var result=engine.accept(event);
                if (result==EventIngress.Result.ACCEPTED) context.accepted.incrementAndGet();
                else if (result!=EventIngress.Result.DUPLICATE) context.rejected.incrementAndGet();
            });
        } catch (RuntimeException failure) {
            open.set(false);
            context.startupError="COLLECTOR_START_FAILED";
            retire(CollectorPort.State.FAILED);
            engine.collectorFailed(id);
            throw new SessionException("COLLECTOR_START_FAILED",503);
        }
    }
    @Scheduled(fixedDelay = 250)
    public synchronized void reconcile() {
        if (run == null || run.terminal!=null) return;
        var state=run.state();
        if (state==CollectorPort.State.ENDED || state==CollectorPort.State.FAILED) {
            // Close ingress/playback before disposing the source; preserve the terminal evidence.
            run.open.set(false);
            if (state==CollectorPort.State.FAILED) engine.collectorFailed(run.sessionId);
            else engine.stop(run.sessionId);
            retire(state);
        } else if (engine.status().state()!=RuntimeEngine.State.RUNNING) retire(CollectorPort.State.STOPPED);
    }
    public synchronized RuntimeView status() {
        var s = engine.status(); var config = configs.snapshot().settings(); var p = s.playback();
        int capacity = config.synthesisQueueCapacity();
        var d = run == null || run.handle == null ? null : run.handle.diagnostics();
        return new RuntimeView(s.sessionId(),s.state().name(),s.mode(),s.conversationMode(),s.speechMode(),s.roomId(),
            s.savedConfigVersion(),s.appliedConfigVersion(),new RuntimeView.Queues(
                new RuntimeView.Queue(s.comments(),capacity),new RuntimeView.Queue(s.templates(),capacity),
                new RuntimeView.Queue(s.synthesis(),capacity),new RuntimeView.Queue(p.queued(),config.playbackQueueCapacity()),
                s.modelActive(),s.speechActive()),new RuntimeView.Player(p.connected(),p.ready(),p.playing(),p.generation()),
            new RuntimeView.Collector(s.mode(),run == null ? "STOPPED" : run.state().name(),
                d == null ? 0 : d.count(RECEIVED),d == null ? 0 : d.count(EMITTED),d == null ? 0 : d.count(DUPLICATE),
                run == null ? 0 : run.rejected.get(),run == null ? "" : run.error()),s.dropped(),s.failures(),s.lastError(),s.lastErrorAt());
    }
    /** Read-only evidence, never a connectivity probe. Connected alone is not receive verification. */
    public synchronized ProvidersView.Provider douyinStatus() {
        if (run==null || !run.mode.equals("DOUYIN"))
            return new ProvidersView.Provider(true,true,"UNVERIFIED","LIVE_RECEIVE_NOT_VERIFIED");
        String reason=!run.error().isEmpty() ? run.error()
            : run.accepted.get()>0 ? "LIVE_EVENT_OBSERVED" : "LIVE_RECEIVE_NOT_VERIFIED";
        var state=run.state();
        boolean active=state==CollectorPort.State.CONNECTING || state==CollectorPort.State.CONNECTED || state==CollectorPort.State.RECONNECTING;
        return new ProvidersView.Provider(true,true,active && run.accepted.get()==0 ? "UNVERIFIED" : state.name(),reason);
    }
    private void retire(CollectorPort.State terminal) {
        if (run==null) return;
        run.open.set(false); run.terminal=terminal;
        if (run.handle==null) return;
        run.handle.port().stop();
        long until=System.nanoTime()+250_000_000L;
        while (!run.handle.released().getAsBoolean() && System.nanoTime()<until && !Thread.currentThread().isInterrupted())
            LockSupport.parkNanos(1_000_000L);
    }
    private void ensureReleased() {
        if (run!=null && run.handle!=null && !run.handle.released().getAsBoolean()) throw new SessionException("COLLECTOR_STOPPING",409);
    }
    @PreDestroy public synchronized void close() { engine.stop(engine.status().sessionId()); retire(CollectorPort.State.STOPPED); }
}
