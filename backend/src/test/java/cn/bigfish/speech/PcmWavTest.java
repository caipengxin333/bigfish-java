package cn.bigfish.speech;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.*;
import java.time.Duration;
import java.util.Arrays;
import javax.sound.sampled.AudioSystem;
import static org.assertj.core.api.Assertions.*;
import static cn.bigfish.speech.SpeechFailure.Code.*;

class PcmWavTest {
    @Test void durationMatchesDecodedFrames() throws Exception {
        byte[] bytes = SpeechFixtures.wav(4800);
        var info = PcmWav.inspect(bytes);
        try (var decoded = AudioSystem.getAudioInputStream(new ByteArrayInputStream(bytes))) {
            assertThat(info.duration()).isEqualTo(Duration.ofMillis(300));
            assertThat(decoded.getFormat().getSampleRate()).isEqualTo(16000);
            assertThat(decoded.getFormat().getSampleSizeInBits()).isEqualTo(16);
            assertThat(decoded.getFormat().getChannels()).isEqualTo(1);
            assertThat(decoded.readAllBytes()).hasSize(info.pcmBytes());
            assertThat(decoded.getFrameLength()).isEqualTo(4800);
        }
    }
    @Test void rejectsEmptyTruncatedSilenceWrongFormatAndOversizedAudio() {
        reject(new byte[0], INVALID_WAV);
        reject(SpeechFixtures.wav(0), INVALID_WAV);
        byte[] valid = SpeechFixtures.wav(800);
        reject(Arrays.copyOf(valid, valid.length - 1), INVALID_WAV);
        byte[] wrongRate = valid.clone(); ByteBuffer.wrap(wrongRate).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 24000);
        reject(wrongRate, INVALID_WAV);
        byte[] compressed = valid.clone(); compressed[20] = 3; reject(compressed, INVALID_WAV);
        byte[] silence = valid.clone(); Arrays.fill(silence, 44, silence.length, (byte)0); reject(silence, AUDIO_SILENT);
        reject(SpeechFixtures.wav(16000 * 61), AUDIO_TOO_LONG);
        reject(new byte[PcmWav.MAX_BYTES + 1], AUDIO_TOO_LARGE);
        byte[] overflow = valid.clone(); ByteBuffer.wrap(overflow).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1);
        reject(overflow, INVALID_WAV);
        reject("{\"status\":40000001}".getBytes(java.nio.charset.StandardCharsets.UTF_8), INVALID_WAV);
    }
    private static void reject(byte[] bytes, SpeechFailure.Code code) {
        assertThatThrownBy(() -> PcmWav.inspect(bytes)).isInstanceOf(SpeechFailure.class).hasMessage("NLS_" + code);
    }
}
