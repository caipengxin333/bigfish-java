package cn.bigfish.speech;

import java.time.*;
import java.util.concurrent.*;

/** No refresh and no invented expiry. Rejected environment tokens require replacement/restart. */
public final class StaticNlsTokenProvider implements NlsTokenProvider {
    private final Token token;
    private final Clock clock;
    private Status state = Status.UNVERIFIED;
    public StaticNlsTokenProvider(String value, Instant expiresAt) { this(value, expiresAt, Clock.systemUTC()); }
    StaticNlsTokenProvider(String value, Instant expiresAt, Clock clock) {
        this.token = new Token(value, expiresAt); this.clock = clock;
    }
    @Override public synchronized CompletionStage<Token> acquire(Duration deadline) {
        if (deadline == null || deadline.isNegative() || deadline.isZero()) return failure(SpeechFailure.Code.DEADLINE_EXCEEDED);
        if (state == Status.CLOSED) return failure(SpeechFailure.Code.CLOSED);
        if (state == Status.REJECTED) return failure(SpeechFailure.Code.TOKEN_REJECTED);
        if (token.expired(clock)) return failure(SpeechFailure.Code.TOKEN_EXPIRED);
        return CompletableFuture.completedFuture(token);
    }
    private CompletionStage<Token> failure(SpeechFailure.Code code) { return CompletableFuture.failedFuture(new SpeechFailure(code)); }
    @Override public synchronized void rejected(Token value) { if (value == token && state != Status.CLOSED) state = Status.REJECTED; }
    @Override public synchronized void accepted(Token value) { if (value == token && state == Status.UNVERIFIED) state = Status.ACCEPTED; }
    @Override public synchronized Status status() {
        if (state == Status.CLOSED || state == Status.REJECTED) return state;
        return token.expired(clock) ? Status.EXPIRED : state;
    }
    @Override public synchronized void close() { state = Status.CLOSED; }
    @Override public String toString() { return "StaticNlsTokenProvider[REDACTED]"; }
}
