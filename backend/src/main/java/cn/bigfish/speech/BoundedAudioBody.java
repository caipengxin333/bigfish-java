package cn.bigfish.speech;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounded memory and an idle read timer; the adapter separately enforces the absolute deadline. */
final class BoundedAudioBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final ScheduledExecutorService timers;
    private final long idleNanos;
    private final int limit;
    private Flow.Subscription subscription;
    private ScheduledFuture<?> idle;
    BoundedAudioBody(int limit, Duration timeout, ScheduledExecutorService timers) {
        this.limit = limit; idleNanos = timeout.toNanos(); this.timers = timers;
    }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public synchronized void onSubscribe(Flow.Subscription next) {
        if (subscription != null || result.isDone()) { next.cancel(); return; }
        subscription = next;
        resetTimer();
        next.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> buffers) {
        if (result.isDone()) return;
        for (ByteBuffer b : buffers) {
            if (b.remaining() > limit - bytes.size()) { fail(SpeechFailure.Code.AUDIO_TOO_LARGE); return; }
            byte[] chunk = new byte[b.remaining()]; b.get(chunk); bytes.writeBytes(chunk);
        }
        resetTimer(); subscription.request(1);
    }
    private void resetTimer() {
        if (idle != null) idle.cancel(false);
        try { idle = timers.schedule(() -> fail(SpeechFailure.Code.READ_TIMEOUT), idleNanos, TimeUnit.NANOSECONDS); }
        catch (RejectedExecutionException ignored) { fail(SpeechFailure.Code.CLOSED); }
    }
    @Override public synchronized void onError(Throwable ignored) { fail(SpeechFailure.Code.TRANSPORT_FAILED); }
    @Override public synchronized void onComplete() {
        if (idle != null) idle.cancel(false);
        result.complete(bytes.toByteArray());
    }
    synchronized void fail(SpeechFailure.Code code) {
        if (result.completeExceptionally(new SpeechFailure(code))) {
            if (idle != null) idle.cancel(false);
            if (subscription != null) subscription.cancel();
        }
    }
}
