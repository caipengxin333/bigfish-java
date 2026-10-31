package cn.bigfish.speech;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Single-flight refresh infrastructure. The supplied issuer must implement real CreateToken separately. */
public final class RefreshingNlsTokenProvider implements NlsTokenProvider {
    private final Function<Duration, CompletionStage<Token>> issuer;
    private final Clock clock;
    private final Duration refreshLimit, refreshAhead;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("nls-token-issuer").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().daemon().name("nls-token-deadline").factory());
    private Token cached;
    private boolean accepted, closed;
    private Flight flight;
    private long retryAfter;
    private static final class Flight {
        final Map<CompletableFuture<Token>, ScheduledFuture<?>> waiters = new IdentityHashMap<>();
        CompletableFuture<Token> upstream;
        Future<?> invocation;
        ScheduledFuture<?> timeout;
    }
    public RefreshingNlsTokenProvider(Function<Duration, CompletionStage<Token>> issuer) {
        this(issuer, Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(30));
    }
    RefreshingNlsTokenProvider(Function<Duration, CompletionStage<Token>> issuer, Clock clock, Duration limit, Duration ahead) {
        if (issuer == null || limit.isNegative() || limit.isZero() || limit.compareTo(Duration.ofSeconds(10)) > 0 || ahead.isNegative())
            throw new IllegalArgumentException("NLS_TOKEN_OPTIONS");
        this.issuer = issuer; this.clock = clock; refreshLimit = limit; refreshAhead = ahead;
        timers.setRemoveOnCancelPolicy(true);
    }
    @Override public synchronized CompletionStage<Token> acquire(Duration deadline) {
        if (closed) return failed(SpeechFailure.Code.CLOSED);
        if (deadline == null || deadline.isZero() || deadline.isNegative()) return failed(SpeechFailure.Code.DEADLINE_EXCEEDED);
        if (cached != null && cached.expiresAt().isAfter(clock.instant().plus(refreshAhead))) return CompletableFuture.completedFuture(cached);
        if (flight != null && flight.waiters.size() >= 16) return failed(SpeechFailure.Code.BUSY);
        if (flight == null) {
            if (System.nanoTime() < retryAfter) return failed(SpeechFailure.Code.COOLDOWN);
            Duration budget = deadline.compareTo(refreshLimit) < 0 ? deadline : refreshLimit;
            Flight next = new Flight(); flight = next;
            next.timeout = timers.schedule(() -> finish(next, null, SpeechFailure.Code.TOKEN_REFRESH_TIMEOUT), budget.toNanos(), TimeUnit.NANOSECONDS);
            try {
                next.invocation = worker.submit(() -> {
                    try {
                        CompletionStage<Token> issued = issuer.apply(budget);
                        synchronized (this) {
                            if (flight != next || closed) { if (issued != null) issued.toCompletableFuture().cancel(true); return; }
                            next.upstream = issued.toCompletableFuture();
                        }
                        next.upstream.whenComplete((token, error) -> finish(next, token, error == null ? null : SpeechFailure.Code.TOKEN_REFRESH_FAILED));
                    } catch (Exception ignored) { finish(next, null, SpeechFailure.Code.TOKEN_REFRESH_FAILED); }
                });
            } catch (RejectedExecutionException ignored) { finish(next, null, SpeechFailure.Code.TOKEN_REFRESH_FAILED); }
        }
        Flight selected = flight;
        if (selected == null) return failed(SpeechFailure.Code.TOKEN_REFRESH_FAILED);
        CompletableFuture<Token> result = new CompletableFuture<>();
        long waitNanos = deadline.compareTo(refreshLimit) < 0 ? deadline.toNanos() : refreshLimit.toNanos();
        ScheduledFuture<?> timeout = timers.schedule(() -> result.completeExceptionally(new SpeechFailure(SpeechFailure.Code.TOKEN_REFRESH_TIMEOUT)), waitNanos, TimeUnit.NANOSECONDS);
        selected.waiters.put(result, timeout);
        result.whenComplete((v, e) -> {
            timeout.cancel(false);
            synchronized (this) { selected.waiters.remove(result); }
        });
        return result;
    }
    private synchronized void finish(Flight current, Token token, SpeechFailure.Code error) {
        if (flight != current) return;
        flight = null; current.timeout.cancel(false);
        if (error == null && (token == null || token.expiresAt() == null || !token.expiresAt().isAfter(clock.instant().plus(refreshAhead))))
            error = SpeechFailure.Code.TOKEN_REFRESH_FAILED;
        if (error == null) { cached = token; accepted = false; }
        else {
            retryAfter = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            if (current.upstream != null) current.upstream.cancel(true);
            if (current.invocation != null) current.invocation.cancel(true);
        }
        for (var waiter : List.copyOf(current.waiters.keySet())) {
            if (error == null) waiter.complete(token);
            else waiter.completeExceptionally(new SpeechFailure(error));
        }
    }
    private CompletionStage<Token> failed(SpeechFailure.Code code) { return CompletableFuture.failedFuture(new SpeechFailure(code)); }
    @Override public synchronized void rejected(Token token) { if (cached == token) { cached = null; accepted = false; } }
    @Override public synchronized void accepted(Token token) { if (cached == token) accepted = true; }
    @Override public synchronized Status status() {
        if (closed) return Status.CLOSED;
        if (flight != null) return Status.REFRESHING;
        if (cached == null) return Status.MISSING;
        if (cached.expired(clock)) return Status.EXPIRED;
        return accepted ? Status.ACCEPTED : Status.UNVERIFIED;
    }
    @Override public synchronized void close() {
        closed = true;
        if (flight != null) finish(flight, null, SpeechFailure.Code.CLOSED);
        cached = null; worker.shutdownNow(); timers.shutdownNow();
    }
    @Override public String toString() { return "RefreshingNlsTokenProvider[REDACTED]"; }
}
