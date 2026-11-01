package cn.bigfish.speech;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static cn.bigfish.speech.NlsTokenProvider.Status.*;

class NlsTokenProviderTest {
    private static final Duration SECOND = Duration.ofSeconds(1);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final class MutableClock extends Clock {
        Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void staticTokenDoesNotInventValidityAndRemembersRejection() {
        MutableClock clock = new MutableClock();
        try (var provider = new StaticNlsTokenProvider("local-fixture-only", null, clock)) {
            assertThat(provider.status()).isEqualTo(UNVERIFIED);
            var token = provider.acquire(SECOND).toCompletableFuture().join();
            assertThat(token.toString()).doesNotContain("local-fixture-only");
            provider.accepted(token); assertThat(provider.status()).isEqualTo(ACCEPTED);
            provider.rejected(token); assertThat(provider.status()).isEqualTo(REJECTED);
            failure(provider.acquire(SECOND), "NLS_TOKEN_REJECTED");
        }
        var expiring = new StaticNlsTokenProvider("local-fixture-only", NOW.plusSeconds(1), clock);
        clock.now = NOW.plusSeconds(2);
        assertThat(expiring.status()).isEqualTo(EXPIRED);
        failure(expiring.acquire(SECOND), "NLS_TOKEN_EXPIRED");
        expiring.close(); assertThat(expiring.status()).isEqualTo(CLOSED);
        failure(expiring.acquire(SECOND), "NLS_CLOSED");
    }
    @Test void refreshIsSingleFlightAndRefreshesBeforeExpiry() throws Exception {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<CompletableFuture<NlsTokenProvider.Token>> issued = new AtomicReference<>(new CompletableFuture<>());
        try (var provider = new RefreshingNlsTokenProvider(d -> { calls.incrementAndGet(); return issued.get(); }, clock, SECOND, Duration.ofSeconds(30))) {
            List<CompletionStage<NlsTokenProvider.Token>> waiters = new ArrayList<>();
            for (int i = 0; i < 10; i++) waiters.add(provider.acquire(SECOND));
            await(() -> calls.get() == 1);
            assertThat(provider.status()).isEqualTo(REFRESHING);
            var first = new NlsTokenProvider.Token("local-fixture-a", NOW.plusSeconds(60));
            issued.get().complete(first);
            for (var waiter : waiters) assertThat(waiter.toCompletableFuture().get(2, TimeUnit.SECONDS)).isSameAs(first);
            assertThat(provider.acquire(SECOND).toCompletableFuture().join()).isSameAs(first);
            provider.accepted(first); assertThat(provider.status()).isEqualTo(ACCEPTED);
            clock.now = NOW.plusSeconds(31);
            issued.set(new CompletableFuture<>());
            var next = provider.acquire(SECOND);
            await(() -> calls.get() == 2);
            var second = new NlsTokenProvider.Token("local-fixture-b", clock.now.plusSeconds(60));
            issued.get().complete(second);
            assertThat(next.toCompletableFuture().get(2, TimeUnit.SECONDS)).isSameAs(second);
            provider.rejected(first); // A late rejection of an old token cannot invalidate the replacement.
            assertThat(provider.acquire(SECOND).toCompletableFuture().join()).isSameAs(second);
            provider.rejected(second); assertThat(provider.status()).isEqualTo(REJECTED);
        }
    }
    @Test void individualWaiterTimeoutDoesNotCancelOtherWaiters() throws Exception {
        CompletableFuture<NlsTokenProvider.Token> source = new CompletableFuture<>();
        try (var provider = new RefreshingNlsTokenProvider(d -> source, Clock.fixed(NOW, ZoneOffset.UTC), SECOND, Duration.ZERO)) {
            var first = provider.acquire(SECOND);
            var shortWaiter = provider.acquire(Duration.ofMillis(30));
            failure(shortWaiter, "NLS_TOKEN_REFRESH_TIMEOUT");
            assertThat(source.isCancelled()).isFalse();
            source.complete(new NlsTokenProvider.Token("local-fixture-only", NOW.plusSeconds(60)));
            assertThat(first.toCompletableFuture().get(1, TimeUnit.SECONDS)).isNotNull();
        }
    }
    @Test void refreshDeadlineCancelsUpstreamAndRecoveryIsBounded() throws Exception {
        AtomicReference<CompletableFuture<NlsTokenProvider.Token>> source = new AtomicReference<>(new CompletableFuture<>());
        try (var provider = new RefreshingNlsTokenProvider(d -> source.get(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(60), Duration.ZERO)) {
            failure(provider.acquire(SECOND), "NLS_TOKEN_REFRESH_TIMEOUT");
            assertThat(source.get().isCancelled()).isTrue();
            failure(provider.acquire(SECOND), "NLS_COOLDOWN");
            Thread.sleep(220);
            source.set(CompletableFuture.completedFuture(new NlsTokenProvider.Token("local-fixture-only", NOW.plusSeconds(60))));
            assertThat(provider.acquire(SECOND).toCompletableFuture().get(1, TimeUnit.SECONDS)).isNotNull();
        }
    }
    @Test void issuerErrorsAreRedactedAndCloseCompletesWaiters() {
        try (var provider = new RefreshingNlsTokenProvider(d -> { throw new IllegalStateException("sensitive-provider-response"); })) {
            failure(provider.acquire(SECOND), "NLS_TOKEN_REFRESH_FAILED");
        }
        var provider = new RefreshingNlsTokenProvider(d -> new CompletableFuture<>());
        var waiter = provider.acquire(SECOND);
        provider.close();
        failure(waiter, "NLS_CLOSED");
        failure(provider.acquire(SECOND), "NLS_CLOSED");
    }
    @Test void cancelledWaitersReleaseTheirSlotsWithoutDuplicatingRefresh() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<NlsTokenProvider.Token> source = new CompletableFuture<>();
        try (var provider = new RefreshingNlsTokenProvider(d -> { calls.incrementAndGet(); return source; })) {
            var survivor = provider.acquire(SECOND);
            for (int i = 0; i < 100; i++) assertThat(provider.acquire(SECOND).toCompletableFuture().cancel(true)).isTrue();
            await(() -> calls.get() == 1);
            assertThat(source.isCancelled()).isFalse();
            source.complete(new NlsTokenProvider.Token("local-fixture-only", Instant.now().plusSeconds(120)));
            assertThat(survivor.toCompletableFuture().get(1, TimeUnit.SECONDS)).isNotNull();
            assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test void invalidExpiryAndWaiterFloodAreRejected() {
        CompletableFuture<NlsTokenProvider.Token> source = new CompletableFuture<>();
        try (var provider = new RefreshingNlsTokenProvider(d -> source)) {
            List<CompletionStage<NlsTokenProvider.Token>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) pending.add(provider.acquire(SECOND));
            failure(provider.acquire(SECOND), "NLS_BUSY");
            source.complete(new NlsTokenProvider.Token("local-fixture-only", null));
            pending.forEach(f -> failure(f, "NLS_TOKEN_REFRESH_FAILED"));
        }
    }
    private static void failure(CompletionStage<?> future, String message) {
        assertThatThrownBy(() -> future.toCompletableFuture().get(2, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class).cause().isInstanceOf(SpeechFailure.class).hasMessage(message).hasNoCause();
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
