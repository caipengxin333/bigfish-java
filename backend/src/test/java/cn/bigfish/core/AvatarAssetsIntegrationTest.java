package cn.bigfish.core;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "BIGFISH_ALLOW_PAID=false")
@AutoConfigureMockMvc
class AvatarAssetsIntegrationTest {
    static final Path BASE, ASSETS, MODEL;
    static final long LARGE_SIZE = 30L * 1024 * 1024;
    static {
        try {
            BASE = Files.createTempDirectory("bigfish-avatar-test-");
            ASSETS = Files.createDirectories(BASE.resolve("public-assets"));
            MODEL = Files.createDirectories(ASSETS.resolve("models/BigFish"));
            Files.createDirectories(ASSETS.resolve("sdk"));
            Files.createDirectories(BASE.resolve("runtime"));
            Files.writeString(MODEL.resolve("BigFish.model3.json"), "{\"Version\":3,\"synthetic\":true}");
            Files.writeString(MODEL.resolve("大肥鱼.moc3"), "synthetic-moc");
            Files.writeString(MODEL.resolve("大肥鱼.physics3.json"), "{}");
            Files.writeString(MODEL.resolve("笑脸.exp3.json"), "{}");
            Files.writeString(ASSETS.resolve("sdk/live2dcubismcore.min.js"), "/* synthetic test; no SDK */");
            try (var channel = FileChannel.open(MODEL.resolve("texture.png"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(LARGE_SIZE - 1); channel.write(ByteBuffer.wrap(new byte[]{42}));
            }
            Files.writeString(MODEL.resolve("config.json"), "synthetic-private-config");
            Files.writeString(MODEL.resolve("secret.key"), "synthetic-private-key");
            Files.writeString(MODEL.resolve("app.db"), "synthetic-private-database");
            Files.writeString(MODEL.resolve("audio.wav"), "synthetic-private-audio");
            Files.writeString(ASSETS.resolve("sdk/other.js"), "synthetic-disallowed-script");
            Path outside = Files.createDirectories(BASE.resolve("outside"));
            Files.writeString(outside.resolve("outside.model3.json"), "synthetic-outside-must-not-escape");
            Files.createSymbolicLink(MODEL.resolve("escape.model3.json"), outside.resolve("outside.model3.json"));
            Files.createSymbolicLink(MODEL.resolve("linked"), outside);
            Files.createSymbolicLink(MODEL.resolve("config-alias.model3.json"), MODEL.resolve("config.json"));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("bigfish.avatar.assets-dir", ASSETS::toString);
        r.add("bigfish.data-dir", () -> BASE.resolve("runtime").toString());
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + BASE.resolve("runtime/test.db"));
    }
    @LocalServerPort int port;
    @Autowired MockMvc mvc;
    final HttpClient client = HttpClient.newHttpClient();
    @AfterEach void close() { client.shutdownNow(); }

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }
    HttpResponse<String> read(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void servesExpectedMimeTypesChineseNamesAndHeadWithoutABody() throws Exception {
        for (var asset : Map.of("models/BigFish/BigFish.model3.json", "application/json",
                "models/BigFish/大肥鱼.moc3", "application/octet-stream",
                "models/BigFish/大肥鱼.physics3.json", "application/json",
                "models/BigFish/笑脸.exp3.json", "application/json",
                "sdk/live2dcubismcore.min.js", "text/javascript").entrySet()) {
            var response = read("/avatar-assets/" + asset.getKey());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type")).contains(asset.getValue());
            assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            var head = client.send(request("/avatar-assets/" + asset.getKey()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(head.statusCode()).isEqualTo(200); assertThat(head.body()).isEmpty();
            assertThat(head.headers().firstValue("Content-Length")).isEqualTo(response.headers().firstValue("Content-Length"));
        }
    }

    @Test void streamsThirtyMiBTextureAndSupportsByteRanges() throws Exception {
        var response = client.send(request("/avatar-assets/models/BigFish/texture.png").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(response.headers().firstValueAsLong("Content-Length").orElseThrow()).isEqualTo(LARGE_SIZE);
        try (var body = response.body()) { assertThat(body.transferTo(OutputStream.nullOutputStream())).isEqualTo(LARGE_SIZE); }
        var range = client.send(request("/avatar-assets/models/BigFish/texture.png").header("Range", "bytes=" + (LARGE_SIZE - 1) + "-")
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(range.statusCode()).isEqualTo(206); assertThat(range.body()).containsExactly((byte) 42);
    }

    @Test void conditionalCacheDetectsSameSecondReplacementWithoutReadingTheWholeFile() throws Exception {
        Path asset = MODEL.resolve("replace.model3.json");
        Instant second = Instant.parse("2026-11-02T00:00:00Z");
        Files.writeString(asset, "{\"value\":1}"); Files.setLastModifiedTime(asset, FileTime.from(second.plusMillis(100)));
        var first = read("/avatar-assets/models/BigFish/replace.model3.json");
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(first.headers().firstValue("Cache-Control").orElseThrow()).contains("no-cache", "private");
        assertThat(first.headers().firstValue("Last-Modified")).isEmpty();
        var unchanged = client.send(request("/avatar-assets/models/BigFish/replace.model3.json").header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(unchanged.statusCode()).isEqualTo(304); assertThat(unchanged.body()).isEmpty();
        Files.writeString(asset, "{\"value\":2}"); Files.setLastModifiedTime(asset, FileTime.from(second.plusMillis(200)));
        var updated = client.send(request("/avatar-assets/models/BigFish/replace.model3.json").header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(updated.statusCode()).isEqualTo(200); assertThat(updated.body()).contains("2");
        assertThat(updated.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);
    }

    @Test void missingFilesDirectoriesAndNonAssetFilesReturn404RatherThanSpa() throws Exception {
        for (String path : List.of("", "/", "/models/", "/models/BigFish/", "/models/BigFish/missing.model3.json",
                "/models/BigFish/config.json", "/models/BigFish/secret.key", "/models/BigFish/app.db", "/models/BigFish/audio.wav",
                "/sdk/other.js", "/index.html", "/application.yml")) {
            var response = read("/avatar-assets" + path);
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).containsAnyOf("no-cache", "no-store");
            assertThat(response.body()).doesNotContain("synthetic-private", "<div id=\"app\"", ASSETS.toString());
        }
        assertThat(read("/avatar-assets/models/BigFish/missing.model3.json?root=" + BASE).statusCode()).isEqualTo(404);
    }

    @Test void traversalEncodedAbsolutePathsAndLinksCannotEscapeThePublicTree() throws Exception {
        for (String path : List.of("/models/BigFish/../../../outside/outside.model3.json",
                "/models/BigFish/%2e%2e/%2e%2e/%2e%2e/outside/outside.model3.json",
                "/models/BigFish/%252e%252e/outside.model3.json", "/models/BigFish/..%5coutside.model3.json",
                "//etc/passwd", "/C:/outside.model3.json", "/models/BigFish/%00outside.model3.json",
                "/models/BigFish/escape.model3.json", "/models/BigFish/linked/outside.model3.json",
                "/models/BigFish/config-alias.model3.json", "/models/BigFish/../BigFish/BigFish.model3.json",
                "/models/BigFish/./BigFish.model3.json", "/models/BigFish/BigFish.model3.json;ignored")) {
            var response = read("/avatar-assets" + path);
            assertThat(response.statusCode()).as(path).isIn(400, 404);
            assertThat(response.body()).doesNotContain("synthetic-outside", "synthetic-private");
        }
        // The servlet container normalizes this to /api/v1/runtime; the raw URI guard must still reject it.
        assertThat(read("/avatar-assets/../api/v1/runtime").statusCode()).isEqualTo(404);
        assertThat(read("/%61vatar-assets/../api/v1/runtime").statusCode()).isEqualTo(404);
    }

    @Test void localSymlinkReplacementIsCheckedAgainAndMutationsAreRejected() throws Exception {
        Path swap = MODEL.resolve("swap.model3.json");
        Files.writeString(swap, "{\"synthetic\":true}");
        assertThat(read("/avatar-assets/models/BigFish/swap.model3.json").statusCode()).isEqualTo(200);
        Files.move(swap, MODEL.resolve("before-swap.model3.json"));
        Files.createSymbolicLink(swap, BASE.resolve("outside/outside.model3.json"));
        assertThat(read("/avatar-assets/models/BigFish/swap.model3.json").statusCode()).isEqualTo(404);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            var response = client.send(request("/avatar-assets/models/BigFish/BigFish.model3.json")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(response.headers().firstValue("Allow")).contains("GET, HEAD");
        }
        assertThat(Files.readString(MODEL.resolve("BigFish.model3.json"))).contains("synthetic");
    }

    @Test void existingRoutesRemainIndependentAndAssetReadsDoNotStartASession() throws Exception {
        for (String path : List.of("/api/v1/config", "/api/v1/providers", "/api/v1/bootstrap", "/actuator/health")) {
            assertThat(read(path).statusCode()).as(path).isEqualTo(200);
        }
        assertThat(read("/api/v1/runtime").body()).contains("\"state\":\"STOPPED\"");
        assertThat(read("/api/v1/audio/unknown").statusCode()).isEqualTo(404);
        mvc.perform(get("/admin")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/stage")).andExpect(forwardedUrl("/index.html"));
    }
}
