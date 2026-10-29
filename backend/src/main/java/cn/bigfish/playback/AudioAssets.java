package cn.bigfish.playback;

import cn.bigfish.contract.SpeechPort.AudioAsset;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Opaque asset IDs only. Bytes are loaded off the ingress thread and never expose paths. */
public final class AudioAssets {
    public record Content(byte[] bytes, String mimeType) {}
    private record Entry(String sessionId, Instant expiresAt, Content content) {}
    private final Map<String,Entry> entries = new HashMap<>();
    private final Path root;
    private final Clock clock;
    private String sessionId = "";
    public AudioAssets(Path root, Clock clock) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
        this.clock = clock;
    }
    public Content load(AudioAsset asset) throws IOException {
        Path path = asset.path().toRealPath();
        if (!path.startsWith(root) || !Files.isRegularFile(path) || !"audio/wav".equals(asset.mimeType()))
            throw new IOException("UNSUPPORTED_AUDIO");
        // Bounded read also protects against a file growing after its size is checked.
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IOException("AUDIO_TOO_LARGE");
            return new Content(bytes, asset.mimeType());
        }
    }
    public synchronized void begin(String session) { entries.clear(); sessionId = session; }
    public synchronized boolean register(String id, String session, Instant expires, Content content) {
        prune();
        if (!sessionId.equals(session) || !expires.isAfter(clock.instant()) || entries.size() >= 128) return false;
        entries.put(id, new Entry(session, expires, content));
        return true;
    }
    public synchronized Optional<Content> get(String id) {
        prune();
        var entry = entries.get(id);
        return entry == null || !entry.sessionId().equals(sessionId) ? Optional.empty() : Optional.of(entry.content());
    }
    public synchronized void remove(String id) { entries.remove(id); }
    public synchronized void clear() { entries.clear(); }
    private void prune() { entries.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(clock.instant())); }
}
