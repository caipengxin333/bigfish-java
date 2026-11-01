package cn.bigfish.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.DouyinFailure.Code.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static org.junit.jupiter.api.Assertions.*;

class DouyinRoomResolverTest {
    static String fixture(String name) throws Exception {
        try (var stream = DouyinRoomResolverTest.class.getResourceAsStream("/collector/room-" + name + ".html")) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static DouyinCredentials credentials() {
        return new DouyinCredentials("synthetic_cookie=fixture_only", "SyntheticTestAgent/1", "9000000000000000001", Instant.now().plusSeconds(300));
    }
    static DouyinRoomResolver resolver(String html) {
        return new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> new DouyinRoomResolver.Page(200, "text/html; charset=utf-8", html));
    }

    @Test void onlyCanonicalRoomInputsAreAccepted() throws Exception {
        for (String input : List.of("123456", " https://live.douyin.com/123456 ", "https://live.douyin.com:443/123456/")) {
            assertEquals("123456", DouyinRoomInput.parse(input).webRoomId());
        }
        for (String input : List.of("http://live.douyin.com/123", "https://127.0.0.1/123", "https://v.douyin.com/abc/",
                "https://live.douyin.com.evil.example/123", "https://user@live.douyin.com/123", "https://live.douyin.com:8080/123",
                "https://live.douyin.com/%31%32%33", "https://live.douyin.com/123?signature=synthetic", "https://live.douyin.com/123#x",
                "https://live.douyin.com/../123", "file:///123", "https://www.douyin.com/user/123", "123/456", "")) {
            assertEquals(UNSUPPORTED_ROOM_URL, assertThrows(DouyinFailure.class, () -> DouyinRoomInput.parse(input)).code());
        }
    }

    @Test void parsesScopedRoomRatherThanRecommendedRoomAndPreservesStringIds() throws Exception {
        var room = resolver(fixture("live")).resolve("https://live.douyin.com/123456", credentials());
        assertEquals("7000000000000000001", room.internalRoomId());
        assertEquals("8000000000000000001", room.anchorId()); assertEquals("合成主播+示例", room.anchorName()); assertTrue(room.live());
        assertEquals("DouyinRoom[redacted]", room.toString());
        assertFalse(resolver(fixture("ended")).resolve("123456", credentials()).live());
    }

    @Test void supportsPercentEncodedAndStreamedEscapedHydrationWithoutExecutingScripts() throws Exception {
        String html = fixture("live");
        String json = html.substring(html.indexOf("{\"state\""), html.indexOf("</script>"));
        String encoded = URLEncoder.encode(json, StandardCharsets.UTF_8).replace("+", "%20");
        String render = "<script id=\"RENDER_DATA\" type=\"application/json\">" + encoded + "</script>";
        String streamed = "<script>self.__pace_f.push(" + new ObjectMapper().writeValueAsString(List.of(1, "0:" + json)) + ")</script>";
        for (String body : List.of(render, streamed)) {
            assertEquals("合成主播+示例", resolver(body).resolve("123456", credentials()).anchorName());
        }
    }

    @Test void unknownConflictingOrDifferentRoomMetadataFailsClosed() throws Exception {
        String html = fixture("live");
        for (String body : List.of("<script>{\"roomId\":\"123\"}</script>", html.replace("\"status\":2", "\"status\":0"),
                html.replace("\"web_rid\":\"123456\"", "\"web_rid\":\"654321\""),
                html + html.replace("7000000000000000001", "7000000000000000002"),
                html.replace("\"id_str\":\"7000000000000000001\"", "\"id_str\":\"7000000000000000002\""))) {
            assertEquals(PAGE_STRUCTURE_CHANGED, assertThrows(DouyinFailure.class,
                    () -> resolver(body).resolve("123456", credentials())).code());
        }
    }

    @Test void gatePagesAndHttpFailuresHaveFixedCodes() throws Exception {
        for (var entry : Map.of("验证码", CHALLENGE_REQUIRED, "地区限制", REGION_RESTRICTED, "登录抖音", AUTH_REQUIRED).entrySet()) {
            assertEquals(entry.getValue(), assertThrows(DouyinFailure.class, () -> resolver("<title>" + entry.getKey() + "</title>")
                    .resolve("123456", credentials())).code());
        }
        for (var entry : Map.of(401, AUTH_REQUIRED, 403, AUTH_REQUIRED, 429, RATE_LIMITED, 451, REGION_RESTRICTED,
                302, REDIRECT_REJECTED, 503, HTTP_UNAVAILABLE, 404, PAGE_STRUCTURE_CHANGED).entrySet()) {
            var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) ->
                    new DouyinRoomResolver.Page(entry.getKey(), "text/html", "synthetic sensitive response must never escape"));
            var failure = assertThrows(DouyinFailure.class, () -> resolver.resolve("123456", credentials()));
            assertEquals(entry.getValue(), failure.code()); assertEquals(entry.getValue().name(), failure.getMessage()); assertNull(failure.getCause());
        }
    }

    @Test void missingExpiredOrInvalidCredentialsNeverMakeARequest() {
        var calls = new AtomicInteger();
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> { calls.incrementAndGet(); return null; });
        assertEquals(CREDENTIALS_MISSING, assertThrows(DouyinFailure.class, () -> resolver.resolve("123", null)).code());
        var expired = new DouyinCredentials("synthetic=1", "test", "123", Instant.EPOCH);
        assertEquals(CREDENTIALS_EXPIRED, assertThrows(DouyinFailure.class, () -> resolver.resolve("123", expired)).code());
        var injection = new DouyinCredentials("synthetic=1\r\nInvalid: header", "test", "123", Instant.now().plusSeconds(60));
        assertEquals(CREDENTIALS_INVALID, assertThrows(DouyinFailure.class, () -> resolver.resolve("123", injection)).code());
        assertEquals(0, calls.get()); assertEquals(0, resolver.inFlightRequests());
        assertEquals(0, resolver.requestsStarted());
    }

    @Test void explicitChallengeOn403IsNotMisdiagnosedAsCookieFailure() {
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) ->
                new DouyinRoomResolver.Page(403, "text/html", "<TITLE>安全验证 - 抖音</TITLE>"));
        assertEquals(CHALLENGE_REQUIRED, assertThrows(DouyinFailure.class, () -> resolver.resolve("123456", credentials())).code());
        assertEquals(1, resolver.requestsStarted()); assertEquals(0, resolver.inFlightRequests());
    }

    @Test void canonicalFetchReceivesOnlyCurrentCredentialsAndCannotFollowRedirect() throws Exception {
        String body = fixture("live");
        var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> {
            assertEquals("https://live.douyin.com/123456", uri.toString());
            assertEquals("synthetic_cookie=fixture_only", headers.get("Cookie"));
            return new DouyinRoomResolver.Page(200, "text/html", body);
        });
        assertTrue(resolver.resolve("https://live.douyin.com:443/123456/", credentials()).live());
        var destinationCalls = new AtomicInteger();
        try (var destination = new LocalHttp(exchange -> { destinationCalls.incrementAndGet(); exchange.close(); });
             var redirect = new LocalHttp(exchange -> {
                 exchange.getResponseHeaders().set("Location", destination.uri().toString()); exchange.sendResponseHeaders(302, -1); exchange.close();
             })) {
            var response = new DouyinRoomResolver.JdkPageFetcher(Duration.ofSeconds(2), 1024).get(redirect.uri(), Map.of());
            assertEquals(302, response.status()); assertEquals(0, destinationCalls.get());
        }
    }

    @Test void bodyIsBoundedAndDeadlineIncludesBodyAfterHeaders() throws Exception {
        var release = new CountDownLatch(1);
        try (var tooLarge = new LocalHttp(exchange -> {
            exchange.sendResponseHeaders(200, 2048); exchange.getResponseBody().write(new byte[2048]); exchange.close();
        }); var slow = new LocalHttp(exchange -> {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            assertEquals(RESPONSE_TOO_LARGE, assertThrows(DouyinFailure.class, () ->
                    new DouyinRoomResolver.JdkPageFetcher(Duration.ofSeconds(2), 128).get(tooLarge.uri(), Map.of())).code());
            long start = System.nanoTime();
            assertEquals(HTTP_TIMEOUT, assertThrows(DouyinFailure.class, () ->
                    new DouyinRoomResolver.JdkPageFetcher(Duration.ofMillis(300), 128).get(slow.uri(), Map.of())).code());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        } finally { release.countDown(); }
    }

    @Test void stopCancelsAnActualPendingHttpRequestAndReleasesCollectorResources() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var server = new LocalHttp(exchange -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            exchange.close();
        })) {
            var fetcher = new DouyinRoomResolver.JdkPageFetcher(Duration.ofSeconds(10), 1024);
            var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> fetcher.get(server.uri(), headers));
            try (var collector = new DouyinCollector(room -> {
                resolver.resolve(room, credentials()); return DouyinCollectorTest.connection("123");
            })) {
                collector.start("123456", "test", event -> fail()); assertTrue(entered.await(2, TimeUnit.SECONDS));
                collector.stop(); collector.stop();
                await(() -> collector.resources().released() && resolver.inFlightRequests() == 0);
                assertEquals(cn.bigfish.contract.CollectorPort.State.STOPPED, collector.state());
                assertEquals(0, collector.diagnostics().count(CollectorDiagnostics.Counter.RECONNECT));
            }
        } finally { release.countDown(); }
    }

    static final class LocalHttp implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        LocalHttp(HttpHandler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler); server.setExecutor(executor); server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
