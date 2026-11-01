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

/** Independent opt-in: at most one CreateToken request and one short PCM synthesis, no retries. */
@EnabledIfSystemProperty(named="bigfish.nls.ramLive", matches="true")
class AlibabaNlsRamLiveTest {
    @Test void oneIssuanceAndOneSentence() throws Exception {
        String configured = System.getProperty("bigfish.nls.audioDirectory");
        assertThat(configured).as("Set this worktree's absolute audio directory").isNotBlank();
        Path directory = Path.of(configured);
        assertThat(directory.isAbsolute()).isTrue();
        assertThat(NlsTokenProviders.mode(System.getenv("BIGFISH_ALIYUN_NLS_TOKEN_MODE"))).isEqualTo(NlsTokenProviders.Mode.RAM);
        String appKey = System.getenv("BIGFISH_ALIYUN_NLS_APP_KEY");
        assertThat(appKey != null && !appKey.isBlank()).as("NLS AppKey present").isTrue();
        var mapper = new ObjectMapper();
        try (var provider = NlsTokenProviders.create(true,System::getenv,mapper);
             var speech = new AlibabaNlsSpeech(URI.create("https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts"),
                 directory,mapper,appKey,provider,
                 new AlibabaNlsSpeech.Options(Duration.ofSeconds(3),Duration.ofSeconds(2),Duration.ofSeconds(1),1))) {
            long start = System.nanoTime();
            var asset = speech.synthesize(UUID.randomUUID().toString(),"你好，这是语音续期接入测试。","zhigui",Duration.ofSeconds(10))
                .toCompletableFuture().get(11,TimeUnit.SECONDS);
            try {
                var info = PcmWav.inspect(Files.readAllBytes(asset.path()));
                try (var decoded = AudioSystem.getAudioInputStream(asset.path().toFile())) {
                    assertThat(decoded.readAllBytes()).hasSize(info.pcmBytes());
                    assertThat(decoded.getFrameLength()).isEqualTo(info.pcmBytes()/2);
                    assertThat(decoded.getFormat().getSampleRate()).isEqualTo(16000);
                }
                assertThat(info.duration()).isEqualTo(asset.duration());
                assertThat(provider.snapshot().attempts()).isEqualTo(1);
                assertThat(speech.diagnostics().requests()).isEqualTo(1);
                System.out.printf("NLS RAM smoke: tokenRequests=1 synthesisRequests=1 voice=zhigui elapsedMs=%d expiresAt=%s bytes=%d durationMs=%d decode=PASS%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start), provider.snapshot().expiresAt(), Files.size(asset.path()), info.duration().toMillis());
            } finally { speech.release(asset); }
        }
    }
}
