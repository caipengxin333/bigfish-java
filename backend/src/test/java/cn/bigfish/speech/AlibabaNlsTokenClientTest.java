package cn.bigfish.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static cn.bigfish.speech.SpeechFailure.Code.*;

class AlibabaNlsTokenClientTest {
    private HttpServer server;
    private ExecutorService workers;
    private AlibabaNlsTokenClient client;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(workers);
        handler.set(x -> respond(x, 200, valid()));
        server.createContext("/", x -> { calls.incrementAndGet(); try { handler.get().handle(x); } catch (IOException ignored) { } finally { x.close(); } });
        server.start(); client = make(Clock.fixed(now, ZoneOffset.UTC));
    }
    private AlibabaNlsTokenClient make(Clock clock) {
        return new AlibabaNlsTokenClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
            "fake-access-id", "fake-access-secret", mapper, clock, () -> UUID.randomUUID().toString());
    }
    @AfterEach void stop() { if (client != null) client.close(); if (server != null) server.stop(0); if (workers != null) workers.shutdownNow(); }
    private String valid() { return "{\"Token\":{\"Id\":\"fake-issued-token\",\"ExpireTime\":" + now.plusSeconds(3600).getEpochSecond() + "}}"; }
    private CompletionStage<NlsTokenProvider.Token> issue() { return client.issue(Duration.ofSeconds(2)); }
    @Test void postUsesOfficialParametersNoQueryAndActualExpiration() throws Exception {
        List<Map<String,String>> requests = new CopyOnWriteArrayList<>();
        AtomicReference<String> method = new AtomicReference<>(), query = new AtomicReference<>(), contentType = new AtomicReference<>();
        handler.set(x -> {
            method.set(x.getRequestMethod()); query.set(x.getRequestURI().getRawQuery()); contentType.set(x.getRequestHeaders().getFirst("Content-Type"));
            Map<String,String> params = new HashMap<>();
            for (String pair : new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                String[] parts = pair.split("=",2); params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            requests.add(params); respond(x, 200, valid());
        });
        assertThat(calls.get()).isZero(); assertThat(client.attempts()).isZero();
        var token = issue().toCompletableFuture().get(3, TimeUnit.SECONDS);
        issue().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertThat(token.expiresAt()).isEqualTo(now.plusSeconds(3600));
        assertThat(token.value()).isEqualTo("fake-issued-token");
        assertThat(token.toString()).doesNotContain("fake-issued-token");
        assertThat(method.get()).isEqualTo("POST"); assertThat(query.get()).isNull();
        assertThat(contentType.get()).startsWith("application/x-www-form-urlencoded");
        for (var p : requests) {
            assertThat(p.get("Action")).isEqualTo("CreateToken"); assertThat(p.get("Version")).isEqualTo("2019-02-28");
            assertThat(p.get("RegionId")).isEqualTo("cn-shanghai"); assertThat(p.get("Timestamp")).isEqualTo(now.toString());
            assertThat(p.get("Signature")).isEqualTo(NlsRpcSignature.signature("POST", p, "fake-access-secret"));
        }
        assertThat(requests.get(0).get("SignatureNonce")).isNotEqualTo(requests.get(1).get("SignatureNonce"));
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void rejectsMalformedMissingAmbiguousAndExpiredResponses() {
        for (String value : List.of("{}", "[]", "not-json", "{\"Token\":{}}",
            "{\"Token\":{\"Id\":\"fake\",\"ExpireTime\":\"9999999999\"}}",
            "{\"Token\":{\"Id\":123,\"ExpireTime\":9999999999}}",
            "{\"Token\":{\"Id\":\"fake\",\"ExpireTime\":1.2}}", valid() + " {}",
            valid().replace("\"Id\":", "\"Id\":\"other\",\"Id\":"),
            valid().replace("fake-issued-token", ""), valid().replace(Long.toString(now.plusSeconds(3600).getEpochSecond()), "9223372036854775807"))) {
            handler.set(x -> respond(x, 200, value)); failure(issue(), TOKEN_RESPONSE_INVALID);
        }
        handler.set(x -> respond(x, 200, valid().replace(Long.toString(now.plusSeconds(3600).getEpochSecond()), Long.toString(now.minusSeconds(1).getEpochSecond()))));
        failure(issue(), TOKEN_EXPIRED);
    }
    @Test void providerErrorsAreMappedWithoutEchoOrRetryAndNextCallRecovers() throws Exception {
        Map<String,SpeechFailure.Code> cases = Map.of("InvalidTimeStamp.Expired", TOKEN_CLOCK_SKEW,
            "SignatureDoesNotMatch", TOKEN_SIGNATURE_INVALID, "InvalidAccessKeyId.NotFound", TOKEN_AUTH_REJECTED,
            "Forbidden.RAM", TOKEN_AUTH_REJECTED, "Throttling.User", TOKEN_RATE_LIMITED, "Unexpected.Private", TOKEN_REQUEST_REJECTED);
        for (var entry : cases.entrySet()) {
            handler.set(x -> respond(x, 403, "{\"Code\":\"" + entry.getKey() + "\",\"Message\":\"private-signed-body-secret\"}"));
            failure(issue(), entry.getValue());
        }
        assertThat(calls.get()).isEqualTo(cases.size());
        handler.set(x -> respond(x, 200, valid())); assertThat(issue().toCompletableFuture().get(3, TimeUnit.SECONDS)).isNotNull();
    }
    @Test void httpFailureAndRedirectAreBoundedAndNeverFollowed() {
        for (int status : new int[]{401, 429, 503, 302}) {
            handler.set(x -> { x.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/other"); respond(x, status, ""); });
            failure(issue(), status == 401 ? TOKEN_AUTH_REJECTED : status == 429 ? TOKEN_RATE_LIMITED : status == 503 ? TOKEN_PROVIDER_UNAVAILABLE : TOKEN_REQUEST_REJECTED);
        }
        assertThat(calls.get()).isEqualTo(4);
    }
    @Test void rejectsSuccessfulResponseWhenLocalClockIsBadlySkewed() {
        client.close(); client = make(Clock.fixed(now.minusSeconds(600), ZoneOffset.UTC));
        failure(issue(), TOKEN_CLOCK_SKEW);
    }
    @Test void oversizedAndTricklingResponsesCannotEvadeBounds() {
        handler.set(x -> respond(x, 200, "x".repeat(16_385))); failure(issue(), TOKEN_RESPONSE_INVALID);
        handler.set(x -> {
            x.sendResponseHeaders(200, 0);
            for (int i = 0; i < 50; i++) { x.getResponseBody().write(' '); x.getResponseBody().flush(); pause(20); }
        });
        failure(client.issue(Duration.ofMillis(180)), TOKEN_REFRESH_TIMEOUT);
    }
    @Test void cancellationClosesActiveRequestAndAllowsRecovery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        handler.set(x -> { entered.countDown(); await(release); respond(x, 200, valid()); });
        var request = issue().toCompletableFuture(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        failure(issue(), BUSY);
        request.cancel(true); release.countDown();
        assertThat(request.isCancelled()).isTrue();
        handler.set(x -> respond(x, 200, valid()));
        assertThat(issue().toCompletableFuture().get(3, TimeUnit.SECONDS)).isNotNull();
    }
    @Test void closeCompletesWaiterAndNoCredentialsOrUrlsEscapeExceptions() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        handler.set(x -> { entered.countDown(); await(release); respond(x, 200, valid()); });
        var pending = issue(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        client.close(); failure(pending, CLOSED); release.countDown(); failure(issue(), CLOSED);
        assertThat(client.toString()).doesNotContain("fake-access-id", "fake-access-secret");
        assertThatThrownBy(() -> AlibabaNlsTokenClient.create(false, "fake", "fake", mapper)).hasMessage("NLS_DISABLED");
        assertThatThrownBy(() -> AlibabaNlsTokenClient.create(true, null, null, mapper)).hasMessage("NLS_MISSING_CREDENTIALS");
    }
    static void failure(CompletionStage<?> future, SpeechFailure.Code code) {
        assertThatThrownBy(() -> future.toCompletableFuture().get(4, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
            .cause().isInstanceOf(SpeechFailure.class).hasMessage("NLS_" + code).hasNoCause();
    }
    private static void respond(HttpExchange x, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json"); x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        x.getResponseBody().write(bytes);
    }
    private static void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static void await(CountDownLatch latch) { try { latch.await(2,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}
