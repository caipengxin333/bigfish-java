package cn.bigfish.speech;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Strict RIFF PCM validation. HTTP Content-Type alone is never evidence of audio. */
public final class PcmWav {
    public static final int MAX_BYTES = 2_000_000;
    public record Info(int sampleRate, int bits, int channels, int pcmBytes, Duration duration) {}
    private PcmWav() {}
    /** Wrap the complete NLS raw 16 kHz / 16-bit mono PCM response using its actual byte count. */
    public static byte[] fromPcm16Mono(byte[] pcm) {
        if (pcm.length > MAX_BYTES - 44) throw new SpeechFailure(SpeechFailure.Code.AUDIO_TOO_LARGE);
        if (pcm.length == 0 || (pcm.length & 1) != 0) throw invalid();
        // A WAV response is a different wire format; never play its header as PCM samples.
        if (pcm.length >= 12 && tag(pcm, 0, "RIFF") && tag(pcm, 8, "WAVE")) throw invalid();
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
        b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        b.putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return b.array();
    }
    public static Info inspect(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new SpeechFailure(SpeechFailure.Code.AUDIO_TOO_LARGE);
        if (bytes.length < 44) throw invalid();
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE") || Integer.toUnsignedLong(b.getInt(4)) + 8 != bytes.length) throw invalid();
        boolean format = false, data = false, nonzero = false;
        int pcmBytes = 0;
        for (int offset = 12; offset < bytes.length;) {
            if (bytes.length - offset < 8) throw invalid();
            long length = Integer.toUnsignedLong(b.getInt(offset + 4));
            long end = offset + 8L + length, padded = end + (length & 1);
            if (padded > bytes.length) throw invalid();
            int start = offset + 8;
            if (tag(bytes, offset, "fmt ")) {
                if (format || data || length < 16 || b.getShort(start) != 1 || b.getShort(start + 2) != 1 ||
                    b.getInt(start + 4) != 16000 || b.getInt(start + 8) != 32000 || b.getShort(start + 12) != 2 || b.getShort(start + 14) != 16) throw invalid();
                format = true;
            } else if (tag(bytes, offset, "data")) {
                if (!format || data || length == 0 || (length & 1) != 0) throw invalid();
                data = true; pcmBytes = (int)length;
                for (int i = start; i < end; i++) if (bytes[i] != 0) { nonzero = true; break; }
            }
            offset = (int)padded;
        }
        if (!format || !data) throw invalid();
        if (!nonzero) throw new SpeechFailure(SpeechFailure.Code.AUDIO_SILENT);
        Duration duration = Duration.ofNanos(pcmBytes * 1_000_000_000L / 32000);
        if (duration.compareTo(Duration.ofSeconds(60)) > 0) throw new SpeechFailure(SpeechFailure.Code.AUDIO_TOO_LONG);
        return new Info(16000, 16, 1, pcmBytes, duration);
    }
    private static boolean tag(byte[] bytes, int start, String tag) { return new String(bytes, start, 4, StandardCharsets.US_ASCII).equals(tag); }
    private static SpeechFailure invalid() { return new SpeechFailure(SpeechFailure.Code.INVALID_WAV); }
}
