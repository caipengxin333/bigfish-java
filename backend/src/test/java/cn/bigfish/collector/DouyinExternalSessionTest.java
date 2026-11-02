package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.DouyinFailure.Code.*;
import static cn.bigfish.collector.DouyinRoomResolverTest.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static org.junit.jupiter.api.Assertions.*;

/** All credentials, IDs and peers are invented; no browser or platform is contacted. */
class DouyinExternalSessionTest {
    static final String COOKIE = "ttwid=synthetic-device; sessionid=synthetic-login; sessionid_ss=synthetic-secure; extra=synthetic=value";
    static final String UA = "SyntheticSourceSafari/1";
    static DouyinCredentials automatic() { return new DouyinCredentials(COOKIE, UA, Instant.now().plusSeconds(300)); }
    static WebDouyinConnectionProvider provider(String html, DouyinCredentials input) {
        return new WebDouyinConnectionProvider(() -> input, new NativeDouyinSigner(), resolver(html), Clock.systemUTC());
    }

    @Test void autoVisitorUsesSameCookieAndUaAcrossSupportedEscapedHydrationShapes() throws Exception {
        String html = DouyinGuestSessionTest.guestPage("9001");
        String json = html.substring(html.indexOf("{\"state\""), html.indexOf("</script>"));
        var shapes = List.of(html, fixture("pace-bootstrap"),
                "<script id=\"RENDER_DATA\">" + URLEncoder.encode(json, StandardCharsets.UTF_8) + "</script>",
                "<script>self.__pace_f.push(" + new ObjectMapper().writeValueAsString(List.of(1, "0:" + json)) + ")</script>",
                "<script type=\"application/json\">" + json.replace("&", "&amp;").replace("\"", "&quot;") + "</script>",
                html.replace("\"9001\"", "\"\\u0039\\u0030\\u0030\\u0031\""));
        for (String body : shapes) {
            var requests = new AtomicInteger(); var signs = new CopyOnWriteArrayList<DouyinSigner.Request>();
            var nativeSigner = new NativeDouyinSigner();
            var signer = new DouyinSigner() {
                public Provenance provenance() { return nativeSigner.provenance(); }
                public SignedTarget sign(Request request) throws Exception { signs.add(request); return nativeSigner.sign(request); }
            };
            var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> {
                requests.incrementAndGet(); assertEquals("/123456", uri.getPath()); // Never guest homepage.
                assertEquals(COOKIE, headers.get("Cookie")); assertEquals(UA, headers.get("User-Agent"));
                return new DouyinRoomResolver.Page(200, "text/html", body, List.of("ttwid=other-synthetic; Path=/"));
            });
            var provider = new WebDouyinConnectionProvider(DouyinExternalSessionTest::automatic, signer, resolver, Clock.systemUTC());
            var connection = provider.resolve("123456");
            assertEquals(COOKIE, connection.headers.get("Cookie")); assertEquals(UA, connection.headers.get("User-Agent"));
            assertEquals("9001", signs.getFirst().userUniqueId()); assertEquals(UA, signs.getFirst().userAgent());
            assertTrue(connection.endpoint.getRawQuery().contains("user_unique_id=9001&"));
            assertEquals(1, requests.get()); assertEquals(1, provider.signingAttempts());
            assertEquals(new InboundDiagnostics.CredentialEvidence(true, true, InboundDiagnostics.VisitorSource.PAGE), provider.credentialEvidence());
        }
    }

    @Test void missingInvalidAndConflictingVisitorsHaveDistinctSafeFailuresWithoutFallback() throws Exception {
        String html = DouyinGuestSessionTest.guestPage("9001");
        var cases = Map.of(fixture("live"), EXTERNAL_ID_MISSING,
                html + html.replace("9001", "9002"), EXTERNAL_ID_CONFLICT,
                html.replace("\"9001\"", "null"), EXTERNAL_ID_INVALID,
                html.replace("9001", "synthetic-private-id"), EXTERNAL_ID_INVALID,
                html.replace("\"9001\"", "9001.5"), EXTERNAL_ID_INVALID,
                html.replace("9001", "0"), EXTERNAL_ID_INVALID,
                html.replace("\"user_unique_id\":\"9001\"", "\"user_unique_id\":\"9001\",\"user_unique_id\":\"9002\""), PAGE_STRUCTURE_CHANGED);
        for (var entry : cases.entrySet()) {
            var provider = provider(entry.getKey(), automatic());
            var error = assertThrows(DouyinFailure.class, () -> provider.resolve("123456"));
            assertEquals(entry.getValue(), error.code()); assertEquals(error.code().name(), error.getMessage());
            assertNull(error.getCause()); assertEquals(1, provider.requestsStarted()); assertEquals(0, provider.signingAttempts());
            assertEquals(0, provider.inFlightRequests()); assertEquals(0, provider.signingWorkers());
        }
    }

    @Test void manualVisitorWorksWhenAbsentOrEqualButCannotOverrideContradictoryPageIdentity() throws Exception {
        var manual = new DouyinCredentials(COOKIE, UA, "9001", Instant.now().plusSeconds(300));
        for (String html : List.of(fixture("live"), DouyinGuestSessionTest.guestPage("9001"))) {
            var p = provider(html, manual);
            assertTrue(p.resolve("123456").endpoint.getRawQuery().contains("user_unique_id=9001&"));
            assertEquals(InboundDiagnostics.VisitorSource.MANUAL, p.credentialEvidence().visitorSource());
        }
        assertEquals(EXTERNAL_ID_CONFLICT, assertThrows(DouyinFailure.class,
                () -> provider(DouyinGuestSessionTest.guestPage("9002"), manual).resolve("123456")).code());
        assertEquals(EXTERNAL_ID_INVALID, assertThrows(DouyinFailure.class,
                () -> provider(DouyinGuestSessionTest.guestPage("bad"), manual).resolve("123456")).code());
        var empty = new DouyinCredentials(COOKIE, UA, "", Instant.now().plusSeconds(300));
        assertTrue(provider(DouyinGuestSessionTest.guestPage("9001"), empty).resolve("123456").endpoint.getRawQuery().contains("user_unique_id=9001&"));
    }

    @Test void badInputsFailLocallyBeforeHttpAndWhitespaceIsNotAnAutomaticIdentity() throws Exception {
        Instant future = Instant.now().plusSeconds(300);
        var invalid = List.of(new DouyinCredentials(COOKIE + "\nInjected", UA, future),
                new DouyinCredentials(COOKIE, UA + "\rInjected", future), new DouyinCredentials(COOKIE + "\u0000", UA, future),
                new DouyinCredentials("x".repeat(16385), UA, future), new DouyinCredentials(COOKIE, "x".repeat(2049), future),
                new DouyinCredentials(COOKIE, UA, " ", future), new DouyinCredentials(COOKIE, UA, "0", future),
                new DouyinCredentials(COOKIE, UA, "9".repeat(31), future), new DouyinCredentials(COOKIE, UA, (Instant) null));
        for (var input : invalid) {
            var p = provider(fixture("live"), input);
            assertEquals(CREDENTIALS_INVALID, assertThrows(DouyinFailure.class, () -> p.resolve("123456")).code());
            assertEquals(0, p.requestsStarted()); assertEquals(0, p.signingAttempts());
        }
        for (var input : List.of(new DouyinCredentials(null, UA, future), new DouyinCredentials(COOKIE, "", future))) {
            var p = provider(fixture("live"), input);
            assertEquals(CREDENTIALS_MISSING, assertThrows(DouyinFailure.class, () -> p.resolve("123456")).code());
            assertEquals(0, p.requestsStarted());
        }
        var expired = provider(fixture("live"), new DouyinCredentials(COOKIE, UA, Instant.EPOCH));
        assertEquals(CREDENTIALS_EXPIRED, assertThrows(DouyinFailure.class, () -> expired.resolve("123456")).code());
        assertEquals(0, expired.requestsStarted());
    }

    @Test void expiryDuringHttpAndEndedOrChangedRoomNeverReachSigning() throws Exception {
        var clock = new GiftDiagnosticsTest.MutableClock();
        String html = DouyinGuestSessionTest.guestPage("9001");
        var input = new DouyinCredentials(COOKIE, UA, clock.instant().plusSeconds(1));
        var resolver = new DouyinRoomResolver(clock, (uri, headers) -> {
            clock.advance(2); return new DouyinRoomResolver.Page(200, "text/html", html);
        });
        var p = new WebDouyinConnectionProvider(() -> input, new NativeDouyinSigner(), resolver, clock);
        assertEquals(CREDENTIALS_EXPIRED, assertThrows(DouyinFailure.class, () -> p.resolve("123456")).code());
        assertEquals(0, p.signingAttempts()); assertEquals(0, p.inFlightRequests());
        // No visitor is needed when ending an old broadcast, and no replacement visitor is invented.
        var ended = provider(fixture("ended"), automatic());
        assertTrue(ended.resolve("123456").ended); assertEquals(0, ended.signingAttempts());
        var changed = provider(fixture("live"), automatic());
        assertTrue(changed.resolve("123456", "123").ended); assertEquals(0, changed.signingAttempts());
    }

    @Test void dynamicUpdatesAreAtomicPerAttemptAndReconnectGetsFreshIdentity() throws Exception {
        String first = DouyinGuestSessionTest.guestPage("9001"), second = DouyinGuestSessionTest.guestPage("9002");
        var source = new AtomicReference<>(automatic()); var reads = new AtomicInteger();
        var replacement = new DouyinCredentials("sessionid=synthetic-second; ttwid=second", "SyntheticOtherBrowser/2", Instant.now().plusSeconds(300));
        var opened = new CopyOnWriteArrayList<DouyinConnection>(); var sockets = new CopyOnWriteArrayList<DouyinCollectorTest.FakeSocket>();
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> {
            boolean initial = headers.get("Cookie").equals(COOKIE);
            assertEquals(initial ? UA : "SyntheticOtherBrowser/2", headers.get("User-Agent"));
            source.set(replacement); // Simulate settings saved while the first page request is in flight.
            return new DouyinRoomResolver.Page(200, "text/html", initial ? first : second);
        });
        var p = new WebDouyinConnectionProvider(() -> { reads.incrementAndGet(); return source.get(); }, new NativeDouyinSigner(), resolver, Clock.systemUTC());
        try (var collector = new DouyinCollector(p, DouyinCollectorTest.quick(), Clock.systemUTC(), () -> {
            var socket = new DouyinCollectorTest.FakeSocket() {
                public void open(DouyinConnection connection) throws Exception { opened.add(connection); super.open(connection); }
            }; sockets.add(socket); return socket;
        })) {
            collector.start("123456", "00000000-0000-4000-8000-000000000021", event -> fail());
            await(() -> sockets.size() == 1 && sockets.getFirst().open);
            assertEquals(COOKIE, opened.getFirst().headers.get("Cookie")); assertEquals(UA, opened.getFirst().headers.get("User-Agent"));
            assertTrue(opened.getFirst().endpoint.getRawQuery().contains("user_unique_id=9001&"));
            sockets.getFirst().open = false;
            await(() -> sockets.size() == 2 && sockets.getLast().open);
            assertEquals("sessionid=synthetic-second; ttwid=second", opened.getLast().headers.get("Cookie"));
            assertEquals("SyntheticOtherBrowser/2", opened.getLast().headers.get("User-Agent"));
            assertTrue(opened.getLast().endpoint.getRawQuery().contains("user_unique_id=9002&"));
            collector.stop(); await(() -> collector.resources().released());
            assertEquals(2, reads.get()); assertEquals(2, p.requestsStarted()); assertEquals(2, p.signingAttempts());
            assertEquals(0, p.inFlightRequests()); assertEquals(0, p.signingWorkers());
        }
    }

    @Test void stopCancelsExternalHttpAndMissingIdentityDoesNotReconnect() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var http = new LocalHttp(exchange -> {
            assertEquals(COOKIE, exchange.getRequestHeaders().getFirst("Cookie")); entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            var fetcher = new DouyinRoomResolver.JdkPageFetcher(Duration.ofSeconds(10), 4096);
            var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> fetcher.get(http.uri(), headers));
            var p = new WebDouyinConnectionProvider(DouyinExternalSessionTest::automatic, new NativeDouyinSigner(), resolver, Clock.systemUTC());
            try (var collector = new DouyinCollector(p)) {
                collector.start("123456", "synthetic-external-stop", event -> fail());
                assertTrue(entered.await(2, TimeUnit.SECONDS)); collector.stop(); collector.stop();
                await(() -> collector.resources().released() && p.inFlightRequests() == 0);
                assertEquals(CollectorPort.State.STOPPED, collector.state()); assertEquals(0, p.signingAttempts());
                assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.HANDSHAKE_ATTEMPT));
            }
        } finally { release.countDown(); }
        try (var collector = new DouyinCollector(provider(fixture("live"), automatic()))) {
            collector.start("123456", "synthetic-external-missing", event -> fail());
            await(() -> collector.state() == CollectorPort.State.FAILED); await(() -> collector.resources().released());
            assertEquals("EXTERNAL_ID_MISSING", collector.diagnostics().lastError());
            assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.RECONNECT));
        }
    }

    @Test void credentialEvidenceIsSafeAndNeverClaimsLoginOrGiftValidation() throws Exception {
        var p = provider(DouyinGuestSessionTest.guestPage("9001"), automatic()); p.resolve("123456");
        var logs = new ArrayList<String>(); var d = new InboundDiagnostics((level, line) -> logs.add(line));
        d.begin("00000000-0000-4000-8000-000000000022", Clock.systemUTC(), true);
        d.credentials(InboundDiagnostics.Credentials.EXTERNAL); d.credentialEvidence(p.credentialEvidence()); d.flushLogs(true);
        var json = new ObjectMapper().readTree(logs.getFirst());
        assertEquals("LOCAL_VALID", json.path("credentialValidation").asText());
        assertEquals("PRESENT", json.path("loginFields").asText()); assertEquals("PAGE", json.path("visitorSource").asText());
        assertEquals("UNVERIFIED", json.path("platformLogin").asText()); assertEquals(0, json.path("giftMethod").asInt());
        for (String secret : List.of(COOKIE, UA, "9001", "signature=", "合成主播")) assertFalse(logs.toString().contains(secret));
        assertEquals("DouyinCredentials[redacted]", automatic().toString());
        for (String cookie : List.of("ttwid=synthetic", "not_sessionid=synthetic", "sessionid=", "other=sessionid=synthetic"))
            assertFalse(new DouyinCredentials(cookie, UA, Instant.now()).hasLoginFields());
        assertTrue(automatic().hasLoginFields());
    }

    @Test void oldGenerationLogsRetainItsOwnCredentialEvidenceAfterRotation() throws Exception {
        var logs = new ArrayList<String>(); var d = new InboundDiagnostics((level, line) -> logs.add(line));
        d.begin("00000000-0000-4000-8000-000000000023", Clock.systemUTC(), true); d.nextConnection();
        d.credentialEvidence(new InboundDiagnostics.CredentialEvidence(true, true, InboundDiagnostics.VisitorSource.PAGE));
        var old = d.nextFrame().message(1).method("WebcastGiftMessage");
        d.nextConnection();
        d.credentialEvidence(new InboundDiagnostics.CredentialEvidence(true, false, InboundDiagnostics.VisitorSource.MANUAL));
        try (var scope = d.use(old)) {
            d.record(InboundDiagnostics.Stage.DISPATCH, InboundDiagnostics.Reason.SINK_RETURNED, InboundDiagnostics.Kind.GIFT, null);
        }
        d.flushLogs(true);
        var first = new ObjectMapper().readTree(logs.getFirst());
        assertEquals(1, first.path("generation").asInt()); assertEquals("PRESENT", first.path("loginFields").asText());
        assertEquals("PAGE", first.path("visitorSource").asText());
        var summary = new ObjectMapper().readTree(logs.getLast());
        assertEquals(2, summary.path("generation").asInt()); assertEquals("ABSENT", summary.path("loginFields").asText());
        assertEquals("MANUAL", summary.path("visitorSource").asText());
    }
}
