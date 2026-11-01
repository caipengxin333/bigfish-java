package cn.bigfish.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import static cn.bigfish.speech.SpeechFailure.Code.*;

/** Backend injection factory. STATIC is the default; modes never fall back to another credential/account. */
public final class NlsTokenProviders {
    public enum Mode { STATIC, RAM }
    public record Snapshot(Mode mode, NlsTokenProvider.Status status, Instant expiresAt, Long remainingSeconds,
                           RefreshingNlsTokenProvider.RefreshResult refreshResult, SpeechFailure.Code lastError,
                           Instant lastAttemptAt, Instant lastSuccessAt, int consecutiveFailures,
                           long retryAfterMillis, int waiters, long attempts) {}
    private NlsTokenProviders() {}
    public static Handle create(boolean enabled, Function<String, String> environment, ObjectMapper mapper) {
        if (!enabled) throw new SpeechFailure(DISABLED); // Do not even read credentials when disabled.
        Mode mode = mode(environment.apply("BIGFISH_ALIYUN_NLS_TOKEN_MODE"));
        if (mode == Mode.STATIC) return new Handle(mode,
            new StaticNlsTokenProvider(environment.apply("BIGFISH_ALIYUN_NLS_TOKEN"), null), null);
        var client = AlibabaNlsTokenClient.create(true, environment.apply("BIGFISH_ALIYUN_ACCESS_KEY_ID"),
            environment.apply("BIGFISH_ALIYUN_ACCESS_KEY_SECRET"), mapper);
        return refreshing(client);
    }
    static Handle refreshing(AlibabaNlsTokenClient client) {
        return new Handle(Mode.RAM, new RefreshingNlsTokenProvider(client::issue), client);
    }
    public static Mode mode(String configured) {
        if (configured == null || configured.isBlank()) return Mode.STATIC;
        try { return Mode.valueOf(configured.trim()); }
        catch (IllegalArgumentException ignored) { throw new SpeechFailure(TOKEN_MODE_INVALID); }
    }
    /** Only snapshot(), never Token or credential objects, belongs in a future management DTO. */
    public static final class Handle implements NlsTokenProvider {
        private final Mode mode;
        private final NlsTokenProvider provider;
        private final AlibabaNlsTokenClient client;
        private Handle(Mode mode, NlsTokenProvider provider, AlibabaNlsTokenClient client) {
            this.mode = mode; this.provider = provider; this.client = client;
        }
        @Override public CompletionStage<Token> acquire(Duration deadline) { return provider.acquire(deadline); }
        @Override public void rejected(Token token) { provider.rejected(token); }
        @Override public void accepted(Token token) { provider.accepted(token); }
        @Override public Status status() { return provider.status(); }
        public Snapshot snapshot() {
            if (provider instanceof RefreshingNlsTokenProvider refreshing) {
                var d = refreshing.diagnostics();
                return new Snapshot(mode, d.status(), d.expiresAt(), d.remainingSeconds(), d.refreshResult(), d.lastError(),
                    d.lastAttemptAt(), d.lastSuccessAt(), d.consecutiveFailures(), d.retryAfterMillis(), d.waiters(), d.attempts());
            }
            Status status = provider.status();
            return new Snapshot(mode, status, null, null, RefreshingNlsTokenProvider.RefreshResult.NOT_REQUESTED,
                status == Status.REJECTED ? TOKEN_REJECTED : status == Status.EXPIRED ? TOKEN_EXPIRED : null, null, null, 0, 0, 0, 0);
        }
        @Override public void close() { provider.close(); if (client != null) client.close(); }
        @Override public String toString() { return "NlsTokenProviderHandle[mode=" + mode + ", credentials=REDACTED]"; }
    }
}
