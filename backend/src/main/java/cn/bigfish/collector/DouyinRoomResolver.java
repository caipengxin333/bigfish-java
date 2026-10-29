package cn.bigfish.collector;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/** One bounded, interruptible GET per attempt. No redirect, cookie jar, disk cache or browser execution. */
public final class DouyinRoomResolver {
    private final Clock clock;
    private final PageFetcher fetcher;
    private final AtomicInteger inFlight = new AtomicInteger();

    public DouyinRoomResolver() { this(Clock.systemUTC(), new JdkPageFetcher(Duration.ofSeconds(10), 2 * 1024 * 1024)); }
    DouyinRoomResolver(Clock clock, PageFetcher fetcher) { this.clock = clock; this.fetcher = fetcher; }

    public DouyinRoom resolve(String input, DouyinCredentials credentials) throws DouyinFailure, InterruptedException {
        DouyinRoomInput room = DouyinRoomInput.parse(input);
        if (credentials == null) throw new DouyinFailure(CREDENTIALS_MISSING);
        credentials.validate(clock.instant());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        inFlight.incrementAndGet();
        try {
            Page page = fetcher.get(room.pageUri(), credentials.headers(room));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            credentials.validate(clock.instant());
            if (page.status == 401 || page.status == 403) throw new DouyinFailure(AUTH_REQUIRED);
            if (page.status == 451) throw new DouyinFailure(REGION_RESTRICTED);
            if (page.status == 429) throw new DouyinFailure(RATE_LIMITED);
            if (page.status >= 300 && page.status < 400) throw new DouyinFailure(REDIRECT_REJECTED);
            if (page.status >= 500) throw new DouyinFailure(HTTP_UNAVAILABLE);
            if (page.status != 200 || !page.contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            }
            return DouyinRoomPage.parse(page.body, room);
        } finally { inFlight.decrementAndGet(); }
    }
    public int inFlightRequests() { return inFlight.get(); }

    @FunctionalInterface interface PageFetcher {
        Page get(URI uri, Map<String, String> headers) throws DouyinFailure, InterruptedException;
    }
    record Page(int status, String contentType, String body) {
        @Override public String toString() { return "Page[redacted]"; }
    }

    /** Package-private transport seam allows loopback tests without weakening production URL validation. */
    static final class JdkPageFetcher implements PageFetcher {
        private final Duration timeout;
        private final int maximum;
        JdkPageFetcher(Duration timeout, int maximum) { this.timeout = timeout; this.maximum = maximum; }

        @Override public Page get(URI uri, Map<String, String> headers) throws DouyinFailure, InterruptedException {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
            CompletableFuture<HttpResponse<byte[]>> pending = null;
            try {
                var builder = HttpRequest.newBuilder(uri).timeout(timeout).GET().header("Accept", "text/html");
                headers.forEach(builder::header);
                pending = client.sendAsync(builder.build(), ignored -> new LimitedBody(maximum));
                // Unlike ofInputStream, this deadline includes the entire response body.
                var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new Page(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                        new String(response.body(), StandardCharsets.UTF_8));
            } catch (TimeoutException exception) { throw new DouyinFailure(HTTP_TIMEOUT); }
            catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof DouyinFailure failure) throw failure;
                if (cause instanceof java.net.http.HttpTimeoutException) throw new DouyinFailure(HTTP_TIMEOUT);
                throw new DouyinFailure(HTTP_UNAVAILABLE);
            } catch (IllegalArgumentException exception) { throw new DouyinFailure(CREDENTIALS_INVALID); }
            finally {
                if (pending != null) pending.cancel(true);
                client.shutdownNow();
            }
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        LimitedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new DouyinFailure(RESPONSE_TOO_LARGE)); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
