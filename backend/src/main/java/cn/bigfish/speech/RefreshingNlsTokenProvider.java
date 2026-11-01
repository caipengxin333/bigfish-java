package cn.bigfish.speech;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static cn.bigfish.speech.SpeechFailure.Code.*;

/** Existing single-flight refresher; refreshes on demand, never from construction/status polling. */
public final class RefreshingNlsTokenProvider implements NlsTokenProvider {
    public enum RefreshResult { NOT_REQUESTED, RUNNING, SUCCESS, FAILED, USING_VALID_CACHED, CANCELLED }
    public record Diagnostics(Status status, Instant expiresAt, Long remainingSeconds, RefreshResult refreshResult,
                              SpeechFailure.Code lastError, Instant lastAttemptAt, Instant lastSuccessAt,
                              int consecutiveFailures, long retryAfterMillis, int waiters, long attempts) {}
    private final Function<Duration, CompletionStage<Token>> issuer;
    private final Clock clock;
    private final LongSupplier ticker;
    private final Duration refreshLimit, refreshAhead;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("nls-token-issuer").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("nls-token-deadline").factory());
    private Token cached;
    private String rejectedValue;
    private boolean accepted, closed, expired;
    private Flight flight;
    private long retryAfter, cachedAt, lifetime, attempts;
    private int failures;
    private RefreshResult refreshResult = RefreshResult.NOT_REQUESTED;
    private SpeechFailure.Code lastError;
    private Instant lastAttemptAt, lastSuccessAt;
    private record Waiter(long began, long budget, ScheduledFuture<?> timeout) {}
    private static final class Flight {
        final Map<CompletableFuture<Token>, Waiter> waiters = new IdentityHashMap<>();
        CompletableFuture<Token> upstream;
        Future<?> invocation;
        ScheduledFuture<?> timeout;
    }
    public RefreshingNlsTokenProvider(Function<Duration, CompletionStage<Token>> issuer) {
        this(issuer, Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(30));
    }
    RefreshingNlsTokenProvider(Function<Duration, CompletionStage<Token>> issuer, Clock clock, Duration limit, Duration ahead) {
        this(issuer, clock, limit, ahead, System::nanoTime);
    }
    RefreshingNlsTokenProvider(Function<Duration, CompletionStage<Token>> issuer, Clock clock, Duration limit, Duration ahead, LongSupplier ticker) {
        if (issuer == null || limit.isNegative() || limit.isZero() || limit.compareTo(Duration.ofSeconds(10)) > 0 || ahead.isNegative())
            throw new IllegalArgumentException("NLS_TOKEN_OPTIONS");
        this.issuer = issuer; this.clock = clock; refreshLimit = limit; refreshAhead = ahead; this.ticker = ticker;
        timers.setRemoveOnCancelPolicy(true);
    }
    @Override public synchronized CompletionStage<Token> acquire(Duration deadline) {
        if (closed) return failed(CLOSED);
        if (deadline == null || deadline.isZero() || deadline.isNegative()) return failed(DEADLINE_EXCEEDED);
        // The audio adapter already caps at 120 seconds; cap arithmetic, never extend the caller's wait.
        long budget = Math.min(deadline.compareTo(Duration.ofSeconds(120)) > 0 ? TimeUnit.SECONDS.toNanos(120) : deadline.toNanos(), Long.MAX_VALUE / 2);
        if (remainingNanos() > Math.max(refreshAhead.toNanos(), budget + TimeUnit.SECONDS.toNanos(5)))
            return CompletableFuture.completedFuture(cached);
        if (flight != null && flight.waiters.size() >= 16) return failed(BUSY);
        if (flight == null) {
            if (ticker.getAsLong() < retryAfter) {
                if (transientFailure(lastError) && canUseCached(budget)) {
                    refreshResult = RefreshResult.USING_VALID_CACHED;
                    return CompletableFuture.completedFuture(cached);
                }
                return failed(COOLDOWN);
            }
            Flight next = new Flight(); flight = next;
            refreshResult = RefreshResult.RUNNING; lastAttemptAt = clock.instant(); attempts++;
            // Each waiter has its own deadline; the first short caller cannot shorten later waiters.
            next.timeout = timers.schedule(() -> finish(next, null, TOKEN_REFRESH_TIMEOUT), refreshLimit.toNanos(), TimeUnit.NANOSECONDS);
            try {
                next.invocation = worker.submit(() -> {
                    try {
                        CompletionStage<Token> issued = issuer.apply(refreshLimit);
                        synchronized (this) {
                            if (flight != next || closed) { if (issued != null) issued.toCompletableFuture().cancel(true); return; }
                            next.upstream = issued.toCompletableFuture();
                        }
                        next.upstream.whenComplete((token, error) -> finish(next, token, error == null ? null : safeRefreshCode(error)));
                    } catch (Exception error) { finish(next, null, safeRefreshCode(error)); }
                });
            } catch (RejectedExecutionException ignored) { finish(next, null, TOKEN_REFRESH_FAILED); }
        }
        Flight selected = flight;
        if (selected == null) return failed(lastError == null ? TOKEN_REFRESH_FAILED : lastError);
        CompletableFuture<Token> result = new CompletableFuture<>();
        ScheduledFuture<?> timeout = timers.schedule(() -> result.completeExceptionally(new SpeechFailure(TOKEN_REFRESH_TIMEOUT)),
            Math.min(budget, refreshLimit.toNanos()), TimeUnit.NANOSECONDS);
        selected.waiters.put(result, new Waiter(ticker.getAsLong(), budget, timeout));
        result.whenComplete((v, e) -> {
            timeout.cancel(false);
            synchronized (this) {
                selected.waiters.remove(result);
                if (flight == selected && selected.waiters.isEmpty()) finish(selected, null, CANCELLED);
            }
        });
        return result;
    }
    private synchronized void finish(Flight current, Token token, SpeechFailure.Code error) {
        if (flight != current) return; // Discard cancelled/timed-out/old callback generations.
        flight = null; current.timeout.cancel(false);
        long newLifetime = 0;
        if (error == null) {
            try {
                if (token == null || token.expiresAt() == null || !token.expiresAt().isAfter(clock.instant().plus(refreshAhead)))
                    throw new IllegalArgumentException();
                newLifetime = Duration.between(clock.instant(), token.expiresAt()).toNanos();
                if (Objects.equals(rejectedValue, token.value())) error = TOKEN_REJECTED;
            } catch (Exception ignored) { error = TOKEN_REFRESH_FAILED; }
        }
        if (error == null) {
            cached = token; cachedAt = ticker.getAsLong(); lifetime = newLifetime; expired = false;
            rejectedValue = null; accepted = false; failures = 0; retryAfter = 0;
            lastError = null; lastSuccessAt = clock.instant(); refreshResult = RefreshResult.SUCCESS;
        } else {
            lastError = error;
            if (error != CANCELLED && error != CLOSED) failures = Math.min(31, failures + 1);
            long delay = Math.min(30_000, 200L << Math.min(8, Math.max(0, failures - 1)));
            retryAfter = ticker.getAsLong() + TimeUnit.MILLISECONDS.toNanos(delay);
            refreshResult = error == CANCELLED || error == CLOSED ? RefreshResult.CANCELLED : RefreshResult.FAILED;
            if (current.upstream != null) current.upstream.cancel(true);
            if (current.invocation != null) current.invocation.cancel(true);
            worker.purge();
        }
        for (var entry : List.copyOf(current.waiters.entrySet())) {
            var waiter = entry.getValue();
            long remaining = waiter.budget() - (ticker.getAsLong() - waiter.began());
            if (remaining <= 0) entry.getKey().completeExceptionally(new SpeechFailure(TOKEN_REFRESH_TIMEOUT));
            else if (error == null) entry.getKey().complete(token);
            else if (transientFailure(error) && canUseCached(remaining)) {
                refreshResult = RefreshResult.USING_VALID_CACHED; entry.getKey().complete(cached);
            } else entry.getKey().completeExceptionally(new SpeechFailure(error));
        }
    }
    private long remainingNanos() {
        if (cached == null || expired) return 0;
        long wall;
        try { wall = Duration.between(clock.instant(), cached.expiresAt()).toNanos(); }
        catch (ArithmeticException ignored) { expired = true; return 0; }
        long remaining = Math.min(wall, lifetime - (ticker.getAsLong() - cachedAt));
        if (remaining <= 0) expired = true; // A subsequent clock rollback cannot resurrect a token.
        return Math.max(0, remaining);
    }
    private boolean canUseCached(long required) { return remainingNanos() > required + TimeUnit.SECONDS.toNanos(5); }
    private static boolean transientFailure(SpeechFailure.Code code) {
        return code == TOKEN_REFRESH_TIMEOUT || code == TOKEN_TRANSPORT_FAILED || code == TOKEN_RATE_LIMITED || code == TOKEN_PROVIDER_UNAVAILABLE;
    }
    private static SpeechFailure.Code safeRefreshCode(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) error = error.getCause();
        return error instanceof SpeechFailure known ? known.code() : TOKEN_REFRESH_FAILED;
    }
    private CompletionStage<Token> failed(SpeechFailure.Code code) { return CompletableFuture.failedFuture(new SpeechFailure(code)); }
    @Override public synchronized void rejected(Token token) {
        // Different wrapper objects for the same provider token are still the same credential.
        if (cached != null && token != null && cached.value().equals(token.value())) {
            rejectedValue = cached.value(); cached = null; accepted = false; lastError = TOKEN_REJECTED;
        }
    }
    @Override public synchronized void accepted(Token token) { if (cached == token && remainingNanos() > 0) accepted = true; }
    @Override public synchronized Status status() {
        if (closed) return Status.CLOSED;
        if (flight != null) return Status.REFRESHING;
        if (cached == null) return rejectedValue == null ? Status.MISSING : Status.REJECTED;
        if (remainingNanos() == 0) return Status.EXPIRED;
        return accepted ? Status.ACCEPTED : Status.UNVERIFIED;
    }
    public synchronized Diagnostics diagnostics() {
        Long remaining = cached == null ? null : TimeUnit.NANOSECONDS.toSeconds(remainingNanos());
        return new Diagnostics(status(), cached == null ? null : cached.expiresAt(), remaining, refreshResult, lastError,
            lastAttemptAt, lastSuccessAt, failures, Math.max(0, TimeUnit.NANOSECONDS.toMillis(retryAfter - ticker.getAsLong())),
            flight == null ? 0 : flight.waiters.size(), attempts);
    }
    @Override public synchronized void close() {
        closed = true;
        if (flight != null) finish(flight, null, CLOSED);
        cached = null; rejectedValue = null; worker.shutdownNow(); timers.shutdownNow();
    }
    @Override public String toString() { return "RefreshingNlsTokenProvider[REDACTED]"; }
}
