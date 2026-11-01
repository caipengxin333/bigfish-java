package cn.bigfish.speech;

import java.time.*;
import java.util.concurrent.CompletionStage;

/** Backend-only credential source. Implementations must not expose token values in diagnostics. */
public interface NlsTokenProvider extends AutoCloseable {
    enum Status { MISSING, UNVERIFIED, ACCEPTED, EXPIRED, REJECTED, REFRESHING, CLOSED }
    CompletionStage<Token> acquire(Duration deadline);
    void rejected(Token token);
    void accepted(Token token);
    Status status();
    @Override default void close() {}

    final class Token {
        private final String value;
        private final Instant expiresAt;
        private final long observedAt = System.nanoTime();
        private final Long verifiedLifetime;
        /** null expiry is explicitly unknown, not an invented validity period. */
        public Token(String value, Instant expiresAt) { this(value, expiresAt, null); }
        /** Optional lifetime conservatively anchored to the official response Date, never a fixed TTL. */
        Token(String value, Instant expiresAt, Duration verifiedLifetime) {
            if (value == null || value.isBlank() || value.length() > 4096 || value.chars().anyMatch(c -> c < 33 || c > 126))
                throw new SpeechFailure(SpeechFailure.Code.MISSING_CREDENTIALS);
            this.value = value; this.expiresAt = expiresAt;
            this.verifiedLifetime = verifiedLifetime == null ? null : Math.max(0, verifiedLifetime.toNanos());
        }
        long verifiedRemainingNanos() {
            return verifiedLifetime == null ? Long.MAX_VALUE : Math.max(0, verifiedLifetime - (System.nanoTime() - observedAt));
        }
        String value() { return value; }
        public Instant expiresAt() { return expiresAt; }
        boolean expired(Clock clock) { return expiresAt != null && !expiresAt.isAfter(clock.instant()); }
        @Override public String toString() { return "NlsToken[value=REDACTED, expiryKnown=" + (expiresAt != null) + "]"; }
    }
}
