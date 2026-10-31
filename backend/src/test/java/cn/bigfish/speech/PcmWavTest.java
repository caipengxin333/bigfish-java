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
    @Test void rawPcmIsWrappedWithExactLengthsAndDecodedWithoutSampleChanges() throws Exception {
        byte[] pcm = SpeechFixtures.pcm(34399);
        byte[] wav = PcmWav.fromPcm16Mono(pcm);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(header.getInt(4)).isEqualTo(wav.length - 8);
        assertThat(header.getInt(40)).isEqualTo(pcm.length);
        var info = PcmWav.inspect(wav);
        try (var decoded = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            assertThat(decoded.getFrameLength()).isEqualTo(34399);
            assertThat(decoded.readAllBytes()).isEqualTo(pcm);
            assertThat(info.duration()).isEqualTo(Duration.ofNanos(34399L * 1_000_000_000 / 16000));
        }
    }
    @Test void rawPcmRejectsPartialFramesContainersAndSizeOverflow() {
        for (byte[] bytes : new byte[][]{new byte[0], new byte[]{1}, SpeechFixtures.wav(800)})
            assertThatThrownBy(() -> PcmWav.fromPcm16Mono(bytes)).hasMessage("NLS_INVALID_WAV");
        assertThatThrownBy(() -> PcmWav.fromPcm16Mono(new byte[PcmWav.MAX_BYTES - 42])).hasMessage("NLS_AUDIO_TOO_LARGE");
        reject(PcmWav.fromPcm16Mono(new byte[1600]), AUDIO_SILENT);
        reject(PcmWav.fromPcm16Mono(SpeechFixtures.pcm(16000 * 61)), AUDIO_TOO_LONG);
    }
    @Test void providerWavWithMismatchedLengthRemainsInvalid() {
        byte[] wav = SpeechFixtures.wav(34399);
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 230436).putInt(40, 230400);
        reject(wav, INVALID_WAV);
    }
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
