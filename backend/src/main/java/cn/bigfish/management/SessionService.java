package cn.bigfish.management;

import cn.bigfish.collector.MockCollector;
import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.contract.*;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;

/** Owns the collector lifecycle; callbacks only enter the non-blocking engine ingress. */
@Service
public final class SessionService {
    public static final class SessionException extends RuntimeException {
        public final String code;
        public final int status;
        SessionException(String code, int status) { super(code); this.code = code; this.status = status; }
    }
    private final RuntimeEngine engine;
    private final ConfigStore configs;
    private MockCollector collector;
    private AtomicLong rejected = new AtomicLong();

    public SessionService(RuntimeEngine engine, ConfigStore configs) { this.engine = engine; this.configs = configs; }

    public synchronized RuntimeView start(StartSessionRequest request) {
        if (request.mode() != StartSessionRequest.Mode.MOCK) throw new SessionException("DOUYIN_UNAVAILABLE", 422);
        if (engine.status().state() != RuntimeEngine.State.STOPPED && engine.status().state() != RuntimeEngine.State.FAILED)
            throw new SessionException("SESSION_ACTIVE", 409);
        closeCollector();
        try { engine.startMock(request.conversationMode() == StartSessionRequest.ConversationMode.DEEPSEEK); }
        catch (IllegalStateException error) { throw new SessionException("PROVIDER_UNAVAILABLE", 503); }
        // Empty source waits for an explicit replay; starting a session never invents viewer messages.
        replaceCollector(new byte[0]);
        return status();
    }
    public synchronized RuntimeView stop(String sessionId) {
        if (!engine.status().sessionId().equals(sessionId)) throw new SessionException("SESSION_MISMATCH", 409);
        // Close the engine ingress before cancelling dispatch; any racing callback is rejected.
        engine.stop(sessionId);
        closeCollector();
        return status();
    }
    public synchronized EventIngress.Result simulate(LiveEvent event) { return engine.accept(event); }

    public synchronized RuntimeView replay(String sessionId) {
        var current = engine.status();
        if (!current.sessionId().equals(sessionId) || current.state() != RuntimeEngine.State.RUNNING)
            throw new SessionException("SESSION_MISMATCH", 409);
        if (!current.playback().ready()) throw new SessionException("PLAYER_NOT_READY", 409);
        String id = UUID.randomUUID().toString();
        // Original synthetic input, not copied from test resources or a platform recording.
        String data = """
            {"kind":"COMMENT","messageId":"%s-chat","roomId":"mock-room","userId":"demo-a","username":"模拟观众","content":"你好，大肥鱼"}
            {"kind":"GIFT","messageId":"%s-g1","roomId":"mock-room","userId":"demo-b","username":"模拟礼物观众","giftId":"heart","giftName":"小心心","comboId":"%s","cumulativeQuantity":1}
            {"kind":"GIFT","messageId":"%s-g3","roomId":"mock-room","userId":"demo-b","username":"模拟礼物观众","giftId":"heart","giftName":"小心心","comboId":"%s","cumulativeQuantity":3}
            {"kind":"GIFT","messageId":"%s-end","roomId":"mock-room","userId":"demo-b","username":"模拟礼物观众","giftId":"heart","giftName":"小心心","comboId":"%s","cumulativeQuantity":3}
            """.formatted(id,id,id,id,id,id,id);
        replaceCollector(data.getBytes(StandardCharsets.UTF_8));
        return status();
    }
    private void replaceCollector(byte[] input) {
        closeCollector();
        AtomicLong refusals = new AtomicLong(); rejected = refusals;
        collector = new MockCollector(() -> new ByteArrayInputStream(input));
        var current = engine.status();
        collector.start(current.roomId(), current.sessionId(), event -> {
            var result = engine.accept(event);
            if (result != EventIngress.Result.ACCEPTED && result != EventIngress.Result.DUPLICATE) refusals.incrementAndGet();
        });
    }
    @Scheduled(fixedDelay = 250)
    public synchronized void reconcile() {
        if (collector == null) return;
        if (engine.status().state() != RuntimeEngine.State.RUNNING) closeCollector();
        else if (collector.state() == CollectorPort.State.ENDED || collector.state() == CollectorPort.State.FAILED)
            engine.stop(engine.status().sessionId());
    }
    public synchronized RuntimeView status() {
        var s = engine.status(); var config = configs.snapshot().settings(); var p = s.playback();
        int capacity = config.synthesisQueueCapacity();
        var d = collector == null ? null : collector.diagnostics();
        return new RuntimeView(s.sessionId(),s.state().name(),s.mode(),s.conversationMode(),s.speechMode(),s.roomId(),
            s.savedConfigVersion(),s.appliedConfigVersion(),new RuntimeView.Queues(
                new RuntimeView.Queue(s.comments(),capacity),new RuntimeView.Queue(s.templates(),capacity),
                new RuntimeView.Queue(s.synthesis(),capacity),new RuntimeView.Queue(p.queued(),config.playbackQueueCapacity()),
                s.modelActive(),s.speechActive()),new RuntimeView.Player(p.connected(),p.ready(),p.playing(),p.generation()),
            new RuntimeView.Collector("MOCK",collector == null ? "STOPPED" : collector.state().name(),
                d == null ? 0 : d.count(RECEIVED),d == null ? 0 : d.count(EMITTED),d == null ? 0 : d.count(DUPLICATE),
                rejected.get(),d == null ? "" : d.lastError()),s.dropped(),s.failures());
    }
    private void closeCollector() { if (collector != null) collector.stop(); }
    @PreDestroy public synchronized void close() { engine.stop(engine.status().sessionId()); closeCollector(); }
}
