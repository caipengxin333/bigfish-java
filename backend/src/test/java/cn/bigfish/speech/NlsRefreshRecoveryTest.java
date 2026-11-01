package cn.bigfish.speech;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static cn.bigfish.speech.SpeechFailure.Code.*;
import static cn.bigfish.speech.AlibabaNlsTokenClientTest.failure;

class NlsRefreshRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final Duration BUDGET = Duration.ofSeconds(2);
    private static final class TestClock extends Clock {
        Instant now = NOW;
        final AtomicLong nano = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long ms) { now = now.plusMillis(ms); nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms)); }
    }
    private static final class Rig implements AutoCloseable {
        final TestClock clock = new TestClock();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<CompletableFuture<NlsTokenProvider.Token>> source = new AtomicReference<>();
        final RefreshingNlsTokenProvider provider;
        Rig() { this(Duration.ofSeconds(1)); }
        Rig(Duration limit) {
            provider = new RefreshingNlsTokenProvider(d -> { calls.incrementAndGet(); return source.get(); }, clock, limit, Duration.ofSeconds(30), clock.nano::get);
        }
        NlsTokenProvider.Token token(String name) { return new NlsTokenProvider.Token("fixture-" + name, clock.instant().plusSeconds(60)); }
        NlsTokenProvider.Token prime() throws Exception {
            var token = token("old"); source.set(CompletableFuture.completedFuture(token));
            assertThat(provider.acquire(BUDGET).toCompletableFuture().get(2,TimeUnit.SECONDS)).isSameAs(token);
            provider.accepted(token); return token;
        }
        public void close() { provider.close(); }
    }
    @Test void constructionAndPollingNeverIssueTokens() {
        try (Rig rig = new Rig()) {
            for (int i=0;i<100;i++) { rig.provider.status(); rig.provider.diagnostics(); }
            assertThat(rig.calls.get()).isZero(); assertThat(rig.provider.diagnostics().attempts()).isZero();
        }
    }
    @Test void firstShortWaiterDoesNotCancelLongerWaiter() throws Exception {
        try (Rig rig = new Rig()) {
            rig.source.set(new CompletableFuture<>());
            var shortOne = rig.provider.acquire(Duration.ofMillis(40));
            var longOne = rig.provider.acquire(BUDGET);
            failure(shortOne, TOKEN_REFRESH_TIMEOUT);
            assertThat(rig.source.get().isCancelled()).isFalse();
            var token = rig.token("new"); rig.source.get().complete(token);
            assertThat(longOne.toCompletableFuture().get(1,TimeUnit.SECONDS)).isSameAs(token);
            assertThat(rig.calls.get()).isEqualTo(1);
        }
    }
    @Test void temporaryRefreshFailureReusesOnlySufficientlyValidCachedToken() throws Exception {
        try (Rig rig = new Rig()) {
            var old = rig.prime(); rig.clock.advance(31_000);
            rig.source.set(CompletableFuture.failedFuture(new SpeechFailure(TOKEN_PROVIDER_UNAVAILABLE)));
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().get(1,TimeUnit.SECONDS)).isSameAs(old);
            assertThat(rig.provider.diagnostics().refreshResult()).isEqualTo(RefreshingNlsTokenProvider.RefreshResult.USING_VALID_CACHED);
            assertThat(rig.provider.diagnostics().lastError()).isEqualTo(TOKEN_PROVIDER_UNAVAILABLE);
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().join()).isSameAs(old);
            assertThat(rig.calls.get()).isEqualTo(2);
            rig.clock.advance(25_000); // Four seconds left cannot cover caller budget plus reserve.
            failure(rig.provider.acquire(BUDGET), TOKEN_PROVIDER_UNAVAILABLE);
            rig.clock.advance(5000);
            assertThat(rig.provider.status()).isEqualTo(NlsTokenProvider.Status.EXPIRED);
            failure(rig.provider.acquire(BUDGET), TOKEN_PROVIDER_UNAVAILABLE);
        }
    }
    @Test void authFailuresNeverUseOldTokenAndBackoffIncreasesThenResets() throws Exception {
        try (Rig rig = new Rig()) {
            rig.prime(); rig.clock.advance(31_000);
            rig.source.set(CompletableFuture.failedFuture(new SpeechFailure(TOKEN_AUTH_REJECTED)));
            failure(rig.provider.acquire(BUDGET), TOKEN_AUTH_REJECTED);
            assertThat(rig.provider.diagnostics().retryAfterMillis()).isEqualTo(200);
            failure(rig.provider.acquire(BUDGET), COOLDOWN);
            rig.clock.advance(201); failure(rig.provider.acquire(BUDGET), TOKEN_AUTH_REJECTED);
            assertThat(rig.provider.diagnostics().retryAfterMillis()).isEqualTo(400);
            rig.clock.advance(401);
            var replacement = rig.token("replacement"); rig.source.set(CompletableFuture.completedFuture(replacement));
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().get(1,TimeUnit.SECONDS)).isSameAs(replacement);
            assertThat(rig.provider.diagnostics().consecutiveFailures()).isZero();
            assertThat(rig.provider.diagnostics().lastError()).isNull();
        }
    }
    @Test void rollbackDoesNotExtendMonotonicLifetimeOrResurrectExpiredToken() throws Exception {
        try (Rig rig = new Rig()) {
            rig.prime(); rig.clock.nano.addAndGet(TimeUnit.SECONDS.toNanos(61));
            rig.clock.now = NOW.minusSeconds(3600);
            assertThat(rig.provider.status()).isEqualTo(NlsTokenProvider.Status.EXPIRED);
            assertThat(rig.provider.diagnostics().remainingSeconds()).isZero();
            rig.clock.now = NOW;
            assertThat(rig.provider.status()).isEqualTo(NlsTokenProvider.Status.EXPIRED);
        }
        try (Rig rig = new Rig()) {
            rig.prime(); rig.clock.now = NOW.plusSeconds(61);
            assertThat(rig.provider.status()).isEqualTo(NlsTokenProvider.Status.EXPIRED);
            rig.clock.now = NOW;
            assertThat(rig.provider.status()).isEqualTo(NlsTokenProvider.Status.EXPIRED);
        }
    }
    @Test void rejectedOldValueCannotBeRepublishedButLateOldCallbackCannotInvalidateNewValue() throws Exception {
        try (Rig rig = new Rig()) {
            var old = rig.prime(); rig.clock.advance(31_000);
            rig.source.set(new CompletableFuture<>());
            var pending = rig.provider.acquire(BUDGET); await(() -> rig.calls.get() == 2);
            rig.provider.rejected(old);
            rig.source.get().complete(new NlsTokenProvider.Token(old.value(), rig.clock.instant().plusSeconds(60)));
            failure(pending, TOKEN_REJECTED);
            rig.clock.advance(201);
            var next = rig.token("next"); rig.source.set(CompletableFuture.completedFuture(next));
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().get(1,TimeUnit.SECONDS)).isSameAs(next);
            rig.provider.accepted(next); rig.provider.rejected(old); rig.provider.accepted(old);
            assertThat(rig.provider.status()).isEqualTo(NlsTokenProvider.Status.ACCEPTED);
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().join()).isSameAs(next);
        }
    }
    @Test void cancelledGenerationCannotOverwriteReplacementEvenIfIssuerIgnoresCancellation() throws Exception {
        try (Rig rig = new Rig(Duration.ofMillis(80))) {
            CompletableFuture<NlsTokenProvider.Token> late = new CompletableFuture<>() {
                @Override public boolean cancel(boolean ignored) { return false; }
            };
            rig.source.set(late); failure(rig.provider.acquire(BUDGET), TOKEN_REFRESH_TIMEOUT);
            rig.clock.advance(201);
            var next = rig.token("next"); rig.source.set(CompletableFuture.completedFuture(next));
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().get(1,TimeUnit.SECONDS)).isSameAs(next);
            late.complete(rig.token("late"));
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().join()).isSameAs(next);
        }
    }
    @Test void cancellingLastWaiterCancelsIssuerAndLaterRequestRecovers() throws Exception {
        try (Rig rig = new Rig()) {
            rig.source.set(new CompletableFuture<>()); var waiter = rig.provider.acquire(BUDGET).toCompletableFuture();
            await(() -> rig.calls.get() == 1); waiter.cancel(true);
            await(() -> rig.source.get().isCancelled());
            assertThat(rig.provider.diagnostics().waiters()).isZero();
            rig.clock.advance(201);
            rig.source.set(CompletableFuture.completedFuture(rig.token("next")));
            assertThat(rig.provider.acquire(BUDGET).toCompletableFuture().get(1,TimeUnit.SECONDS)).isNotNull();
        }
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(2);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
