package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.management.RuntimeEngine;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageWebSocketIntegrationTest {
    static final Path DATA;
    static { try { DATA = Files.createTempDirectory("bigfish-stage-test-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATA.resolve("test.db"));
        registry.add("bigfish.data-dir",DATA::toString);
    }
    @LocalServerPort int port;
    @Autowired RuntimeEngine engine;
    @Autowired ObjectMapper mapper;
    final HttpClient http = HttpClient.newHttpClient();
    final List<Client> clients = new ArrayList<>();
    String session;
    @BeforeEach void start() throws Exception {
        session = mapper.readTree(post("/api/v1/sessions",Map.of("mode","MOCK","conversationMode","MOCK"),201)).path("sessionId").asText();
    }
    @AfterEach void stop() throws Exception {
        try { post("/api/v1/sessions/"+session+"/stop",Map.of(),200); }
        finally { clients.forEach(Client::close); http.shutdownNow(); }
    }

    @Test void realSocketsVerifyMockCommentGiftAudioReadyAckAndExclusivePlayer() throws Exception {
        Client first = connect(); Client second = connect();
        first.hello(); var lease = first.next("PLAYER_LEASE"); long generation = lease.path("payload").path("leaseGeneration").asLong();
        second.hello(); assertThat(second.next("PLAYER_REJECTED").path("payload").path("reason").asText()).isEqualTo("PLAYER_ALREADY_ACTIVE");
        post("/api/v1/events/simulate",event("c",LiveEvent.Type.COMMENT),202);
        assertThat(first.frames.poll(250,TimeUnit.MILLISECONDS)).isNull(); // no PLAYER_READY, no audio work
        first.send("PLAYER_READY",Map.of("playerId",first.id,"leaseGeneration",generation));
        var comment = first.next("PLAY_TASK").path("payload");
        assertThat(comment.path("type").asText()).isEqualTo("COMMENT");
        assertThat(comment.path("text").asText()).contains("[模拟回复]");
        String url = comment.path("audioUrl").asText();
        var audio = get(url);
        assertThat(audio.statusCode()).isEqualTo(200);
        assertThat(audio.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(new String(audio.body(),0,4,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(get("/api/v1/audio/unknown").statusCode()).isEqualTo(404);
        post("/api/v1/events/simulate",event("g",LiveEvent.Type.GIFT),202);
        assertThat(first.frames.poll(300,TimeUnit.MILLISECONDS)).isNull(); // server keeps a single in-flight task
        first.ack(comment,generation,"STARTED"); first.ack(comment,generation,"COMPLETED");
        var gift = first.next("PLAY_TASK").path("payload");
        assertThat(gift.path("type").asText()).isEqualTo("GIFT");
        assertThat(gift.path("text").asText()).contains("谢谢测试观众送来的1个小心心").doesNotContain("模拟回复");
        assertThat(second.frames.poll(100,TimeUnit.MILLISECONDS)).isNull();
        first.ack(gift,generation,"STARTED");
        post("/api/v1/sessions/"+session+"/stop",Map.of(),200);
        assertThat(first.next("SESSION_STOPPED").path("sessionId").asText()).isEqualTo(session);
        assertThat(get(gift.path("audioUrl").asText()).statusCode()).isEqualTo(404);
        assertThat(engine.status().playback().playing()).isFalse();
    }
    @Test void disconnectRevokesAudioAndDoesNotReplayToReplacement() throws Exception {
        Client first = connect(); first.hello();
        long generation = first.next("PLAYER_LEASE").path("payload").path("leaseGeneration").asLong();
        first.send("PLAYER_READY",Map.of("playerId",first.id,"leaseGeneration",generation));
        post("/api/v1/events/simulate",event("g",LiveEvent.Type.GIFT),202);
        var gift = first.next("PLAY_TASK").path("payload");
        first.ack(gift,generation,"STARTED"); first.close();
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (engine.status().playback().connected() && System.nanoTime()<until) Thread.sleep(10);
        assertThat(engine.status().playback().connected()).isFalse();
        assertThat(get(gift.path("audioUrl").asText()).statusCode()).isEqualTo(404);
        Client second = connect(); second.hello();
        long nextGeneration = second.next("PLAYER_LEASE").path("payload").path("leaseGeneration").asLong();
        assertThat(nextGeneration).isGreaterThan(generation);
        second.send("PLAYER_READY",Map.of("playerId",second.id,"leaseGeneration",nextGeneration));
        assertThat(second.frames.poll(300,TimeUnit.MILLISECONDS)).isNull();
        post("/api/v1/events/simulate",event("fresh",LiveEvent.Type.COMMENT),202);
        assertThat(second.next("PLAY_TASK").path("payload").path("taskId").asText()).isNotEqualTo(gift.path("taskId").asText());
    }
    @Test void collectorReplayCrossesNormalizationIngressAndAudioWithoutDoubleCountingCombo() throws Exception {
        Client player = connect(); player.hello();
        long generation = player.next("PLAYER_LEASE").path("payload").path("leaseGeneration").asLong();
        player.send("PLAYER_READY",Map.of("playerId",player.id,"leaseGeneration",generation));
        long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while (!engine.status().playback().ready() && System.nanoTime()<until) Thread.sleep(10);
        post("/api/v1/sessions/"+session+"/replay",Map.of(),202);
        int gifts = 0, comments = 0;
        for (int i=0;i<2;i++) {
            var task = player.next("PLAY_TASK").path("payload");
            if (task.path("type").asText().equals("GIFT")) {
                gifts++; assertThat(task.path("text").asText()).contains("3个小心心");
            } else { comments++; assertThat(task.path("text").asText()).contains("[模拟回复]"); }
            assertThat(get(task.path("audioUrl").asText()).statusCode()).isEqualTo(200);
            player.ack(task,generation,"STARTED"); player.ack(task,generation,"COMPLETED");
        }
        assertThat(gifts).isEqualTo(1); assertThat(comments).isEqualTo(1);
        assertThat(player.frames.poll(300,TimeUnit.MILLISECONDS)).isNull();
        var status = mapper.readTree(get("/api/v1/runtime").body());
        assertThat(status.path("collector").path("received").asLong()).isEqualTo(4);
        assertThat(status.path("collector").path("emitted").asLong()).isEqualTo(3);
        assertThat(status.path("collector").path("duplicates").asLong()).isEqualTo(1);
        assertThat(status.path("collector").path("rejected").asLong()).isZero();
    }
    private byte[] post(String path, Object body, int expected) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(expected);
        return response.body();
    }
    private HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private LiveEvent event(String id, LiveEvent.Type type) {
        return new LiveEvent(1,id,session,"mock-room",type,"u","测试观众",Instant.now(),type == LiveEvent.Type.COMMENT
            ? Map.of("content","你好") : Map.of("giftId","1","giftName","小心心","quantity",1,"comboId","c","unitValue",1,"valueUnit","DOUYIN_DIAMOND"));
    }
    private Client connect() throws Exception {
        var client = new Client(); clients.add(client);
        client.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2))
            .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/stage"),client).get(3,TimeUnit.SECONDS);
        return client;
    }
    private final class Client implements WebSocket.Listener {
        final String id = UUID.randomUUID().toString();
        final BlockingQueue<JsonNode> frames = new ArrayBlockingQueue<>(32);
        final StringBuilder text = new StringBuilder();
        WebSocket socket;
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try { frames.add(mapper.readTree(text.toString())); } catch (Exception e) { throw new AssertionError(e); }
                text.setLength(0);
            }
            socket.request(1); return CompletableFuture.completedFuture(null);
        }
        void hello() throws Exception { send("PLAYER_HELLO",Map.of("playerId",id)); }
        void send(String type, Object payload) throws Exception {
            socket.sendText(mapper.writeValueAsString(Map.of("schemaVersion",1,"messageId",UUID.randomUUID().toString(),
                "sessionId",session,"type",type,"payload",payload)),true).get(2,TimeUnit.SECONDS);
        }
        JsonNode next(String type) throws Exception {
            long until = System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime()<until) {
                JsonNode frame = frames.poll(100,TimeUnit.MILLISECONDS);
                if (frame != null && type.equals(frame.path("type").asText())) return frame;
            }
            throw new AssertionError("Missing frame " + type);
        }
        void ack(JsonNode task, long generation, String state) throws Exception {
            var payload = mapper.createObjectNode().put("taskId",task.path("taskId").asText()).put("sessionId",session)
                .put("playerId",id).put("leaseGeneration",generation).put("state",state).put("occurredAt",Instant.now().toString()).putNull("errorCode");
            send("PLAYBACK_ACK",payload);
        }
        void close() { if (socket != null) socket.abort(); }
    }
}
