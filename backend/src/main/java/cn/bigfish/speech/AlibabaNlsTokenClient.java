package cn.bigfish.speech;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static cn.bigfish.speech.SpeechFailure.Code.*;

/** Lazy, one-request-at-a-time official CreateToken client. No retries, redirects or cloud calls at construction. */
public final class AlibabaNlsTokenClient implements AutoCloseable {
    private static final URI ENDPOINT = URI.create("https://nls-meta.cn-shanghai.aliyuncs.com/");
    private final URI endpoint;
    private final String accessKeyId, accessKeySecret;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Supplier<String> nonce;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("nls-token-http-deadline").factory());
    private Request active;
    private boolean closed;
    private long attempts;
    private static final class Request {
        final CompletableFuture<NlsTokenProvider.Token> result = new CompletableFuture<>();
        final long started = System.nanoTime();
        HttpClient http;
        CompletableFuture<HttpResponse<byte[]>> transport;
        NlsTokenBody body;
        ScheduledFuture<?> timeout;
        boolean finishing;
    }
    public static AlibabaNlsTokenClient create(boolean enabled, String accessKeyId, String accessKeySecret, ObjectMapper mapper) {
        if (!enabled) throw new SpeechFailure(DISABLED);
        return new AlibabaNlsTokenClient(ENDPOINT, accessKeyId, accessKeySecret, mapper, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }
    // Loopback seam is package-local, never a configuration or browser input.
    AlibabaNlsTokenClient(URI endpoint, String id, String secret, ObjectMapper mapper, Clock clock, Supplier<String> nonce) {
        if (!ENDPOINT.equals(endpoint) && !("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())
            && "/".equals(endpoint.getPath()) && endpoint.getQuery() == null && endpoint.getFragment() == null && endpoint.getUserInfo() == null))
            throw new IllegalArgumentException("NLS_TOKEN_ENDPOINT");
        if (!credential(id, 128) || !credential(secret, 256)) throw new SpeechFailure(MISSING_CREDENTIALS);
        this.endpoint = endpoint; accessKeyId = id; accessKeySecret = secret;
        this.mapper = Objects.requireNonNull(mapper).copy()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS); this.clock = Objects.requireNonNull(clock); this.nonce = Objects.requireNonNull(nonce);
        timer.setRemoveOnCancelPolicy(true);
    }
    private static boolean credential(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.chars().allMatch(c -> c >= 33 && c <= 126);
    }
    public synchronized CompletionStage<NlsTokenProvider.Token> issue(Duration deadline) {
        if (closed) return failed(CLOSED);
        if (deadline == null || deadline.isNegative() || deadline.isZero()) return failed(DEADLINE_EXCEEDED);
        if (active != null) return failed(BUSY);
        long budget = deadline.compareTo(Duration.ofSeconds(3)) > 0 ? TimeUnit.SECONDS.toNanos(3) : deadline.toNanos();
        Request request = new Request(); active = request;
        request.result.whenComplete((v, error) -> { if (request.result.isCancelled()) finish(request, null, CANCELLED); });
        try {
            request.timeout = timer.schedule(() -> finish(request, null, TOKEN_REFRESH_TIMEOUT), budget, TimeUnit.NANOSECONDS);
            String unique = nonce.get();
            if (unique == null || !unique.matches("[A-Za-z0-9-]{1,128}")) throw new SpeechFailure(TOKEN_SIGNATURE_INVALID);
            String body = NlsRpcSignature.postBody(accessKeyId, accessKeySecret, clock.instant(), unique);
            request.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofNanos(Math.min(budget, TimeUnit.SECONDS.toNanos(2)))).build();
            long remaining = budget - (System.nanoTime() - request.started);
            if (remaining <= 0) throw new SpeechFailure(TOKEN_REFRESH_TIMEOUT);
            HttpRequest post = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            attempts++;
            request.transport = request.http.sendAsync(post, response -> {
                synchronized (this) {
                    request.body = new NlsTokenBody();
                    if (active != request || request.finishing) request.body.fail(CANCELLED);
                    return request.body;
                }
            });
            request.transport.whenComplete((response, error) -> {
                if (error != null) finish(request, null, safeCode(error));
                else {
                    try { finish(request, parse(response, request.started), null); }
                    catch (Exception failure) { finish(request, null, safeCode(failure)); }
                }
            });
        } catch (Exception error) { finish(request, null, safeCode(error)); }
        return request.result;
    }
    private NlsTokenProvider.Token parse(HttpResponse<byte[]> response, long started) {
        int status = response.statusCode();
        if (status >= 300 && status < 400) throw new SpeechFailure(TOKEN_REQUEST_REJECTED);
        if (status == 429) throw new SpeechFailure(TOKEN_RATE_LIMITED);
        if (status >= 500) throw new SpeechFailure(TOKEN_PROVIDER_UNAVAILABLE);
        JsonNode document;
        try { document = mapper.readTree(response.body()); }
        catch (Exception ignored) { throw new SpeechFailure(status == 401 || status == 403 ? TOKEN_AUTH_REJECTED : TOKEN_RESPONSE_INVALID); }
        if (document == null || !document.isObject())
            throw new SpeechFailure(status == 401 || status == 403 ? TOKEN_AUTH_REJECTED : TOKEN_RESPONSE_INVALID);
        String code = document.path("Code").asText("");
        if (!code.isEmpty()) throw new SpeechFailure(switch (code) {
            case "InvalidTimeStamp.Expired", "InvalidTimeStamp", "InvalidTimestamp", "SignatureExpired" -> TOKEN_CLOCK_SKEW;
            case "SignatureDoesNotMatch", "InvalidSignature", "SignatureNonceUsed" -> TOKEN_SIGNATURE_INVALID;
            case "InvalidAccessKeyId.NotFound", "InvalidAccessKeyId", "Forbidden", "Forbidden.RAM", "MissingAuthenticationToken" -> TOKEN_AUTH_REJECTED;
            case "Throttling", "Throttling.User", "Throttling.Api" -> TOKEN_RATE_LIMITED;
            case "InternalError", "ServiceUnavailable" -> TOKEN_PROVIDER_UNAVAILABLE;
            default -> TOKEN_REQUEST_REJECTED;
        });
        if (status == 401 || status == 403) throw new SpeechFailure(TOKEN_AUTH_REJECTED);
        if (status != 200) throw new SpeechFailure(TOKEN_REQUEST_REJECTED);
        Instant now = clock.instant();
        Instant reference = now;
        String date = response.headers().firstValue("Date").orElse(null);
        if (date != null) {
            try {
                Instant server = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (Duration.between(server, now).abs().compareTo(Duration.ofMinutes(5)) > 0) throw new SpeechFailure(TOKEN_CLOCK_SKEW);
                // Include all request elapsed time and Date's one-second precision conservatively.
                Instant serverUpperBound = server.plusNanos(System.nanoTime() - started).plusSeconds(1);
                if (serverUpperBound.isAfter(reference)) reference = serverUpperBound;
            } catch (java.time.format.DateTimeParseException ignored) { throw new SpeechFailure(TOKEN_RESPONSE_INVALID); }
        }
        var token = document.path("Token");
        var id = token.path("Id"); var expires = token.path("ExpireTime");
        if (!token.isObject() || !id.isTextual() || !credential(id.textValue(), 4096)
            || !expires.isIntegralNumber() || !expires.canConvertToLong()) throw new SpeechFailure(TOKEN_RESPONSE_INVALID);
        Instant expiration;
        try {
            expiration = Instant.ofEpochSecond(expires.longValue());
            Duration.between(now, expiration).toNanos(); // Reject unrepresentable/absurd lifetimes, not a guessed TTL.
        } catch (Exception ignored) { throw new SpeechFailure(TOKEN_RESPONSE_INVALID); }
        if (!expiration.isAfter(reference.plusSeconds(30))) throw new SpeechFailure(TOKEN_EXPIRED);
        return new NlsTokenProvider.Token(id.textValue(), expiration, Duration.between(reference, expiration));
    }
    private void finish(Request request, NlsTokenProvider.Token token, SpeechFailure.Code error) {
        synchronized (this) {
            if (active != request || request.finishing) return;
            request.finishing = true;
        }
        // Cancel outside the monitor: HTTP body completion may call finish concurrently.
        if (request.timeout != null) request.timeout.cancel(false);
        if (request.body != null) request.body.fail(CANCELLED);
        if (request.transport != null) request.transport.cancel(true);
        if (request.http != null) request.http.shutdownNow();
        synchronized (this) { if (active == request) active = null; }
        // Completion outside the client monitor avoids lock inversion with the refresher's cancellation.
        if (error == null) request.result.complete(token);
        else request.result.completeExceptionally(new SpeechFailure(error));
    }
    static SpeechFailure.Code safeCode(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof SpeechFailure safe) return safe.code();
        if (error instanceof HttpTimeoutException || error instanceof TimeoutException) return TOKEN_REFRESH_TIMEOUT;
        if (error instanceof CancellationException) return CANCELLED;
        return TOKEN_TRANSPORT_FAILED;
    }
    private static CompletionStage<NlsTokenProvider.Token> failed(SpeechFailure.Code code) { return CompletableFuture.failedFuture(new SpeechFailure(code)); }
    public synchronized long attempts() { return attempts; }
    @Override public void close() {
        Request request;
        synchronized (this) { if (closed) return; closed = true; request = active; }
        if (request != null) finish(request, null, CLOSED);
        timer.shutdownNow();
    }
    @Override public String toString() { return "AlibabaNlsTokenClient[REDACTED]"; }
}
