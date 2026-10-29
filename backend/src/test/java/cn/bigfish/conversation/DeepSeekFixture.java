package cn.bigfish.conversation;

import cn.bigfish.contract.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.springframework.ai.model.NoopApiKey;

final class DeepSeekFixture implements AutoCloseable {
    interface Handler { void respond(HttpExchange exchange, int call) throws Exception; }
    final HttpServer server;
    final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger calls = new AtomicInteger(), closed = new AtomicInteger();
    final Queue<String> bodies = new ConcurrentLinkedQueue<>();
    volatile Handler handler;
    DeepSeekFixture(Handler handler) throws Exception {
        this.handler=handler; server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(workers);
        server.createContext("/",exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { this.handler.respond(exchange,calls.incrementAndGet()); }
            catch (Exception ignored) { exchange.close(); }
        }); server.start();
    }
    Supplier<DeepSeekConversation.Transport> transports() {
        return () -> {
            var t=DeepSeekHttp.open(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),new NoopApiKey(),Duration.ofSeconds(1));
            return new DeepSeekConversation.Transport(t.api(),t.web(),() -> { t.close().run(); closed.incrementAndGet(); });
        };
    }
    DeepSeekConversation adapter() { return new DeepSeekConversation(transports()); }
    static void json(HttpExchange e, int status, String body) throws Exception { send(e,status,"application/json",body); }
    static void stream(HttpExchange e, String body) throws Exception { send(e,200,"text/event-stream",body); }
    static void send(HttpExchange e, int status, String type, String body) throws Exception {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type",type);
        e.sendResponseHeaders(status,bytes.length); try(var out=e.getResponseBody()) { out.write(bytes); }
    }
    static String chunk(String text) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\""+text+"\"}}]}\n\n";
    }
    static String end(String reason) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\""+reason+"\"}]}\n\ndata: [DONE]\n\n";
    }
    static String success() { return chunk("hello")+end("stop"); }
    static ConfigSnapshot config(String model, String persona, int tokens, long version) {
        var d=RuntimeSettings.defaults();
        return new ConfigSnapshot(version,Instant.now(),new RuntimeSettings(d.profileName(),persona,d.roomId(),model,tokens,
            d.voice(),d.readComment(),d.welcomeIntervalSeconds(),d.synthesisQueueCapacity(),d.playbackQueueCapacity(),d.priorities()));
    }
    static ConfigSnapshot config() { return config("fixture-model","fixture-persona",128,1); }
    static LiveEvent event() { return new LiveEvent(1,"event","session","room",LiveEvent.Type.COMMENT,"u","",Instant.now(),Map.of("content","你好")); }
    static DeepSeekFailure failure(CompletionStage<?> stage) throws Exception {
        try { stage.toCompletableFuture().get(4,TimeUnit.SECONDS); throw new AssertionError("Expected failure"); }
        catch(ExecutionException e) { return (DeepSeekFailure)e.getCause(); }
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
