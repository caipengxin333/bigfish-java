package cn.bigfish.speech;

import cn.bigfish.contract.SpeechPort;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

/** A 300 ms quiet tone, NOT human speech. One reusable file keeps disk usage constant. */
public final class LocalTestSpeech implements SpeechPort {
    private final AudioAsset asset;
    public LocalTestSpeech(Path directory) throws java.io.IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("local-test-tone-v1.wav");
        if (Files.isSymbolicLink(path)) throw new java.io.IOException("Invalid test audio path");
        Files.write(path, wav(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        asset = new AudioAsset(path, "audio/wav", Duration.ofMillis(300));
    }
    @Override public CompletionStage<AudioAsset> synthesize(String id, String text, String voice, Duration deadline) {
        if (deadline.isNegative() || deadline.isZero()) return CompletableFuture.failedFuture(new TimeoutException());
        return CompletableFuture.completedFuture(asset);
    }
    @Override public void cancel(String id) {}
    private static byte[] wav() {
        int samples = 4800;
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt(36 + samples * 2).put(new byte[]{'W','A','V','E','f','m','t',' '});
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put(new byte[]{'d','a','t','a'}).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1, Math.min(i, samples - 1 - i) / 160.0);
            b.putShort((short)(1200 * envelope * StrictMath.sin(2 * StrictMath.PI * 440 * i / 16000)));
        }
        return b.array();
    }
}
