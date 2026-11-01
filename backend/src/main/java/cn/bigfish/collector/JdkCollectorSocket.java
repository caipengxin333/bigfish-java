package cn.bigfish.collector;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;

/** JDK listener only copies bounded frames; it never executes parsing or business callbacks. */
final class JdkCollectorSocket implements CollectorSocket, WebSocket.Listener {
    private final CollectorOptions options;
    private final CollectorDiagnostics diagnostics;
    private final Clock clock;
    private final ArrayBlockingQueue<Frame> inbound;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ByteArrayOutputStream fragments = new ByteArrayOutputStream();
    private final HttpClient client;
    private volatile WebSocket socket;
    private volatile CompletableFuture<WebSocket> opening;
    private volatile long lastActivity = System.nanoTime();
    private long fragmentStarted;
    private int fragmentCount;
    private boolean dropping;

    JdkCollectorSocket(CollectorOptions options, CollectorDiagnostics diagnostics, Clock clock) {
        this.options = options; this.diagnostics = diagnostics; this.clock = clock;
        inbound = new ArrayBlockingQueue<>(options.queueCapacity());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public void open(DouyinConnection connection) throws Exception {
        openEndpoint(connection.endpoint, connection.headers);
    }

    /** Package-private seam for an isolated loopback WebSocket test, not a public URL-taking API. */
    void openEndpoint(URI endpoint, Map<String, String> headers) throws Exception {
        if (closed.get()) throw new InterruptedException();
        var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        opening = builder.buildAsync(endpoint, this);
        try {
            socket = opening.get(12, TimeUnit.SECONDS);
            if (closed.get()) socket.abort();
        } catch (java.util.concurrent.ExecutionException exception) {
            close();
            if (exception.getCause() instanceof WebSocketHandshakeException) {
                throw new DouyinFailure(DouyinFailure.Code.HANDSHAKE_REJECTED);
            }
            throw exception;
        } catch (Exception exception) { close(); throw exception; }
    }

    @Override public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        if (closed.get()) webSocket.abort(); else { lastActivity = System.nanoTime(); webSocket.request(1); }
    }

    @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        if (closed.get()) return CompletableFuture.completedFuture(null);
        lastActivity = System.nanoTime();
        fragmentCount = Math.min(Integer.MAX_VALUE - 1, fragmentCount) + 1;
        if (fragmentStarted == 0) fragmentStarted = lastActivity;
        boolean oversized = data.remaining() > options.maxFrameBytes() - fragments.size();
        if (!dropping && (oversized || lastActivity - fragmentStarted >= options.queueTtl().toNanos())) {
            diagnostics.inbound().record(InboundDiagnostics.Stage.ASSEMBLY,
                    oversized ? InboundDiagnostics.Reason.SIZE_LIMIT : InboundDiagnostics.Reason.TTL,
                    new InboundDiagnostics.Shape(fragments.size(), data.remaining(), fragmentCount, 0,
                            InboundDiagnostics.Encoding.NONE, false, java.util.List.of()));
            dropping = true; fragments.reset(); diagnostics.error(MALFORMED, 0);
        }
        if (!dropping) { byte[] bytes = new byte[data.remaining()]; data.get(bytes); fragments.writeBytes(bytes); }
        if (last) {
            if (!closed.get() && !dropping) {
                diagnostics.inbound().record(InboundDiagnostics.Stage.ASSEMBLY, InboundDiagnostics.Reason.OK,
                        new InboundDiagnostics.Shape(fragments.size(), 0, fragmentCount, 0,
                                InboundDiagnostics.Encoding.NONE, false, java.util.List.of()));
                if (!inbound.offer(new Frame(fragments.toByteArray(), clock.instant()))) {
                    diagnostics.increment(QUEUE_FULL);
                    diagnostics.inbound().record(InboundDiagnostics.Stage.QUEUE, InboundDiagnostics.Reason.QUEUE_FULL, null);
                }
            }
            fragments.reset(); dropping = false; fragmentStarted = 0; fragmentCount = 0;
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (last) diagnostics.increment(UNKNOWN); webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        lastActivity = System.nanoTime(); webSocket.request(1); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        lastActivity = System.nanoTime(); webSocket.request(1); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.set(true); return CompletableFuture.completedFuture(null);
    }
    @Override public void onError(WebSocket webSocket, Throwable error) { closed.set(true); }
    @Override public Frame poll(Duration wait) throws InterruptedException {
        return inbound.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public boolean isOpen() { return !closed.get() && socket != null && !socket.isInputClosed(); }
    @Override public long lastActivityNanos() { return lastActivity; }
    @Override public void acknowledge(byte[] frame) throws Exception {
        if (!isOpen()) throw new IllegalStateException("Transport closed");
        socket.sendBinary(ByteBuffer.wrap(frame), true).get(5, TimeUnit.SECONDS);
    }
    @Override public void heartbeat(byte[] frame, DouyinConnection.Heartbeat kind) throws Exception {
        if (!isOpen()) throw new IllegalStateException("Transport closed");
        var sent = kind == DouyinConnection.Heartbeat.WEBSOCKET_PING
                ? socket.sendPing(ByteBuffer.wrap(frame)) : socket.sendBinary(ByteBuffer.wrap(frame), true);
        sent.get(5, TimeUnit.SECONDS);
    }
    @Override public void close() {
        closed.set(true);
        var pending = opening; if (pending != null) pending.cancel(true);
        var current = socket; if (current != null) current.abort();
        inbound.clear(); client.shutdownNow();
    }
}
