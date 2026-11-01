package cn.bigfish.configuration;

import cn.bigfish.conversation.*;
import cn.bigfish.management.RuntimeEngine;
import cn.bigfish.playback.*;
import cn.bigfish.speech.LocalTestSpeech;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableScheduling
public class CoreConfiguration {
    @Bean SessionCollectors sessionCollectors() { return new SessionCollectors(); }
    @Bean Clock runtimeClock() { return Clock.systemUTC(); }
    @Bean AudioAssets audioAssets(@Value("${bigfish.data-dir}") String root, Clock clock) throws Exception {
        return new AudioAssets(Path.of(root,"audio"),clock);
    }
    @Bean LocalTestSpeech localTestSpeech(@Value("${bigfish.data-dir}") String root) throws Exception {
        return new LocalTestSpeech(Path.of(root,"audio"));
    }
    @Bean PlaybackCoordinator playbackCoordinator(Clock clock, AudioAssets assets) { return new PlaybackCoordinator(clock,assets); }
    @Bean RuntimeEngine runtimeEngine(ConfigStore configs, LocalTestSpeech speech, PlaybackCoordinator playback,
                                      AudioAssets assets, Clock clock, Environment env) {
        return new RuntimeEngine(configs,new MockConversation(),() -> DeepSeekConversation.create(
            env.getProperty("BIGFISH_ALLOW_PAID",Boolean.class,false),env.getProperty("BIGFISH_DEEPSEEK_API_KEY")),
            speech,playback,assets,clock,Duration.ofSeconds(20),Duration.ofSeconds(5));
    }
    @Bean StageSocketHandler stageSocketHandler(PlaybackCoordinator playback, ObjectMapper mapper) {
        return new StageSocketHandler(playback,mapper);
    }
    @Configuration
    @EnableWebSocket
    static class StageWebSocketConfiguration implements WebSocketConfigurer {
        private final StageSocketHandler handler;
        StageWebSocketConfiguration(StageSocketHandler handler) { this.handler = handler; }
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(handler,"/ws/stage"); // Spring's default same-origin policy is intentional.
        }
    }
}
