package cn.bigfish.conversation;

import cn.bigfish.contract.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Existing Spring AI OpenAI API adapter, with request-owned HTTP cancellation. */
public final class DeepSeekConversation implements ConversationPort {
    public record Transport(OpenAiApi api, Runnable close) {}
    private final Supplier<Transport> transports;
    private final Map<String, CompletableFuture<Void>> active = new HashMap<>();
    private long cooldownUntil;
    /** Injection seam for local HTTP fault tests; no endpoint is accepted from REST/config. */
    public DeepSeekConversation(Supplier<Transport> transports) { this.transports = transports; }
    public static DeepSeekConversation create(boolean allowPaid, String key) {
        if (!allowPaid || key == null || key.isBlank()) throw new IllegalStateException("PROVIDER_DISABLED");
        return new DeepSeekConversation(() -> {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
            var web = WebClient.builder().clientConnector(new JdkClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024));
            var api = OpenAiApi.builder().baseUrl("https://api.deepseek.com").completionsPath("/chat/completions")
                .apiKey(new SimpleApiKey(key)).webClientBuilder(web).build();
            return new Transport(api,http::shutdownNow);
        });
    }
    @Override public synchronized CompletionStage<Void> reply(String id, LiveEvent event, ConfigSnapshot config,
                                                              Duration deadline, Consumer<String> sink) {
        if (deadline.isZero() || deadline.isNegative()) return CompletableFuture.failedFuture(new TimeoutException());
        if (active.size() >= 4 || active.containsKey(id) || System.nanoTime() < cooldownUntil)
            return CompletableFuture.failedFuture(new IllegalStateException("PROVIDER_BUSY"));
        var requestBody = ModelOptionsUtils.jsonToObject(ModelOptionsUtils.toJsonString(Map.of(
            "model",config.settings().model(),"max_tokens",config.settings().maxOutputTokens(),"stream",true,
            "messages",List.of(Map.of("role","system","content",config.settings().persona()),
                Map.of("role","user","content",event.payload().get("content"))))),OpenAiApi.ChatCompletionRequest.class);
        Transport transport = transports.get();
        CompletableFuture<Void> result = new CompletableFuture<>();
        active.put(id,result);
        // Timeout wraps the terminal Mono: neither tokens nor keep-alives can extend it.
        var request = Mono.defer(() -> transport.api().chatCompletionStream(requestBody)
            .reduce(new StringBuilder(), (text,chunk) -> {
                if (chunk.choices() != null) {
                    for (var choice : chunk.choices()) {
                        if (choice.delta() == null || choice.delta().content() == null) continue;
                        String delta = choice.delta().content();
                        if (text.length() + delta.length() > 2000) throw new IllegalStateException("MODEL_OUTPUT_LIMIT");
                        text.append(delta);
                    }
                }
                return text;
            }))
            .onErrorMap(error -> new IllegalStateException("MODEL_TRANSPORT_FAILED"))
            .timeout(deadline).toFuture();
        result.whenComplete((unused,error) -> {
            request.cancel(true);
            // JDK WebClient may drain a cancelled SSE body; close its request-owned client too.
            transport.close().run();
            synchronized (this) { active.remove(id,result); }
        });
        request.whenComplete((text,error) -> {
            synchronized (this) {
                if (result.isDone()) return;
                if (error != null) {
                    cooldownUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    result.completeExceptionally(new IllegalStateException("MODEL_REQUEST_FAILED"));
                } else {
                    try { sink.accept(text.toString()); result.complete(null); }
                    catch (RuntimeException ignored) { result.completeExceptionally(new IllegalStateException("MODEL_OUTPUT_REJECTED")); }
                }
            }
        });
        return result;
    }
    @Override public synchronized void cancel(String id) {
        var future = active.remove(id);
        if (future != null) future.cancel(true);
    }
}
