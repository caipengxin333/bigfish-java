package cn.bigfish.speech;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static cn.bigfish.speech.SpeechFailure.Code.*;

/** Token/error documents are bounded independently from audio. */
final class NlsTokenBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public synchronized void onSubscribe(Flow.Subscription value) {
        if (subscription != null || result.isDone()) { value.cancel(); return; }
        subscription = value; value.request(1);
    }
    @Override public synchronized void onNext(List<ByteBuffer> buffers) {
        if (result.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > 16_384 - bytes.size()) { fail(TOKEN_RESPONSE_INVALID); return; }
            byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable ignored) { fail(TOKEN_TRANSPORT_FAILED); }
    @Override public synchronized void onComplete() { result.complete(bytes.toByteArray()); }
    synchronized void fail(SpeechFailure.Code code) {
        if (result.completeExceptionally(new SpeechFailure(code)) && subscription != null) subscription.cancel();
    }
}
