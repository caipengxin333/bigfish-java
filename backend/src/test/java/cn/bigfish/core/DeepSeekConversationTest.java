package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.DeepSeekConversation;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

class DeepSeekConversationTest {
    private ConfigSnapshot config() { return new ConfigSnapshot(1,Instant.now(),RuntimeSettings.defaults()); }
    private LiveEvent event() { return new LiveEvent(1,"e","s","r",LiveEvent.Type.COMMENT,"u","",Instant.now(),Map.of("content","你好")); }
    private DeepSeekConversation adapter(int port) {
        return new DeepSeekConversation(() -> {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            var api = OpenAiApi.builder().baseUrl("http://127.0.0.1:"+port).completionsPath("/chat/completions")
                .apiKey(new NoopApiKey()).webClientBuilder(WebClient.builder().clientConnector(new JdkClientHttpConnector(http))).build();
            return new DeepSeekConversation.Transport(api,http::shutdownNow);
        });
    }
    @Test void missingKeyOrPaidFlagCannotEnableProvider() {
        assertThatThrownBy(() -> DeepSeekConversation.create(false,null)).hasMessage("PROVIDER_DISABLED");
        assertThatThrownBy(() -> DeepSeekConversation.create(true,null)).hasMessage("PROVIDER_DISABLED");
    }
    @Test void compatibleStreamUsesSnapshotAndEmitsOnlyCompletedText() throws Exception {
        var body = new AtomicReference<String>(); var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet(); body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type","text/event-stream"); exchange.sendResponseHeaders(200,0);
            try (var out = exchange.getResponseBody()) {
                for (String word : List.of("你好","，朋友！")) {
                    out.write(("data: {\"id\":\"local\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""+word+"\"}}]}\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                out.write("data: [DONE]\n\n".getBytes());
            }
        }); server.start();
        try {
            var output = new ArrayList<String>();
            adapter(server.getAddress().getPort()).reply("success",event(),config(),Duration.ofSeconds(3),output::add)
                .toCompletableFuture().get(4,TimeUnit.SECONDS);
            assertThat(output).containsExactly("你好，朋友！");
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.get());
            assertThat(json.path("model").asText()).isEqualTo(config().settings().model());
            assertThat(json.path("max_tokens").asInt()).isEqualTo(256);
            assertThat(json.path("messages").get(0).path("content").asText()).isEqualTo(config().settings().persona());
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
    @Test void keepAliveAndContinuousContentCannotExtendOverallDeadline() throws Exception {
        for (boolean tokens : List.of(false,true)) {
            var disconnected = new CountDownLatch(1); var started = new CountDownLatch(1);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
            server.createContext("/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,0); started.countDown();
                try (var out = exchange.getResponseBody()) {
                    while (true) {
                        String frame = tokens ? "data: {\"id\":\"local\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"a\"}}]}\n\n" : ": keep-alive\n\n";
                        out.write(frame.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.flush(); Thread.sleep(20);
                    }
                } catch (Exception ignored) { disconnected.countDown(); }
            });
            server.start();
            try {
                var adapter = adapter(server.getAddress().getPort()); var emitted = new AtomicBoolean();
                long start = System.nanoTime();
                var result = adapter.reply("request",event(),config(),Duration.ofMillis(700),text -> emitted.set(true)).toCompletableFuture();
                assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> result.get(3,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
                assertThat(emitted.get()).isFalse();
                assertThat(disconnected.await(3,TimeUnit.SECONDS)).isTrue();
            } finally { server.stop(0); executor.shutdownNow(); }
        }
    }
    @Test void cancellationDisposesHttpStreamWithoutEmittingReply() throws Exception {
        var entered = new CountDownLatch(1); var disconnected = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,0); entered.countDown();
            try (var out = exchange.getResponseBody()) {
                while (true) { out.write(": keep-alive\n\n".getBytes()); out.flush(); Thread.sleep(20); }
            } catch (Exception ignored) { disconnected.countDown(); }
        }); server.start();
        try {
            var adapter = adapter(server.getAddress().getPort()); var emitted = new AtomicBoolean();
            var future = adapter.reply("cancel",event(),config(),Duration.ofSeconds(10),text -> emitted.set(true)).toCompletableFuture();
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue(); adapter.cancel("cancel");
            assertThat(future).isCancelled(); assertThat(emitted.get()).isFalse();
            assertThat(disconnected.await(3,TimeUnit.SECONDS)).isTrue();
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
