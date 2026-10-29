package cn.bigfish.playback;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AudioController {
    private final AudioAssets assets;
    public AudioController(AudioAssets assets) { this.assets = assets; }
    @GetMapping("/api/v1/audio/{assetId}")
    public ResponseEntity<byte[]> audio(@PathVariable String assetId) {
        if (!assetId.matches("[a-f0-9-]{36}")) return ResponseEntity.notFound().build();
        return assets.get(assetId).map(content -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").contentType(MediaType.parseMediaType(content.mimeType()))
            .body(content.bytes())).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
