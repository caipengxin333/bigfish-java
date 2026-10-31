package cn.bigfish.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioSystem;
import static org.assertj.core.api.Assertions.*;

/** Explicit paid smoke only: one public sentence, one HTTP attempt, no automatic retries or playback. */
@EnabledIfSystemProperty(named = "bigfish.nls.live", matches = "true")
class AlibabaNlsLiveTest {
    @Test void oneOfficialSentenceHasDecodablePcmAndConsistentDuration() throws Exception {
        String configured = System.getProperty("bigfish.nls.audioDirectory");
        assertThat(configured).as("Set bigfish.nls.audioDirectory to this worktree's configured audio directory").isNotBlank();
        Path directory = Path.of(configured);
        assertThat(directory.isAbsolute()).as("Use an absolute worktree audio directory").isTrue();
        String appKey = System.getenv("BIGFISH_ALIYUN_NLS_APP_KEY");
        String token = System.getenv("BIGFISH_ALIYUN_NLS_TOKEN");
        String voice = System.getProperty("bigfish.nls.voice", "xiaoyun");
        assertThat(AlibabaNlsSpeech.VOICES.contains(voice)).as("Supported NLS voice").isTrue();
        // Assert only booleans, so a failure never renders a credential value.
        assertThat(appKey != null && !appKey.isBlank()).as("BIGFISH_ALIYUN_NLS_APP_KEY present").isTrue();
        assertThat(token != null && !token.isBlank()).as("BIGFISH_ALIYUN_NLS_TOKEN present").isTrue();
        var options = new AlibabaNlsSpeech.Options(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1), 1);
        try (var speech = new AlibabaNlsSpeech(URI.create("https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts"),
                directory, new ObjectMapper(), appKey, new StaticNlsTokenProvider(token, null), options)) {
            long start = System.nanoTime();
            var asset = speech.synthesize(UUID.randomUUID().toString(), "你好，这是大肥鱼语音合成测试。", voice, Duration.ofSeconds(10))
                .toCompletableFuture().get(11, TimeUnit.SECONDS);
            boolean retain = false;
            try {
                var info = PcmWav.inspect(Files.readAllBytes(asset.path()));
                try (var decoded = AudioSystem.getAudioInputStream(asset.path().toFile())) {
                    assertThat(decoded.readAllBytes()).hasSize(info.pcmBytes());
                    assertThat(decoded.getFrameLength()).isEqualTo(info.pcmBytes() / 2);
                    assertThat(decoded.getFormat().getSampleRate()).isEqualTo(16000);
                    assertThat(decoded.getFormat().getSampleSizeInBits()).isEqualTo(16);
                    assertThat(decoded.getFormat().getChannels()).isEqualTo(1);
                }
                assertThat(info.duration()).isEqualTo(asset.duration());
                assertThat(speech.diagnostics().requests()).isEqualTo(1);
                System.out.printf("NLS live: voice=%s requests=%d elapsedMs=%d bytes=%d sampleRate=%d durationMs=%d decode=PASS%n",
                    voice, speech.diagnostics().requests(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                    Files.size(asset.path()), info.sampleRate(), info.duration().toMillis());
                retain = Boolean.getBoolean("bigfish.nls.keepSample");
                if (retain)
                    System.out.println("NLS listening sample retained locally: " + asset.path());
            } finally {
                if (!retain) speech.release(asset);
            }
        }
    }
}
