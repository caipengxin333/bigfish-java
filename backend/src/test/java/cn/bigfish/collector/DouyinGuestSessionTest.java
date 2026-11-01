package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.DouyinFailure.Code.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static org.junit.jupiter.api.Assertions.*;

class DouyinGuestSessionTest {
    static String guestPage(String visitor) throws Exception {
        return DouyinRoomResolverTest.fixture("live").replace("\"state\":{", "\"state\":{\"user_unique_id\":\"" + visitor + "\",");
    }
    static DouyinRoomResolver.Page home(String cookie) {
        return new DouyinRoomResolver.Page(200, "text/html", "<title>Synthetic home</title>", List.of(cookie));
    }

    @Test void roomOnlyFetchesOwnGuestCookieAndVisitorWithoutLoginCookieOrFixedIds() throws Exception {
        String html = guestPage("9001"); var calls = new AtomicInteger();
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> {
            int count = calls.incrementAndGet();
            assertEquals(DouyinGuestSession.USER_AGENT, headers.get("User-Agent"));
            if (count == 1) {
                assertEquals(URI.create("https://live.douyin.com/"), uri); assertFalse(headers.containsKey("Cookie"));
                return home("ttwid=synthetic-home; Domain=.douyin.com; Path=/; Secure; HttpOnly; Max-Age=60");
            }
            assertEquals(URI.create("https://live.douyin.com/123456"), uri);
            assertEquals("ttwid=synthetic-home", headers.get("Cookie"));
            return new DouyinRoomResolver.Page(200, "text/html", html,
                    List.of("ttwid=synthetic-room; Path=/; Max-Age=60", "sessionid=synthetic-do-not-forward; Path=/"));
        });
        var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
        var connection = provider.resolve("https://live.douyin.com/123456/");
        assertEquals("ttwid=synthetic-room", connection.headers.get("Cookie"));
        assertTrue(connection.endpoint.getRawQuery().contains("user_unique_id=9001&"));
        assertEquals("7000000000000000001", connection.internalRoomId);
        assertEquals(2, provider.requestsStarted()); assertEquals(1, provider.signingAttempts());
        assertEquals(0, provider.inFlightRequests()); assertEquals(0, provider.signingWorkers());
        assertEquals("Page[redacted]", home("ttwid=synthetic-private").toString());
    }

    @Test void actualReconnectRefreshesGuestCookieVisitorPageAndNativeSignature() throws Exception {
        String template = guestPage("9001"); var homes = new AtomicInteger(); var pages = new AtomicInteger();
        var opened = new CopyOnWriteArrayList<DouyinConnection>(); var sockets = new CopyOnWriteArrayList<DouyinCollectorTest.FakeSocket>();
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> {
            if (uri.getPath().equals("/")) {
                assertFalse(headers.containsKey("Cookie")); return home("ttwid=synthetic-" + homes.incrementAndGet());
            }
            int count = pages.incrementAndGet(); assertEquals("ttwid=synthetic-" + count, headers.get("Cookie"));
            return new DouyinRoomResolver.Page(200, "text/html", template.replace("9001", "900" + count));
        });
        var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
        try (var collector = new DouyinCollector(provider, DouyinCollectorTest.quick(), Clock.systemUTC(), () -> {
            var socket = new DouyinCollectorTest.FakeSocket() {
                @Override public void open(DouyinConnection connection) throws Exception { opened.add(connection); super.open(connection); }
            }; sockets.add(socket); return socket;
        })) {
            collector.start("123456", "synthetic-reconnect", event -> fail());
            await(() -> sockets.size() == 1 && sockets.getFirst().open); sockets.getFirst().open = false;
            await(() -> sockets.size() == 2 && sockets.getLast().open); collector.stop();
            await(() -> collector.resources().released());
            assertEquals(2, homes.get()); assertEquals(2, pages.get()); assertEquals(4, provider.requestsStarted());
            assertEquals("ttwid=synthetic-2", opened.getLast().headers.get("Cookie"));
            assertTrue(opened.getLast().endpoint.getRawQuery().contains("user_unique_id=9002&"));
            assertNotEquals(opened.getFirst().endpoint, opened.getLast().endpoint);
            assertEquals(2, provider.signingAttempts()); assertEquals(0, provider.signingWorkers());
        }
    }

    @Test void missingCookieGateAndBadInputNeverProceedToRoomOrSigner() throws Exception {
        var cases = List.of(new DouyinRoomResolver.Page(200, "text/html", "<title>Synthetic home</title>"),
                new DouyinRoomResolver.Page(403, "text/html", "<title>安全验证 - 抖音</title>"),
                new DouyinRoomResolver.Page(200, "text/html", "<title>登录抖音</title>"),
                new DouyinRoomResolver.Page(429, "text/html", "synthetic"));
        var codes = List.of(GUEST_COOKIE_MISSING, CHALLENGE_REQUIRED, AUTH_REQUIRED, RATE_LIMITED);
        for (int i = 0; i < cases.size(); i++) {
            var page = cases.get(i); var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> page);
            var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
            var failure = assertThrows(DouyinFailure.class, () -> provider.resolve("123456"));
            assertEquals(codes.get(i), failure.code()); assertNull(failure.getCause());
            assertEquals(1, provider.requestsStarted()); assertEquals(0, provider.signingAttempts());
        }
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> { fail("No HTTP expected"); return null; });
        var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
        assertEquals(UNSUPPORTED_ROOM_URL, assertThrows(DouyinFailure.class, () -> provider.resolve("https://example.invalid/123")).code());
        assertEquals(SIGNER_UNAVAILABLE, assertThrows(DouyinFailure.class,
                () -> WebDouyinConnectionProvider.guest(null, resolver, Clock.systemUTC()).resolve("123456")).code());
        assertEquals(0, resolver.requestsStarted());
    }

    @Test void cookieScopeExpiryConflictsAndHeaderLimitsAreEnforcedWithoutSensitiveErrors() throws Exception {
        Instant now = Instant.now();
        for (String invalid : List.of("ttwid=synthetic; Domain=example.invalid", "ttwid=synthetic; Path=/elsewhere",
                "ttwid=bad value", "ttwid=synthetic\r\nInjected: data", "ttwid=")) {
            var error = assertThrows(DouyinFailure.class, () -> DouyinGuestSession.updateCookie(null, List.of(invalid), now));
            assertEquals(GUEST_COOKIE_INVALID, error.code()); assertEquals(error.code().name(), error.getMessage()); assertNull(error.getCause());
        }
        assertEquals(GUEST_COOKIE_INVALID, assertThrows(DouyinFailure.class, () -> DouyinGuestSession.updateCookie(null,
                List.of("ttwid=synthetic-a", "ttwid=synthetic-b"), now)).code());
        for (String expired : List.of("ttwid=synthetic; Max-Age=0", "ttwid=synthetic; Max-Age=-1", "ttwid=synthetic; Max-Age = -1", "ttwid=synthetic; Max-Age=3",
                "ttwid=synthetic; Expires=Thu, 01 Jan 1970 00:00:00 GMT")) {
            assertEquals(CREDENTIALS_EXPIRED, assertThrows(DouyinFailure.class,
                    () -> DouyinGuestSession.updateCookie(null, List.of(expired), now)).code());
        }
        var cookie = DouyinGuestSession.updateCookie(null, List.of("ttwid=synthetic; Max-Age=60"), now);
        assertEquals(now.plusSeconds(60), cookie.expiresAt()); assertEquals("GuestCookie[redacted]", cookie.toString());
        assertEquals(CREDENTIALS_EXPIRED, assertThrows(DouyinFailure.class,
                () -> DouyinGuestSession.updateCookie(cookie, List.of(), now.plusSeconds(60))).code());
        var sessionCookie = DouyinGuestSession.updateCookie(null, List.of("ttwid=synthetic"), now);
        assertEquals(now.plusSeconds(300), sessionCookie.expiresAt());
        assertEquals(RESPONSE_TOO_LARGE, assertThrows(DouyinFailure.class,
                () -> DouyinGuestSession.updateCookie(null, java.util.Collections.nCopies(33, "ignored=synthetic"), now)).code());
    }

    @Test void guestIdSupportsHydrationFormatsAndRejectsAnchorFallbackConflictsAndMalformedValues() throws Exception {
        String html = guestPage("9001");
        String json = html.substring(html.indexOf("{\"state\""), html.indexOf("</script>"));
        String encoded = "<script id=\"RENDER_DATA\">" + URLEncoder.encode(json, StandardCharsets.UTF_8) + "</script>";
        String streamed = "<script>self.__pace_f.push(" + new ObjectMapper().writeValueAsString(List.of(1, "0:" + json)) + ")</script>";
        for (String source : List.of(html, encoded, streamed)) assertEquals("9001", DouyinRoomPage.guestId(source));
        assertEquals(GUEST_ID_MISSING, assertThrows(DouyinFailure.class,
                () -> DouyinRoomPage.guestId(DouyinRoomResolverTest.fixture("live"))).code());
        for (String invalid : List.of(html + html.replace("9001", "9002"), html.replace("9001", "invalid"),
                html.replace("\"9001\"", "null"), html.replace("\"9001\"", "{}"),
                html.replace("\"user_unique_id\":\"9001\"", "\"user_unique_id\":\"9001\",\"user_unique_id\":\"9002\""))) {
            assertEquals(PAGE_STRUCTURE_CHANGED, assertThrows(DouyinFailure.class, () -> DouyinRoomPage.guestId(invalid)).code());
        }
    }

    @Test void endedOrChangedRoomNeedsNoVisitorAndNeverSigns() throws Exception {
        for (boolean changed : List.of(false, true)) {
            String html = DouyinRoomResolverTest.fixture(changed ? "live" : "ended");
            var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> uri.getPath().equals("/")
                    ? home("ttwid=synthetic") : new DouyinRoomResolver.Page(200, "text/html", html));
            var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
            var result = provider.resolve("123456", changed ? "7000000000000000002" : null);
            assertTrue(result.ended); assertEquals(0, provider.signingAttempts()); assertEquals(2, provider.requestsStarted());
        }
    }

    @Test void missingVisitorFailsBeforeHandshakeAndDoesNotRetry() throws Exception {
        String html = DouyinRoomResolverTest.fixture("live");
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> uri.getPath().equals("/")
                ? home("ttwid=synthetic") : new DouyinRoomResolver.Page(200, "text/html", html));
        var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
        try (var collector = new DouyinCollector(provider)) {
            collector.start("123456", "synthetic-missing-visitor", event -> fail());
            await(() -> collector.state() == CollectorPort.State.FAILED); await(() -> collector.resources().released());
            assertEquals("GUEST_ID_MISSING", collector.diagnostics().lastError());
            assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.HANDSHAKE_ATTEMPT));
            assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.RECONNECT));
            assertEquals(0, provider.signingAttempts()); assertEquals(2, provider.requestsStarted());
        }
    }

    @Test void stopCancelsActualHttpDuringEitherBootstrapStage() throws Exception {
        for (boolean blockHome : List.of(true, false)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            try (var server = new DouyinRoomResolverTest.LocalHttp(exchange -> {
                if (!blockHome && exchange.getRequestURI().getPath().equals("/")) {
                    byte[] html = "<title>Synthetic home</title>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.getResponseHeaders().add("Set-Cookie", "ttwid=synthetic; Path=/");
                    exchange.sendResponseHeaders(200, html.length); exchange.getResponseBody().write(html); exchange.close(); return;
                }
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                exchange.close();
            })) {
                var fetcher = new DouyinRoomResolver.JdkPageFetcher(Duration.ofSeconds(10), 4096);
                var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> fetcher.get(server.uri().resolve(uri.getPath()), headers));
                var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
                try (var collector = new DouyinCollector(provider)) {
                    collector.start("123456", "synthetic-cancel", event -> fail()); assertTrue(entered.await(2, TimeUnit.SECONDS));
                    collector.stop(); collector.stop(); await(() -> collector.resources().released() && provider.inFlightRequests() == 0);
                    assertEquals(CollectorPort.State.STOPPED, collector.state());
                    assertEquals(blockHome ? 1 : 2, provider.requestsStarted()); assertEquals(0, provider.signingAttempts());
                }
            } finally { release.countDown(); }
        }
    }

    @Test void actualHttpRejectsOversizedCookieHeadersAndGuestRedirects() throws Exception {
        var targetCalls = new AtomicInteger();
        try (var target = new DouyinRoomResolverTest.LocalHttp(exchange -> { targetCalls.incrementAndGet(); exchange.close(); });
             var redirect = new DouyinRoomResolverTest.LocalHttp(exchange -> {
                 exchange.getResponseHeaders().set("Location", target.uri().toString());
                 exchange.getResponseHeaders().set("Set-Cookie", "ttwid=synthetic");
                 exchange.sendResponseHeaders(302, -1); exchange.close();
             }); var oversized = new DouyinRoomResolverTest.LocalHttp(exchange -> {
                 exchange.getResponseHeaders().set("Content-Type", "text/html");
                 exchange.getResponseHeaders().set("Set-Cookie", "ttwid=" + "x".repeat(16384));
                 exchange.sendResponseHeaders(200, -1); exchange.close();
             })) {
            var fetcher = new DouyinRoomResolver.JdkPageFetcher(Duration.ofSeconds(2), 4096);
            var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> fetcher.get(redirect.uri(), headers));
            var provider = WebDouyinConnectionProvider.guest(new NativeDouyinSigner(), resolver, Clock.systemUTC());
            assertEquals(REDIRECT_REJECTED, assertThrows(DouyinFailure.class, () -> provider.resolve("123456")).code());
            assertEquals(0, targetCalls.get()); assertEquals(1, provider.requestsStarted());
            assertEquals(RESPONSE_TOO_LARGE, assertThrows(DouyinFailure.class, () -> fetcher.get(oversized.uri(), Map.of())).code());
        }
    }
}
