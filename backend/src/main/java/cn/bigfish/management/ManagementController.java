package cn.bigfish.management;

import cn.bigfish.configuration.ConfigStore;
import cn.bigfish.contract.*;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ManagementController {
    private final ConfigStore configs;
    private final RuntimeEngine runtime;
    public ManagementController(ConfigStore configs, RuntimeEngine runtime) { this.configs = configs; this.runtime = runtime; }
    @GetMapping("/bootstrap")
    public Map<String,Object> bootstrap() {
        return Map.of("application", "BigFish Java", "version", "0.1.0-SNAPSHOT", "mode", "SCAFFOLD",
            "sessionState", runtime.status().state().name(), "capabilities", Map.of("configPersistence", true,
            "douyinCollector", false, "conversation", true, "speech", false, "playback", true,
            "mockMode", true, "localTestSpeech", true));
    }
    @GetMapping("/config") public ConfigSnapshot config() { return configs.current(); }
    @PutMapping("/config") public ConfigSnapshot update(@Valid @RequestBody UpdateConfigRequest request) { return configs.update(request); }
}
