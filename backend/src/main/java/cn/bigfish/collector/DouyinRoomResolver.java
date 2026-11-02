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
    private final java.util.concurrent.atomic.AtomicLong requests = new java.util.concurrent.atomic.AtomicLong();

    public DouyinRoomResolver() { this(Clock.systemUTC(), new JdkPageFetcher(Duration.ofSeconds(10), 2 * 1024 * 1024)); }
    DouyinRoomResolver(Clock clock, PageFetcher fetcher) { this.clock = clock; this.fetcher = fetcher; }

    public DouyinRoom resolve(String input, DouyinCredentials credentials) throws DouyinFailure, InterruptedException {
        DouyinRoomInput room = DouyinRoomInput.parse(input);
        if (credentials == null) throw new DouyinFailure(CREDENTIALS_MISSING);
        credentials.validateForResolution(clock.instant());
        Page page = fetch(room.pageUri(), credentials.headers(room));
        credentials.validateForResolution(clock.instant());
        return DouyinRoomPage.parse(page.body, room);
    }

    /** One immutable input snapshot supplies HTTP, visitor discovery, signing and WSS for this attempt. */
    ExternalAccess resolveExternal(String input, DouyinCredentials credentials, String expectedRoom)
            throws DouyinFailure, InterruptedException {
        DouyinRoomInput requested = DouyinRoomInput.parse(input);
        if (credentials == null) throw new DouyinFailure(CREDENTIALS_MISSING);
        credentials.validateForResolution(clock.instant());
        Page page = fetch(requested.pageUri(), credentials.headers(requested));
        credentials.validateForResolution(clock.instant());
        DouyinRoom room = DouyinRoomPage.parse(page.body, requested);
        if (!room.live() || expectedRoom != null && !expectedRoom.equals(room.internalRoomId()))
            return new ExternalAccess(room, null, InboundDiagnostics.VisitorSource.UNRESOLVED);
        String visitor = DouyinRoomPage.visitorId(page.body);
        boolean manual = credentials.hasManualVisitor();
        if (manual && visitor != null && !visitor.equals(credentials.userUniqueId()))
            throw new DouyinFailure(EXTERNAL_ID_CONFLICT);
        if (manual) visitor = credentials.userUniqueId();
        if (visitor == null) throw new DouyinFailure(EXTERNAL_ID_MISSING);
        var resolved = credentials.withVisitor(visitor);
        resolved.validate(clock.instant());
        // Do not replace any caller-provided cookie from Set-Cookie or fall back to guest bootstrap.
        return new ExternalAccess(room, resolved, manual ? InboundDiagnostics.VisitorSource.MANUAL : InboundDiagnostics.VisitorSource.PAGE);
    }
    record ExternalAccess(DouyinRoom room, DouyinCredentials credentials, InboundDiagnostics.VisitorSource visitorSource) {
        @Override public String toString() { return "ExternalAccess[redacted]"; }
    }

    /** Only the homepage and canonical room pages; guest bootstrap cannot fetch arbitrary URLs. */
    Page fetch(URI uri, Map<String, String> headers) throws DouyinFailure, InterruptedException {
        if (!URI.create("https://live.douyin.com/").equals(uri)) DouyinRoomInput.parse(uri.toString());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        inFlight.incrementAndGet();
        try {
            requests.incrementAndGet();
            Page page = fetcher.get(uri, headers);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (page == null || page.contentType == null) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            if ((page.status == 200 || page.status == 401 || page.status == 403)
                    && page.contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) DouyinRoomPage.checkGate(page.body);
            if (page.status == 401 || page.status == 403) throw new DouyinFailure(AUTH_REQUIRED);
            if (page.status == 451) throw new DouyinFailure(REGION_RESTRICTED);
            if (page.status == 429) throw new DouyinFailure(RATE_LIMITED);
            if (page.status >= 300 && page.status < 400) throw new DouyinFailure(REDIRECT_REJECTED);
            if (page.status >= 500) throw new DouyinFailure(HTTP_UNAVAILABLE);
            if (page.status != 200 || !page.contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            }
            if (page.body == null || page.body.length() > 2 * 1024 * 1024) throw new DouyinFailure(RESPONSE_TOO_LARGE);
            return page;
        } finally { inFlight.decrementAndGet(); }
    }
    public int inFlightRequests() { return inFlight.get(); }
    public long requestsStarted() { return requests.get(); }

    @FunctionalInterface interface PageFetcher {
        Page get(URI uri, Map<String, String> headers) throws DouyinFailure, InterruptedException;
    }
    record Page(int status, String contentType, String body, List<String> setCookies) {
        Page(int status, String contentType, String body) { this(status, contentType, body, List.of()); }
        Page { setCookies = List.copyOf(setCookies); }
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
                var cookies = response.headers().allValues("Set-Cookie");
                if (cookies.size() > 32 || cookies.stream().mapToLong(String::length).sum() > 16384) {
                    throw new DouyinFailure(RESPONSE_TOO_LARGE);
                }
                return new Page(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                        new String(response.body(), StandardCharsets.UTF_8), cookies);
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
