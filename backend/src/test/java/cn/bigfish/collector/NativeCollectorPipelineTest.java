package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import cn.bigfish.contract.LiveEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;
import static cn.bigfish.collector.MockCollectorTest.await;
import static org.junit.jupiter.api.Assertions.*;

/** Actual JDK HTTP/WebSocket, native signing and event delivery; all peer data is synthetic and loopback-only. */
class NativeCollectorPipelineTest {
    @Test void nativeSigningThroughLocalTransportDeliversNormalizedEventsAckHeartbeatAndEnded() throws Exception {
        verifyPipeline(false);
    }

    @Test void roomOnlyGuestBootstrapDeliversEventsThroughActualLocalJdkTransport() throws Exception {
        verifyPipeline(true);
    }

    private void verifyPipeline(boolean guest) throws Exception {
        byte[] html = DouyinGuestSessionTest.guestPage("9001").replace("7000000000000000001", "123").getBytes(StandardCharsets.UTF_8);
        var httpCalls = new AtomicInteger();
        var events = new CopyOnWriteArrayList<LiveEvent>();
        var defaults = CollectorOptions.defaults();
        var options = new CollectorOptions(32, defaults.queueTtl(), defaults.cacheCapacity(), defaults.cacheTtl(),
                defaults.reconnectBase(), defaults.reconnectMax(), Duration.ofMillis(100), Duration.ofSeconds(5), defaults.maxFrameBytes());
        try (var http = new DouyinRoomResolverTest.LocalHttp(exchange -> {
            httpCalls.incrementAndGet(); exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            if (guest && exchange.getRequestURI().getPath().equals("/")) {
                assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
                exchange.getResponseHeaders().add("Set-Cookie", "ttwid=synthetic-guest; Domain=.douyin.com; Path=/; HttpOnly; Secure");
                byte[] home = "<title>Synthetic home</title>".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, home.length); exchange.getResponseBody().write(home); exchange.close(); return;
            }
            if (guest) assertEquals("ttwid=synthetic-guest", exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, html.length); exchange.getResponseBody().write(html); exchange.close();
        }); var peer = new JdkCollectorSocketTest.LoopbackPeer()) {
            var fetcher = new DouyinRoomResolver.JdkPageFetcher(Duration.ofSeconds(2), 4096);
            var resolver = new DouyinRoomResolver(Clock.systemUTC(), (uri, headers) -> fetcher.get(http.uri().resolve(uri.getPath()), headers));
            var provider = guest ? WebDouyinConnectionProvider.guest(DouyinSigners.load(), resolver, Clock.systemUTC())
                    : new WebDouyinConnectionProvider(DouyinRoomResolverTest::credentials, DouyinSigners.load(), resolver, Clock.systemUTC());
            var transport = new JdkCollectorSocket(options, new CollectorDiagnostics(), Clock.systemUTC());
            try (var collector = new DouyinCollector(provider, options, Clock.systemUTC(), () -> new CollectorSocket() {
                public void open(DouyinConnection connection) throws Exception {
                    assertEquals("123", connection.internalRoomId);
                    assertTrue(connection.endpoint.getRawQuery().contains("signature="));
                    if (guest) {
                        assertTrue(connection.endpoint.getRawQuery().contains("user_unique_id=9001&"));
                        assertEquals("ttwid=synthetic-guest", connection.headers.get("Cookie"));
                    }
                    // Test-only target substitution; production destination validation remains unchanged.
                    transport.openEndpoint(peer.uri(), connection.headers);
                }
                public Frame poll(Duration wait) throws InterruptedException { return transport.poll(wait); }
                public boolean isOpen() { return transport.isOpen(); }
                public long lastActivityNanos() { return transport.lastActivityNanos(); }
                public void acknowledge(byte[] bytes) throws Exception { transport.acknowledge(bytes); }
                public void heartbeat(byte[] bytes, DouyinConnection.Heartbeat kind) throws Exception { transport.heartbeat(bytes, kind); }
                public void close() { transport.close(); }
            })) {
                collector.start("123456", "synthetic-native-pipeline", events::add);
                await(() -> collector.state() == CollectorPort.State.CONNECTED);
                peer.send(2, true, new byte[]{(byte) 0x80}); // Invalid push must not close the connection.
                byte[] terminalGift = WireFixtures.gift(13, 456, 90, 3, false, true);
                peer.send(2, true, WireFixtures.frame(true, true, WireFixtures.chat(1),
                        WireFixtures.gift(11, 456, 90, 0, false), WireFixtures.gift(12, 456, 90, 3, true),
                        terminalGift, terminalGift, WireFixtures.gift(14, 456, 90, 3, false, true),
                        WireFixtures.gift(15, 789, 90, 1, false),
                        WireFixtures.envelope("WebcastMemberMessage", new WireFixtures().bytes(1, WireFixtures.common(20, 123))
                                .bytes(2, WireFixtures.user(456)).build()),
                        WireFixtures.envelope("WebcastSocialMessage", new WireFixtures().bytes(1, WireFixtures.common(21, 123))
                                .bytes(2, WireFixtures.user(456)).number(4, 1).build())));
                await(() -> events.size() == 6);
                assertEquals(List.of(LiveEvent.Type.COMMENT, LiveEvent.Type.GIFT, LiveEvent.Type.GIFT, LiveEvent.Type.GIFT,
                        LiveEvent.Type.ENTRANCE, LiveEvent.Type.FOLLOW), events.stream().map(LiveEvent::type).toList());
                var gifts = events.stream().filter(event -> event.type() == LiveEvent.Type.GIFT).toList();
                assertEquals(List.of(1, 2, 1), gifts.stream().map(event -> event.payload().get("quantity")).toList());
                assertEquals(List.of("456", "456", "789"), gifts.stream().map(LiveEvent::userId).toList());
                assertTrue(events.stream().allMatch(event -> event.sessionId().equals("synthetic-native-pipeline") && event.schemaVersion() == 1));
                boolean ping = false, ack = false;
                for (int index = 0; index < 8 && !(ping && ack); index++) {
                    var frame = peer.received.poll(2, TimeUnit.SECONDS); assertNotNull(frame);
                    ping |= frame.opcode() == 9;
                    ack |= frame.opcode() == 2 && ProtoWire.parse(frame.bytes()).text(7).equals("ack");
                }
                assertTrue(ping && ack); peer.assertHealthy();
                assertEquals(1, collector.diagnostics().count(MALFORMED));
                assertEquals(3, collector.diagnostics().count(DUPLICATE));
                peer.send(2, true, WireFixtures.frame(false, false, WireFixtures.end(123)));
                await(() -> collector.state() == CollectorPort.State.ENDED);
                await(() -> collector.resources().released());
                assertEquals(guest ? 2 : 1, httpCalls.get()); assertEquals(guest ? 2 : 1, resolver.requestsStarted());
                assertEquals(1, provider.signingAttempts()); assertEquals(1, collector.diagnostics().count(HANDSHAKE_ATTEMPT));
                assertEquals(0, collector.diagnostics().count(RECONNECT));
                collector.stop(); collector.stop();
                assertEquals(CollectorPort.State.STOPPED, collector.state()); assertFalse(transport.isOpen());
                assertEquals(0, resolver.inFlightRequests()); assertEquals(0, provider.signingWorkers());
                assertEquals(6, events.size());
            }
        }
    }
}
