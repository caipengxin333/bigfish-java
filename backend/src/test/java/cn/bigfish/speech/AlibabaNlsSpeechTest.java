package cn.bigfish.speech;

import cn.bigfish.contract.SpeechPort.AudioAsset;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static cn.bigfish.speech.SpeechFailure.Code.*;

class AlibabaNlsSpeechTest {
    @TempDir Path directory;
    private HttpServer server;
    private ExecutorService serverWorkers;
    private AlibabaNlsSpeech speech;
    private StaticNlsTokenProvider tokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private final AtomicInteger received = new AtomicInteger();
    private final byte[] audio = SpeechFixtures.wav(4800);
    private static final Duration BUDGET = Duration.ofSeconds(3);
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverWorkers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(serverWorkers);
        handler.set(x -> respond(x, 200, "audio/mpeg", audio));
        server.createContext("/tts", x -> {
            received.incrementAndGet();
            try { handler.get().handle(x); } catch (IOException ignored) { } finally { x.close(); }
        });
        server.start();
        tokens = new StaticNlsTokenProvider("local-test-token-only", null);
        speech = adapter(tokens, 2);
    }
    private AlibabaNlsSpeech adapter(NlsTokenProvider provider, int attempts) {
        return new AlibabaNlsSpeech(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tts"), directory,
            mapper, "local-test-appkey-only", provider,
            new AlibabaNlsSpeech.Options(Duration.ofMillis(300), Duration.ofMillis(250), Duration.ofMillis(80), attempts));
    }
    @AfterEach void stop() {
        if (speech != null) speech.close();
        if (server != null) server.stop(0);
        if (serverWorkers != null) serverWorkers.shutdownNow();
    }
    @Test void sendsUtf8JsonWithHeaderCredentialAndProducesOwnedDecodableWav() throws Exception {
        AtomicReference<String> method = new AtomicReference<>(), token = new AtomicReference<>(), query = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        handler.set(x -> {
            method.set(x.getRequestMethod()); token.set(x.getRequestHeaders().getFirst("X-NLS-Token"));
            query.set(x.getRequestURI().getRawQuery()); body.set(x.getRequestBody().readAllBytes());
            respond(x, 200, "audio/mpeg", audio);
        });
        AudioAsset result = get("ok", "你好 + & %，大肥鱼。");
        assertThat(method.get()).isEqualTo("POST"); assertThat(query.get()).isNull();
        assertThat(token.get()).isEqualTo("local-test-token-only");
        var sent = mapper.readTree(body.get());
        assertThat(sent.path("text").asText()).isEqualTo("你好 + & %，大肥鱼。");
        assertThat(sent.path("format").asText()).isEqualTo("wav");
        assertThat(sent.path("sample_rate").asInt()).isEqualTo(16000);
        assertThat(sent.path("voice").asText()).isEqualTo("xiaoyun");
        assertThat(sent.has("token")).isFalse();
        assertThat(result.mimeType()).isEqualTo("audio/wav");
        assertThat(result.duration()).isEqualTo(Duration.ofMillis(300));
        assertThat(result.path().getParent()).isEqualTo(directory.toRealPath());
        try (var decoded = javax.sound.sampled.AudioSystem.getAudioInputStream(result.path().toFile())) {
            assertThat(decoded.getFrameLength()).isEqualTo(4800);
            assertThat(decoded.readAllBytes()).hasSize(9600);
        }
        assertThat(tokens.status()).isEqualTo(NlsTokenProvider.Status.ACCEPTED);
        assertThat(speech.release(result)).isTrue(); assertThat(Files.exists(result.path())).isFalse();
        assertThat(speech.release(result)).isFalse();
    }
    @Test void rejectsBadVoiceLongTextAndInvalidInputBeforeNetwork() throws Exception {
        failure(speech.synthesize("edge", "你好", "zh-CN-XiaoyiNeural", BUDGET), UNSUPPORTED_VOICE);
        failure(speech.synthesize("long", "鱼".repeat(301), "xiaoyun", BUDGET), TEXT_TOO_LONG);
        failure(speech.synthesize("../bad", "你好", "xiaoyun", BUDGET), INVALID_TASK);
        failure(speech.synthesize("blank", " ", "xiaoyun", BUDGET), INVALID_TEXT);
        failure(speech.synthesize("surrogate", "\uD800", "xiaoyun", BUDGET), INVALID_TEXT);
        failure(speech.synthesize("ssml", "<speak>你好</speak>", "xiaoyun", BUDGET), INVALID_TEXT);
        failure(speech.synthesize("deadline", "你好", "xiaoyun", Duration.ZERO), DEADLINE_EXCEEDED);
        assertThat(received.get()).isZero();
        speech.release(get("boundary", "鱼".repeat(300)));
        assertThat(received.get()).isEqualTo(1);
    }
    @Test void authFailuresAreNotRetriedOrExposedAndFreshTokenRecovers() throws Exception {
        handler.set(x -> respond(x, 401, "application/json", "private-token-error-body".getBytes()));
        failure(say("auth"), TOKEN_REJECTED);
        awaitIdle();
        failure(say("auth-again"), TOKEN_REJECTED);
        assertThat(received.get()).isEqualTo(1);
        assertThat(tokens.status()).isEqualTo(NlsTokenProvider.Status.REJECTED);
        speech.close();
        speech = adapter(new StaticNlsTokenProvider("replacement-test-token", null), 2);
        handler.set(x -> respond(x, 200, "audio/mpeg", audio));
        speech.release(get("recovered", "你好"));
        assertThat(received.get()).isEqualTo(2);
        assertThat(speech.diagnostics().outstandingFiles()).isZero();
    }
    @Test void forbiddenAndJsonTokenExpiryAreNotRetried() throws Exception {
        for (int status : new int[]{403, 200}) {
            handler.set(x -> respond(x, status, "application/json", "{\"status\":40000001,\"message\":\"private\"}".getBytes()));
            failure(say("forbidden-" + status), TOKEN_REJECTED);
            awaitIdle();
            speech.close(); speech = adapter(new StaticNlsTokenProvider("replacement-test-token", null), 2);
        }
        assertThat(received.get()).isEqualTo(2); assertNoWavs();
    }
    @Test void retries429And503OnlyWithinBudgetAndCapsAttempts() throws Exception {
        handler.set(x -> {
            if (received.get() == 1) respond(x, 429, "application/json", new byte[0]);
            else respond(x, 200, "audio/wav", audio);
        });
        speech.release(get("rate-recovery", "你好")); awaitIdle();
        assertThat(received.get()).isEqualTo(2);
        handler.set(x -> respond(x, 503, "application/json", new byte[0]));
        failure(say("unavailable"), PROVIDER_UNAVAILABLE);
        assertThat(received.get()).isEqualTo(4);
        failure(say("cooling"), COOLDOWN);
        Thread.sleep(100);
        handler.set(x -> respond(x, 200, "audio/mpeg", audio));
        speech.release(get("after-cooldown", "你好"));
    }
    @Test void retryAfterNeverExtendsDeadlineAndRedirectNeverFollows() throws Exception {
        handler.set(x -> { x.getResponseHeaders().add("Retry-After", "20"); respond(x, 429, "application/json", new byte[0]); });
        long begin = System.nanoTime();
        failure(speech.synthesize("retry-after", "你好", "xiaoyun", Duration.ofMillis(500)), RATE_LIMITED);
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(1));
        assertThat(received.get()).isEqualTo(1);
        speech.close(); speech = adapter(new StaticNlsTokenProvider("replacement-test-token", null), 2);
        handler.set(x -> { x.getResponseHeaders().add("Location", "/tts"); respond(x, 302, "text/plain", new byte[0]); });
        failure(say("redirect"), HTTP_REJECTED);
        assertThat(received.get()).isEqualTo(2);
    }
    @Test void rejectsJsonBadParametersTruncationEmptyAndOversizedBodies() throws Exception {
        handler.set(x -> respond(x, 200, "application/json", "{\"status\":40000003,\"message\":\"private-response\"}".getBytes()));
        failure(say("parameters"), INVALID_PARAMETERS); awaitIdle();
        handler.set(x -> respond(x, 200, "application/json", "{\"status\":20000000}".getBytes()));
        failure(say("json"), INVALID_RESPONSE); awaitIdle();
        handler.set(x -> respond(x, 200, "audio/mpeg", Arrays.copyOf(audio, audio.length - 2)));
        failure(say("truncated"), INVALID_WAV); awaitIdle();
        handler.set(x -> respond(x, 200, "audio/mpeg", new byte[0]));
        failure(say("empty"), INVALID_WAV); awaitIdle();
        handler.set(x -> respond(x, 200, "audio/mpeg", new byte[PcmWav.MAX_BYTES + 1]));
        failure(say("large"), AUDIO_TOO_LARGE); awaitIdle();
        assertThat(received.get()).isEqualTo(5); assertNoWavs();
        handler.set(x -> respond(x, 200, "audio/mpeg", audio)); speech.release(get("next", "你好"));
    }
    @Test void deadlineBeforeHeadersCancelsRequestAndRecovers() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1), allow = new CountDownLatch(1);
        handler.set(x -> { arrived.countDown(); waitFor(allow); respond(x, 200, "audio/mpeg", audio); });
        var result = speech.synthesize("slow-header", "你好", "xiaoyun", Duration.ofMillis(350));
        assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue();
        failure(result, DEADLINE_EXCEEDED);
        allow.countDown(); awaitIdle(); assertNoWavs();
        handler.set(x -> respond(x, 200, "audio/mpeg", audio)); speech.release(get("next", "你好"));
    }
    @Test void readIdleTimeoutAndTrickleCannotExtendAbsoluteDeadline() throws Exception {
        handler.set(x -> {
            x.getResponseHeaders().add("Content-Type", "audio/mpeg"); x.sendResponseHeaders(200, 0);
            x.getResponseBody().write(audio, 0, 44); x.getResponseBody().flush(); pause(600);
        });
        failure(say("idle"), READ_TIMEOUT); awaitIdle(); assertNoWavs();
        handler.set(x -> {
            x.getResponseHeaders().add("Content-Type", "audio/mpeg"); x.sendResponseHeaders(200, 0);
            for (int i = 0; i < 30; i++) { x.getResponseBody().write(audio, i * 100, 100); x.getResponseBody().flush(); pause(40); }
        });
        failure(speech.synthesize("trickle", "你好", "xiaoyun", Duration.ofMillis(450)), DEADLINE_EXCEEDED);
        awaitIdle(); assertNoWavs();
    }
    @Test void cancelDuringBodyPreventsLatePublicationAndNextRequestWorks() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1), allow = new CountDownLatch(1);
        handler.set(x -> {
            x.getResponseHeaders().add("Content-Type", "audio/mpeg"); x.sendResponseHeaders(200, 0);
            x.getResponseBody().write(audio, 0, 44); x.getResponseBody().flush(); bodyStarted.countDown();
            waitFor(allow); x.getResponseBody().write(audio, 44, audio.length - 44);
        });
        var result = say("cancel"); assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue();
        speech.cancel("cancel"); failure(result, CANCELLED); allow.countDown();
        awaitIdle(); Thread.sleep(30); assertNoWavs();
        handler.set(x -> respond(x, 200, "audio/mpeg", audio)); speech.release(get("next", "你好"));
    }
    @Test void futureCancellationCloseAndCapacityDoNotLeakReservations() throws Exception {
        CountDownLatch arrived = new CountDownLatch(2), allow = new CountDownLatch(1);
        handler.set(x -> { arrived.countDown(); waitFor(allow); respond(x, 200, "audio/mpeg", audio); });
        var a = say("a"); var b = say("b");
        assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue();
        failure(say("a"), BUSY); failure(say("c"), BUSY);
        assertThat(a.toCompletableFuture().cancel(true)).isTrue();
        speech.close(); failure(b, CLOSED); allow.countDown();
        awaitIdle(); assertNoWavs();
        failure(say("closed"), CLOSED);
    }
    @Test void successfulFilesAreBoundedUniqueAndOnlyExplicitlyReleased() throws Exception {
        List<AudioAsset> assets = new ArrayList<>();
        for (int i = 0; i < OwnedAudioFiles.MAX_FILES; i++) { assets.add(get("file-" + i, "你好")); awaitIdle(); }
        assertThat(assets.stream().map(AudioAsset::path).distinct().count()).isEqualTo(8);
        failure(say("overflow"), FILE_LIMIT);
        assertThat(received.get()).isEqualTo(8);
        Path foreign = directory.resolve("foreign.wav"); Files.write(foreign, audio);
        assertThat(speech.release(new AudioAsset(foreign, "audio/wav", Duration.ofMillis(300)))).isFalse();
        assertThat(Files.exists(foreign)).isTrue();
        speech.release(assets.getFirst());
        AudioAsset next = get("next", "你好"); awaitIdle();
        speech.close(); assertThat(Files.exists(next.path())).isTrue();
        assertThat(speech.release(next)).isTrue();
        // Reopening counts retained files without deleting them or overwriting old tasks.
        speech = adapter(new StaticNlsTokenProvider("replacement-test-token", null), 2);
        speech.release(get("one-slot", "你好"));
        assertThat(Files.exists(assets.get(1).path())).isTrue();
    }
    @Test void existingFileAndExclusiveDirectoryLeaseArePreserved() throws Exception {
        Path orphan = directory.resolve("aliyun-nls-existing.wav"); Files.write(orphan, audio);
        assertThatThrownBy(() -> adapter(new StaticNlsTokenProvider("test-only", null), 1))
            .isInstanceOf(SpeechFailure.class).hasMessage("NLS_FILE_IO");
        AudioAsset asset = get("ok", "你好"); speech.cancel("ok");
        assertThat(Files.exists(asset.path())).isFalse();
        assertThat(Files.readAllBytes(orphan)).isEqualTo(audio);
    }
    @Test void explicitEnableAndCredentialChecksFailBeforeAnyRequest() {
        assertThatThrownBy(() -> AlibabaNlsSpeech.create(false, directory, mapper)).hasMessage("NLS_DISABLED");
        assertThatThrownBy(() -> AlibabaNlsSpeech.create(true, directory, mapper, "", tokens)).hasMessage("NLS_MISSING_CREDENTIALS");
        assertThatThrownBy(() -> new StaticNlsTokenProvider(null, null)).hasMessage("NLS_MISSING_CREDENTIALS");
        assertThat(speech.toString()).doesNotContain("local-test-appkey-only", "local-test-token-only");
        assertThat(received.get()).isZero();
    }
    private CompletionStage<AudioAsset> say(String id) { return speech.synthesize(id, "你好，大肥鱼。", "xiaoyun", BUDGET); }
    private AudioAsset get(String id, String text) throws Exception { return speech.synthesize(id, text, "xiaoyun", BUDGET).toCompletableFuture().get(4, TimeUnit.SECONDS); }
    private static void failure(CompletionStage<?> future, SpeechFailure.Code code) {
        assertThatThrownBy(() -> future.toCompletableFuture().get(4, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class).cause().isInstanceOf(SpeechFailure.class).hasMessage("NLS_" + code).hasNoCause();
    }
    private void awaitIdle() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (speech.diagnostics().active() != 0 && System.nanoTime() < end) Thread.sleep(5);
        assertThat(speech.diagnostics().active()).isZero();
        Thread.sleep(10); // worker may be exiting its final callback after the externally visible terminal state.
    }
    private void assertNoWavs() throws IOException {
        try (var paths = Files.newDirectoryStream(directory, "aliyun-nls-*.wav")) { assertThat(paths).isEmpty(); }
        assertThat(speech.diagnostics().outstandingFiles()).isZero();
    }
    private static void respond(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().add("Content-Type", type); x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        x.getResponseBody().write(body);
    }
    private static void waitFor(CountDownLatch latch) { try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static void pause(long millis) { try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
