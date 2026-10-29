package cn.bigfish.management;

import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.contract.*;
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
    public ManagementController(ConfigStore configs, RuntimeEngine runtime, SessionService sessions) {
        this.configs = configs; this.runtime = runtime; this.sessions = sessions;
    }
    @GetMapping("/bootstrap")
    public Map<String,Object> bootstrap() {
        return Map.of("application", "BigFish Java", "version", "0.1.0-SNAPSHOT", "mode", "READY",
            "sessionState", runtime.status().state().name(), "capabilities", Map.of("configPersistence", true,
            "douyinCollector", false, "conversation", true, "speech", false, "playback", true,
            "mockMode", true, "localTestSpeech", true));
    }
    @GetMapping("/config") public ConfigSnapshot config() { return configs.current(); }
    @PutMapping("/config") public ConfigSnapshot update(@Valid @RequestBody UpdateConfigRequest request) { return configs.update(request); }
    @GetMapping("/runtime") public RuntimeView status() { return sessions.status(); }
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
