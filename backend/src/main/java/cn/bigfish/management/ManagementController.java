package cn.bigfish.management;

import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.configuration.ProviderServices;
import cn.bigfish.contract.*;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1")
public class ManagementController {
    private final ConfigStore configs;
    private final RuntimeEngine runtime;
    private final SessionService sessions;
    private final ProviderServices providers;
    public ManagementController(ConfigStore configs, RuntimeEngine runtime, SessionService sessions, ProviderServices providers) {
        this.configs = configs; this.runtime = runtime; this.sessions = sessions; this.providers=providers;
    }
    @GetMapping("/bootstrap")
    public Map<String,Object> bootstrap() {
        return Map.of("application", "BigFish Java", "version", "0.1.0-SNAPSHOT", "mode", "READY",
            "sessionState", sessions.status().state(), "capabilities", Map.of("configPersistence", true,
            "douyinCollector", true, "conversation", true, "speech", providers.status().aliyun().enabled(), "playback", true,
            "mockMode", true, "localTestSpeech", true));
    }
    @GetMapping("/config") public ConfigSnapshot config() { return configs.current(); }
    @PutMapping("/config") public ConfigSnapshot update(@Valid @RequestBody UpdateConfigRequest request) { return configs.update(request); }
    @GetMapping("/providers") public ProvidersView providers() { return providers.status(sessions.douyinStatus()); }
    @PostMapping("/providers/deepseek/check") public ProvidersView.ModelCheck checkModel() {
        return providers.checkModel(configs.snapshot().settings().model());
    }
    @GetMapping("/runtime") public RuntimeView status() { return sessions.status(); }
    /** Fixed latest-20 window; polling only reads local state and never calls a supplier. */
    @GetMapping("/runtime/diagnostics") public RuntimeDiagnosticsView diagnostics() {
        var view=sessions.diagnostics(); var trace=view.trace();
        var recent=trace.history().stream().skip(Math.max(0,trace.history().size()-20)).map(ManagementController::traceView).toList();
        return new RuntimeDiagnosticsView(trace.sessionId(),view.runtime().state().name(),trace.capturedAt(),
            view.waiting().stream().map(Enum::name).toList(),trace.lastReceivedAt(),trace.lastModelSuccessAt(),
            trace.lastSpeechSuccessAt(),trace.lastPlaybackSuccessAt(),trace.lastFailure()==null ? null : traceView(trace.lastFailure()),
            recent,trace.taskEvictions(),trace.historyEvictions(),view.audioAssets(),view.dedupEntries(),
            workerView(view.modelWorker()),workerView(view.speechWorker()));
    }
    private static RuntimeDiagnosticsView.Trace traceView(RuntimeDiagnostics.Trace trace) {
        return new RuntimeDiagnosticsView.Trace(trace.sequence(),trace.taskId(),trace.eventType()==null ? "" : trace.eventType().name(),
            trace.configVersion(),trace.stage().name(),trace.reason().name(),trace.at(),trace.previousStageMs(),trace.taskElapsedMs());
    }
    private static RuntimeDiagnosticsView.Worker workerView(RuntimeEngine.WorkerStatus worker) {
        return new RuntimeDiagnosticsView.Worker(worker.threads(),worker.active(),worker.queued(),worker.completed());
    }
    @PostMapping("/sessions") public ResponseEntity<RuntimeView> start(@Valid @RequestBody StartSessionRequest request) {
        return ResponseEntity.status(201).body(sessions.start(request));
    }
    @PostMapping("/sessions/{sessionId}/stop") public RuntimeView stop(@PathVariable String sessionId) { return sessions.stop(sessionId); }
    @PostMapping("/sessions/{sessionId}/replay") public ResponseEntity<RuntimeView> replay(@PathVariable String sessionId) {
        return ResponseEntity.accepted().body(sessions.replay(sessionId));
    }
    @PostMapping("/events/simulate") public ResponseEntity<?> simulate(@RequestBody LiveEvent event) {
        var result = sessions.simulate(event);
        int code = switch (result) {
            case ACCEPTED -> 202; case DUPLICATE -> 200; case REJECTED_FULL -> 429;
            case REJECTED_SESSION -> 409; case INVALID -> 400;
        };
        return ResponseEntity.status(code).body(Map.of("result",result));
    }
}
