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
    public ManagementController(ConfigStore configs) { this.configs = configs; }
    @GetMapping("/bootstrap")
    public Map<String,Object> bootstrap() {
        return Map.of("application", "BigFish Java", "version", "0.1.0-SNAPSHOT", "mode", "SCAFFOLD",
            "sessionState", "STOPPED", "capabilities", Map.of("configPersistence", true,
            "douyinCollector", false, "conversation", false, "speech", false, "playback", false));
    }
    @GetMapping("/config") public ConfigSnapshot config() { return configs.current(); }
    @PutMapping("/config") public ConfigSnapshot update(@Valid @RequestBody UpdateConfigRequest request) { return configs.update(request); }
}
