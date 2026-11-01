package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.DouyinFailure.Code.*;
import static cn.bigfish.collector.DouyinRoomResolverTest.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static org.junit.jupiter.api.Assertions.*;

class WebDouyinConnectionProviderTest {
    /** Not a real signature. This signer is test-only and is never registered as a ServiceLoader extension. */
    static DouyinSigner signer(Function<DouyinSigner.Request, DouyinSigner.SignedTarget> callback) {
        return new DouyinSigner() {
            public Provenance provenance() { return new Provenance("https://example.invalid/synthetic", "0".repeat(40), "TEST-ONLY"); }
            public SignedTarget sign(Request request) { return callback.apply(request); }
        };
    }
    static DouyinSigner.SignedTarget signed(DouyinSigner.Request request) {
        return new DouyinSigner.SignedTarget(URI.create("wss://webcast100-ws-web-lq.douyin.com/webcast/im/push/v2/?room_id="
                + request.internalRoomId() + "&user_unique_id=" + request.userUniqueId() + "&signature=synthetic_not_valid_" + request.nonce()),
                request.nonce(), request.requestedAt(), request.requestedAt().plusSeconds(30), DouyinConnection.Heartbeat.WEBSOCKET_PING);
    }

    @Test void everyReconnectRefreshesCredentialsPageAndSignedUrl() throws Exception {
        String html = fixture("live");
        var credentialCalls = new AtomicInteger(); var pages = new AtomicInteger();
        var signatures = new CopyOnWriteArrayList<DouyinSigner.Request>();
        var nativeSigner = new NativeDouyinSigner();
        var recordingSigner = new DouyinSigner() {
            public Provenance provenance() { return nativeSigner.provenance(); }
            public SignedTarget sign(Request request) throws Exception { signatures.add(request); return nativeSigner.sign(request); }
        };
        var sockets = new CopyOnWriteArrayList<DouyinCollectorTest.FakeSocket>();
        var opened = new CopyOnWriteArrayList<DouyinConnection>();
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> {
            int sequence = pages.incrementAndGet(); assertEquals("synthetic=" + sequence, headers.get("Cookie"));
            return new DouyinRoomResolver.Page(200, "text/html", html);
        });
        var provider = new WebDouyinConnectionProvider(() -> new DouyinCredentials("synthetic=" + credentialCalls.incrementAndGet(),
                "SyntheticTestAgent/1", "987654", Instant.now().plusSeconds(60)), recordingSigner,
                resolver, Clock.systemUTC());
        try (var collector = new DouyinCollector(provider, DouyinCollectorTest.quick(), Clock.systemUTC(), () -> {
            var socket = new DouyinCollectorTest.FakeSocket() {
                @Override public void open(DouyinConnection connection) throws Exception { opened.add(connection); super.open(connection); }
            }; sockets.add(socket); return socket;
        })) {
            collector.start("https://live.douyin.com/123456", "test", event -> fail());
            await(() -> sockets.size() == 1 && sockets.getFirst().open); sockets.getFirst().open = false;
            await(() -> sockets.size() == 2 && sockets.getLast().open);
            collector.stop(); await(() -> collector.resources().released());
            assertEquals(2, credentialCalls.get()); assertEquals(2, pages.get()); assertEquals(2, signatures.size());
            assertNotEquals(signatures.get(0).nonce(), signatures.get(1).nonce());
            assertNotEquals(opened.get(0).endpoint, opened.get(1).endpoint);
            assertEquals("synthetic=2", opened.get(1).headers.get("Cookie")); assertEquals(0, provider.inFlightRequests());
            assertEquals(2, provider.signingAttempts()); assertEquals(0, provider.signingWorkers());
        }
    }

    @Test void endedRoomAndInternalRoomChangeEndWithoutOpeningAnotherSocket() throws Exception {
        var signs = new AtomicInteger();
        var signer = signer(request -> {
            if (signs.incrementAndGet() > 1) throw new IllegalStateException("Must end before another signing attempt");
            return signed(request);
        });
        var ended = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, signer, resolver(fixture("ended")), Clock.systemUTC());
        assertTrue(ended.resolve("123456").ended); assertEquals(0, signs.get());
        String live = fixture("live"); var pages = new AtomicInteger(); var socket = new DouyinCollectorTest.FakeSocket();
        var changed = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, signer,
                new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> new DouyinRoomResolver.Page(200, "text/html",
                        pages.incrementAndGet() == 1 ? live : live.replace("7000000000000000001", "7000000000000000002"))), Clock.systemUTC());
        var opens = new AtomicInteger();
        try (var collector = new DouyinCollector(changed, DouyinCollectorTest.quick(), Clock.systemUTC(), () -> { opens.incrementAndGet(); return socket; })) {
            collector.start("123456", "s", event -> fail()); await(() -> socket.open); socket.open = false;
            await(() -> collector.state() == CollectorPort.State.ENDED); await(() -> collector.resources().released());
            assertEquals(1, opens.get()); assertEquals(2, pages.get());
            assertEquals(1, signs.get()); assertEquals(1, collector.diagnostics().count(CollectorDiagnostics.Counter.ROOM_CHANGED));
        }
    }

    @Test void cachedExpiredAndLongLivedSignaturesAreRejectedBeforeHandshake() throws Exception {
        List<Function<DouyinSigner.Request, DouyinSigner.SignedTarget>> invalid = List.of(
                request -> new DouyinSigner.SignedTarget(signed(request).endpoint(), request.nonce(), request.requestedAt().minusSeconds(1),
                        request.requestedAt().plusSeconds(20), DouyinConnection.Heartbeat.WEBSOCKET_PING),
                request -> new DouyinSigner.SignedTarget(signed(request).endpoint(), request.nonce(), request.requestedAt(),
                        request.requestedAt().plusSeconds(1), DouyinConnection.Heartbeat.WEBSOCKET_PING),
                request -> new DouyinSigner.SignedTarget(signed(request).endpoint(), request.nonce(), request.requestedAt(),
                        request.requestedAt().plusSeconds(3600), DouyinConnection.Heartbeat.WEBSOCKET_PING));
        for (var callback : invalid) {
            var provider = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, signer(callback), resolver(fixture("live")), Clock.systemUTC());
            assertEquals(SIGNATURE_EXPIRED, assertThrows(DouyinFailure.class, () -> provider.resolve("123456")).code());
        }
        var requests = new CopyOnWriteArrayList<DouyinSigner.Request>();
        var cached = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials,
                signer(request -> { requests.add(request); return signed(requests.getFirst()); }), resolver(fixture("live")), Clock.systemUTC());
        cached.resolve("123456");
        assertEquals(SIGNATURE_INVALID, assertThrows(DouyinFailure.class, () -> cached.resolve("123456")).code());
    }

    @Test void signedTargetCannotChangeRoomVisitorOrDestinationOrDuplicateBindingFields() throws Exception {
        for (String edit : List.of("wrong-room", "wrong-visitor", "wrong-host", "duplicate", "empty-signature")) {
            var signer = signer(request -> {
                var result = signed(request); String uri = result.endpoint().toString();
                uri = switch (edit) {
                    case "wrong-room" -> uri.replace("room_id=" + request.internalRoomId(), "room_id=1");
                    case "wrong-visitor" -> uri.replace("user_unique_id=" + request.userUniqueId(), "user_unique_id=1");
                    case "wrong-host" -> uri.replace("webcast100-ws-web-lq.douyin.com", "evil.example");
                    case "duplicate" -> uri + "&room_id=" + request.internalRoomId();
                    default -> uri.substring(0, uri.indexOf("&signature=")) + "&signature=";
                };
                return new DouyinSigner.SignedTarget(URI.create(uri), result.nonce(), result.issuedAt(), result.expiresAt(), result.heartbeat());
            });
            var provider = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, signer, resolver(fixture("live")), Clock.systemUTC());
            assertEquals(SIGNATURE_INVALID, assertThrows(DouyinFailure.class, () -> provider.resolve("123456")).code());
        }
    }

    @Test void missingSignerMakesNoNetworkRequestAndPermanentFailureNeverRetriesOrMocks() throws Exception {
        var pages = new AtomicInteger();
        var provider = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, null,
                new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> { pages.incrementAndGet(); return null; }), Clock.systemUTC());
        try (var collector = new DouyinCollector(provider)) {
            collector.start("123456", "s", event -> fail()); await(() -> collector.state() == CollectorPort.State.FAILED);
            await(() -> collector.resources().released());
            assertEquals("SIGNER_UNAVAILABLE", collector.diagnostics().lastError()); assertEquals("DOUYIN", collector.mode());
            assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.RECONNECT)); assertEquals(0, pages.get());
        }
    }

    @Test void expiredCredentialsAndHttpTimeoutKeepDistinctDiagnosticCodes() throws Exception {
        var expired = new WebDouyinConnectionProvider(() -> new DouyinCredentials("synthetic=1", "test", "123", Instant.EPOCH),
                signer(WebDouyinConnectionProviderTest::signed), resolver(fixture("live")), Clock.systemUTC());
        try (var collector = new DouyinCollector(expired)) {
            collector.start("123456", "s", event -> fail()); await(() -> collector.state() == CollectorPort.State.FAILED);
            assertEquals("CREDENTIALS_EXPIRED", collector.diagnostics().lastError());
        }
        try (var collector = new DouyinCollector(room -> { throw new DouyinFailure(HTTP_TIMEOUT); })) {
            collector.start("123456", "s", event -> fail()); await(() -> collector.state() == CollectorPort.State.RECONNECTING);
            assertEquals("HTTP_TIMEOUT", collector.diagnostics().lastError()); collector.stop(); await(() -> collector.resources().released());
        }
    }

    @Test void stopInterruptsSignerAndLateProviderResultCannotConnectOrUndoStop() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        DouyinSigner blocking = new DouyinSigner() {
            public Provenance provenance() { return signer(WebDouyinConnectionProviderTest::signed).provenance(); }
            public SignedTarget sign(Request request) throws Exception {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException exception) { interrupted.countDown(); throw exception; }
                return signed(request);
            }
        };
        var provider = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, blocking, resolver(fixture("live")), Clock.systemUTC());
        try (var collector = new DouyinCollector(provider)) {
            collector.start("123456", "s", event -> fail()); assertTrue(entered.await(2, TimeUnit.SECONDS)); collector.stop();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS)); await(() -> collector.resources().released());
            assertEquals(CollectorPort.State.STOPPED, collector.state()); assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.CONNECTED));
        }
        var lateEntered = new CountDownLatch(1); var release = new CountDownLatch(1); var created = new AtomicInteger();
        try (var collector = new DouyinCollector(room -> {
            lateEntered.countDown();
            try { release.await(); } catch (InterruptedException ignored) { /* Deliberately hostile provider result. */ }
            return DouyinConnection.ended("123");
        }, DouyinCollectorTest.quick(), Clock.systemUTC(), () -> { created.incrementAndGet(); return new DouyinCollectorTest.FakeSocket(); })) {
            collector.start("123456", "s", event -> fail()); assertTrue(lateEntered.await(2, TimeUnit.SECONDS)); collector.stop(); release.countDown();
            await(() -> collector.resources().released()); assertEquals(CollectorPort.State.STOPPED, collector.state()); assertEquals(0, created.get());
        } finally { release.countDown(); }
    }

    @Test void signingFailureCannotLeakExceptionMessagesIntoDiagnostics() throws Exception {
        var provider = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials,
                signer(request -> { throw new IllegalStateException("synthetic-private-data"); }), resolver(fixture("live")), Clock.systemUTC());
        var failure = assertThrows(DouyinFailure.class, () -> provider.resolve("123456"));
        assertEquals(SIGNER_FAILED, failure.code()); assertNull(failure.getCause()); assertEquals("SIGNER_FAILED", failure.getMessage());
        assertEquals("DouyinCredentials[redacted]", credentials().toString()); assertEquals("WebDouyinConnectionProvider[redacted]", provider.toString());
    }

    @Test void signerTimeoutIsBoundedAndUncooperativeExtensionsCannotAccumulateWorkers() throws Exception {
        var release = new CountDownLatch(1); var entered = new AtomicInteger();
        DouyinSigner slow = new DouyinSigner() {
            public Provenance provenance() { return signer(WebDouyinConnectionProviderTest::signed).provenance(); }
            public SignedTarget sign(Request request) {
                entered.incrementAndGet();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* Deliberately broken extension. */ }
                }
                return signed(request);
            }
        };
        var provider = new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, slow, resolver(fixture("live")),
                Clock.systemUTC(), java.time.Duration.ofMillis(100));
        try {
            assertEquals(SIGNER_TIMEOUT, assertThrows(DouyinFailure.class, () -> provider.resolve("123456")).code());
            assertEquals(1, provider.signingWorkers());
            assertEquals(SIGNER_FAILED, assertThrows(DouyinFailure.class, () -> provider.resolve("123456")).code());
            assertEquals(1, entered.get());
        } finally { release.countDown(); }
        await(() -> provider.signingWorkers() == 0);
    }
}
