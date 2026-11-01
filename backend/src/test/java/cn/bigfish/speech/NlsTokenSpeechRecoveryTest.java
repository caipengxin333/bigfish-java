package cn.bigfish.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static cn.bigfish.speech.AlibabaNlsTokenClientTest.failure;
import static cn.bigfish.speech.SpeechFailure.Code.*;

class NlsTokenSpeechRecoveryTest {
    @TempDir Path directory;
    @Test void tokenExpiringDuringRetryCooldownIsNeverResent() throws Exception {
        Instant now = Instant.now();
        AtomicReference<Instant> time = new AtomicReference<>(now);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId ignored) { return this; }
            public Instant instant() { return time.get(); }
        };
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/tts", x -> {
            calls.incrementAndGet(); time.set(now.plusSeconds(2));
            try { x.sendResponseHeaders(429,-1); } finally { x.close(); }
        });
        server.start();
        try (var speech = new AlibabaNlsSpeech(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/tts"),
                directory,new ObjectMapper(),"fixture-appkey",new StaticNlsTokenProvider("fixture-expiring",now.plusSeconds(1),clock),
                new AlibabaNlsSpeech.Options(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(50),2))) {
            failure(speech.synthesize("expiry","测试","zhigui",Duration.ofSeconds(3)), TOKEN_EXPIRED);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(speech.diagnostics().outstandingFiles()).isZero();
        } finally { server.stop(0); }
    }
    @Test void nextTaskRefreshesRejectedTokenWithoutReplayingPreviouslySuccessfulAudio() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        AtomicInteger issues = new AtomicInteger(), syntheses = new AtomicInteger();
        List<String> usedTokens = new CopyOnWriteArrayList<>();
        server.createContext("/", x -> {
            int number = issues.incrementAndGet();
            byte[] bytes = ("{\"Token\":{\"Id\":\"fixture-generation-"+number+"\",\"ExpireTime\":"+Instant.now().plusSeconds(3600).getEpochSecond()+"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try { x.getResponseHeaders().add("Content-Type","application/json"); x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes); }
            finally { x.close(); }
        });
        server.createContext("/tts", x -> {
            int number = syntheses.incrementAndGet(); usedTokens.add(x.getRequestHeaders().getFirst("X-NLS-Token"));
            try {
                if (number == 2) { x.sendResponseHeaders(401,-1); return; }
                var request = new ObjectMapper().readTree(x.getRequestBody().readAllBytes());
                assertThat(request.path("format").asText()).isEqualTo("pcm");
                byte[] pcm = SpeechFixtures.pcm(34399);
                x.getResponseHeaders().add("Content-Type","audio/mpeg"); x.sendResponseHeaders(200,pcm.length); x.getResponseBody().write(pcm);
            } finally { x.close(); }
        });
        server.start();
        URI root = URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
        var mapper = new ObjectMapper();
        try (var client = new AlibabaNlsTokenClient(root,"fixture-access-id","fixture-access-secret",mapper,Clock.systemUTC(),()->UUID.randomUUID().toString());
             var provider = NlsTokenProviders.refreshing(client);
             var speech = new AlibabaNlsSpeech(root.resolve("tts"),directory,mapper,"fixture-appkey",provider,
                 new AlibabaNlsSpeech.Options(Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofMillis(50),1))) {
            assertThat(issues.get()).isZero();
            var first = speech.synthesize("task-one","测试一","zhigui",Duration.ofSeconds(3)).toCompletableFuture().get(4,TimeUnit.SECONDS);
            byte[] saved = Files.readAllBytes(first.path());
            var old = provider.acquire(Duration.ofSeconds(1)).toCompletableFuture().join();
            failure(speech.synthesize("task-two","测试二","zhigui",Duration.ofSeconds(3)), TOKEN_REJECTED);
            var next = speech.synthesize("task-three","测试三","zhigui",Duration.ofSeconds(3)).toCompletableFuture().get(4,TimeUnit.SECONDS);
            provider.rejected(old); // Late response from the previous generation cannot poison the replacement.
            assertThat(provider.status()).isEqualTo(NlsTokenProvider.Status.ACCEPTED);
            assertThat(issues.get()).isEqualTo(2); assertThat(syntheses.get()).isEqualTo(3);
            assertThat(usedTokens).containsExactly("fixture-generation-1","fixture-generation-1","fixture-generation-2");
            assertThat(Files.readAllBytes(first.path())).isEqualTo(saved);
            try (var decoded = javax.sound.sampled.AudioSystem.getAudioInputStream(next.path().toFile())) {
                assertThat(decoded.getFrameLength()).isEqualTo(34399);
                assertThat(decoded.readAllBytes()).isEqualTo(SpeechFixtures.pcm(34399));
            }
            assertThat(provider.snapshot().expiresAt()).isNotNull();
            assertThat(provider.snapshot().toString()).doesNotContain("fixture-generation", "fixture-access");
            speech.release(first); speech.release(next);
            assertThat(speech.diagnostics().outstandingFiles()).isZero();
        } finally { server.stop(0); workers.shutdownNow(); }
    }
}
