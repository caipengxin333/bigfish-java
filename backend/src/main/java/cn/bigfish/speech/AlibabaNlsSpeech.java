package cn.bigfish.speech;

import cn.bigfish.contract.SpeechPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static cn.bigfish.speech.SpeechFailure.Code.*;

/** Official NLS short-text REST adapter. Deliberately not a Spring bean: integration must wire release(). */
public final class AlibabaNlsSpeech implements SpeechPort, AutoCloseable {
    public static final String DEFAULT_VOICE = "xiaoyun";
    public static final Set<String> VOICES = Set.of("xiaoyun", "xiaogang", "zhixiaomei", "zhigui", "aixia");
    private static final URI ENDPOINT = URI.create("https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts");
    record Options(Duration connect, Duration read, Duration cooldown, int attempts) {
        static Options defaults() { return new Options(Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(1), 2); }
        Options {
            if (connect.isNegative() || connect.isZero() || read.isNegative() || read.isZero()
                || cooldown.isNegative() || cooldown.isZero() || attempts < 1 || attempts > 2)
                throw new IllegalArgumentException("NLS_OPTIONS");
        }
    }
    public record Diagnostics(long requests, long succeeded, long failed, int active, int outstandingFiles,
                              NlsTokenProvider.Status token, SpeechFailure.Code lastError) {}
    private final URI endpoint;
    private final String appKey;
    private final ObjectMapper mapper;
    private final NlsTokenProvider tokens;
    private final Options options;
    private final OwnedAudioFiles files;
    private final Map<String, Job> active = new HashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("nls-synthesis-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("nls-deadline").factory());
    private final AtomicLong requests = new AtomicLong(), succeeded = new AtomicLong(), failed = new AtomicLong();
    private volatile SpeechFailure.Code lastError;
    private long cooldownUntil;
    private boolean closed;
    private static final class Job {
        final CompletableFuture<AudioAsset> result = new CompletableFuture<>();
        final long ends;
        final OwnedAudioFiles.Ticket ticket;
        Future<?> worker;
        CompletableFuture<?> pending;
        HttpClient client;
        BoundedAudioBody body;
        ScheduledFuture<?> timeout;
        boolean aborted;
        Job(long ends, OwnedAudioFiles.Ticket ticket) { this.ends = ends; this.ticket = ticket; }
    }
    /** No environment is read while disabled. Unknown environment token expiry stays UNVERIFIED. */
    public static AlibabaNlsSpeech create(boolean enabled, Path audioDirectory, ObjectMapper mapper) {
        if (!enabled) throw new SpeechFailure(DISABLED);
        return create(true, audioDirectory, mapper, System.getenv("BIGFISH_ALIYUN_NLS_APP_KEY"),
            new StaticNlsTokenProvider(System.getenv("BIGFISH_ALIYUN_NLS_TOKEN"), null));
    }
    /** Backend injection for a known-expiry/refreshing provider. This adapter owns and closes the provider. */
    public static AlibabaNlsSpeech create(boolean enabled, Path audioDirectory, ObjectMapper mapper,
                                         String appKey, NlsTokenProvider tokens) {
        if (!enabled) throw new SpeechFailure(DISABLED);
        return new AlibabaNlsSpeech(ENDPOINT, audioDirectory, mapper, appKey, tokens, Options.defaults());
    }
    /** Package-local seam; only official production URI or loopback HTTP fixtures are accepted. */
    AlibabaNlsSpeech(URI endpoint, Path directory, ObjectMapper mapper, String appKey, NlsTokenProvider tokens, Options options) {
        if (!endpoint.equals(ENDPOINT) && !("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())
            && endpoint.getUserInfo() == null && endpoint.getQuery() == null && endpoint.getFragment() == null))
            throw new IllegalArgumentException("NLS_ENDPOINT");
        if (appKey == null || !appKey.matches("[A-Za-z0-9_-]{1,128}") || tokens == null) throw new SpeechFailure(MISSING_CREDENTIALS);
        this.endpoint = endpoint; this.mapper = Objects.requireNonNull(mapper); this.appKey = appKey;
        this.tokens = tokens; this.options = options; files = new OwnedAudioFiles(directory);
        timers.setRemoveOnCancelPolicy(true);
    }
    @Override public synchronized CompletionStage<AudioAsset> synthesize(String taskId, String text, String voice, Duration deadline) {
        long entered = System.nanoTime();
        try {
            if (closed) throw new SpeechFailure(CLOSED);
            validate(taskId, text, voice, deadline);
            if (active.size() >= 2 || active.containsKey(taskId)) throw new SpeechFailure(BUSY);
            if (entered < cooldownUntil) throw new SpeechFailure(COOLDOWN);
            // The supplied budget is never extended; an internal maximum bounds exceptionally large callers.
            long budget = deadline.compareTo(Duration.ofSeconds(120)) > 0 ? TimeUnit.SECONDS.toNanos(120) : deadline.toNanos();
            Job job = new Job(entered + budget, files.reserve(taskId));
            active.put(taskId, job);
            job.result.whenComplete((v, error) -> { if (job.result.isCancelled()) abort(job, CANCELLED); });
            FutureTask<Void> work = new FutureTask<>(() -> { run(job, text, voice); return null; });
            job.worker = work;
            job.timeout = timers.schedule(() -> abort(job, DEADLINE_EXCEEDED), Math.max(0, job.ends - System.nanoTime()), TimeUnit.NANOSECONDS);
            try { workers.execute(work); }
            catch (RejectedExecutionException ignored) { abort(job, BUSY); active.remove(taskId, job); }
            return job.result;
        } catch (SpeechFailure error) { lastError = error.code(); return CompletableFuture.failedFuture(error); }
    }
    private static void validate(String id, String text, String voice, Duration deadline) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new SpeechFailure(INVALID_TASK);
        if (deadline == null || deadline.isZero() || deadline.isNegative()) throw new SpeechFailure(DEADLINE_EXCEEDED);
        if (!VOICES.contains(voice == null ? "" : voice)) throw new SpeechFailure(UNSUPPORTED_VOICE);
        if (text == null || text.isBlank()) throw new SpeechFailure(INVALID_TEXT);
        if (text.length() > 300) throw new SpeechFailure(TEXT_TOO_LONG);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '>' || Character.isISOControl(c) && c != '\n' && c != '\t') throw new SpeechFailure(INVALID_TEXT);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new SpeechFailure(INVALID_TEXT);
            } else if (Character.isLowSurrogate(c)) throw new SpeechFailure(INVALID_TEXT);
        }
    }
    private void run(Job job, String text, String voice) {
        try {
            NlsTokenProvider.Token token = await(job, tokens.acquire(remaining(job)).toCompletableFuture());
            byte[] payload = mapper.writeValueAsBytes(Map.of("appkey", appKey, "text", text, "voice", voice,
                "format", "wav", "sample_rate", 16000, "volume", 50, "speech_rate", 0, "pitch_rate", 0));
            for (int attempt = 1; attempt <= options.attempts(); attempt++) {
                check(job);
                HttpResponse<byte[]> response = request(job, payload, token);
                SpeechFailure.Code error = classify(response);
                if (error == TOKEN_REJECTED) tokens.rejected(token);
                if (error != null) {
                    if (error == RATE_LIMITED || error == PROVIDER_UNAVAILABLE) {
                        long pause = retryDelay(response);
                        synchronized (this) { cooldownUntil = Math.max(cooldownUntil, System.nanoTime() + pause); }
                        if (attempt < options.attempts() && pause < remaining(job).toNanos()) {
                            TimeUnit.NANOSECONDS.sleep(pause); check(job); continue;
                        }
                    }
                    throw new SpeechFailure(error);
                }
                PcmWav.Info info = PcmWav.inspect(response.body());
                synchronized (job) {
                    check(job);
                    files.write(job.ticket, response.body());
                    check(job);
                    tokens.accepted(token);
                    AudioAsset asset = new AudioAsset(job.ticket.path(), "audio/wav", info.duration());
                    if (!job.result.complete(asset)) files.release(job.ticket);
                    else succeeded.incrementAndGet();
                }
                return;
            }
        } catch (Exception error) { abort(job, safeCode(error)); }
        finally {
            synchronized (job) {
                if (job.timeout != null) job.timeout.cancel(false);
                dispose(job);
            }
            synchronized (this) { active.remove(job.ticket.taskId(), job); }
        }
    }
    private HttpResponse<byte[]> request(Job job, byte[] payload, NlsTokenProvider.Token token) throws Exception {
        synchronized (job) {
            check(job);
            job.client = HttpClient.newBuilder().connectTimeout(min(options.connect(), remaining(job)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(remaining(job))
                .header("Content-Type", "application/json; charset=utf-8").header("X-NLS-Token", token.value())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            requests.incrementAndGet();
            job.pending = job.client.sendAsync(request, response -> {
                synchronized (job) {
                    BoundedAudioBody body = new BoundedAudioBody(response.statusCode() == 200 ? PcmWav.MAX_BYTES : 16_384, options.read(), timers);
                    job.body = body;
                    if (job.result.isDone()) body.fail(CANCELLED);
                    return body;
                }
            });
        }
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<HttpResponse<byte[]>> response = (CompletableFuture<HttpResponse<byte[]>>) job.pending;
            return response.get(remaining(job).toNanos(), TimeUnit.NANOSECONDS);
        } finally { synchronized (job) { dispose(job); } }
    }
    private <T> T await(Job job, CompletableFuture<T> pending) throws Exception {
        try {
            synchronized (job) { check(job); job.pending = pending; }
            return pending.get(remaining(job).toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
            synchronized (job) { if (job.pending == pending) job.pending = null; }
        }
    }
    private SpeechFailure.Code classify(HttpResponse<byte[]> response) {
        int http = response.statusCode();
        if (http == 401 || http == 403) return TOKEN_REJECTED;
        if (http == 429) return RATE_LIMITED;
        if (http >= 500 && http <= 599) return PROVIDER_UNAVAILABLE;
        if (http >= 300 && http < 400) return HTTP_REJECTED;
        String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (http == 200 && (type.equals("audio/mpeg") || type.equals("audio/wav") || type.equals("audio/x-wav"))) return null;
        try {
            if (response.body().length > 16_384) return INVALID_RESPONSE;
            var status = mapper.readTree(response.body()).path("status");
            if (!status.isIntegralNumber() || !status.canConvertToInt()) return INVALID_RESPONSE;
            return switch (status.asInt()) {
                case 40000001 -> TOKEN_REJECTED;
                case 40000005 -> RATE_LIMITED;
                case 50000000, 50000001 -> PROVIDER_UNAVAILABLE;
                case 40000000, 40000002, 40000003, 41020001 -> INVALID_PARAMETERS;
                default -> INVALID_RESPONSE;
            };
        } catch (Exception ignored) { return INVALID_RESPONSE; }
    }
    private long retryDelay(HttpResponse<?> response) {
        long nanos = options.cooldown().toNanos();
        // Only integer Retry-After is supported; huge/dated values suppress retries within this bounded task.
        String retry = response.headers().firstValue("Retry-After").orElse(null);
        if (retry != null) {
            try { nanos = Math.max(nanos, TimeUnit.SECONDS.toNanos(Math.min(120, Math.max(0, Long.parseLong(retry))))); }
            catch (NumberFormatException ignored) { nanos = TimeUnit.SECONDS.toNanos(120); }
        }
        return nanos;
    }
    private static Duration min(Duration a, Duration b) { return a.compareTo(b) < 0 ? a : b; }
    private static Duration remaining(Job job) {
        long nanos = job.ends - System.nanoTime();
        if (nanos <= 0) throw new SpeechFailure(DEADLINE_EXCEEDED);
        return Duration.ofNanos(nanos);
    }
    private static void check(Job job) {
        if (job.result.isDone() || Thread.currentThread().isInterrupted()) throw new SpeechFailure(CANCELLED);
        remaining(job);
    }
    private static SpeechFailure.Code safeCode(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof SpeechFailure known) return known.code();
        if (error instanceof HttpConnectTimeoutException) return CONNECT_TIMEOUT;
        if (error instanceof TimeoutException || error instanceof HttpTimeoutException) return DEADLINE_EXCEEDED;
        if (error instanceof InterruptedException || error instanceof CancellationException) return CANCELLED;
        return TRANSPORT_FAILED;
    }
    private void abort(Job job, SpeechFailure.Code code) {
        synchronized (job) {
            if (job.aborted || job.result.isDone() && !job.result.isCancelled()) return;
            job.aborted = true;
            if (job.timeout != null) job.timeout.cancel(false);
            dispose(job);
            if (job.worker != null) job.worker.cancel(true);
            try { files.release(job.ticket); } catch (SpeechFailure ignored) { code = FILE_IO; }
            lastError = code;
            failed.incrementAndGet();
            job.result.completeExceptionally(new SpeechFailure(code));
        }
        synchronized (this) { active.remove(job.ticket.taskId(), job); }
    }
    private static void dispose(Job job) {
        if (job.body != null) { job.body.fail(CANCELLED); job.body = null; }
        if (job.pending != null) { job.pending.cancel(true); job.pending = null; }
        if (job.client != null) { job.client.shutdownNow(); job.client = null; }
    }
    @Override public void cancel(String taskId) {
        Job job;
        synchronized (this) { job = active.get(taskId); }
        if (job != null) abort(job, CANCELLED);
        files.releaseTask(taskId);
    }
    /** Call after AudioAssets.load in a finally block, including if that load fails. Idempotent. */
    public boolean release(AudioAsset asset) { return files.release(asset); }
    public synchronized Diagnostics diagnostics() {
        return new Diagnostics(requests.get(), succeeded.get(), failed.get(), active.size(), files.outstanding(), tokens.status(), lastError);
    }
    @Override public void close() {
        List<Job> jobs;
        synchronized (this) { if (closed) return; closed = true; jobs = List.copyOf(active.values()); }
        for (Job job : jobs) abort(job, CLOSED);
        workers.shutdownNow(); timers.shutdownNow(); tokens.close(); files.close();
        // Successful assets remain caller-owned until release(); closing never performs a batch deletion.
    }
    @Override public String toString() { return "AlibabaNlsSpeech[credentials=REDACTED]"; }
}
