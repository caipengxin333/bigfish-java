package cn.bigfish.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.buffer.*;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import static cn.bigfish.conversation.DeepSeekFailure.Code.*;

/** Request-owned clients; redirects disabled; bounded bodies and status-only diagnostics. */
final class DeepSeekHttp {
    static final URI OFFICIAL = URI.create("https://api.deepseek.com");
    private DeepSeekHttp() {}
    static Context redactedLateErrors(Context context) {
        // Sequence-local hook verified against pinned Reactor 3.7.16. Never install a global hook.
        // JDK HTTP cancellation can signal after disposal; do not log its raw exception/URI/body.
        return context.put("reactor.onErrorDropped.local",(Consumer<Throwable>)error -> {
            var code=DeepSeekFailure.sanitize(error).diagnostic().code();
            if(code!=CANCELLED) org.slf4j.LoggerFactory.getLogger(DeepSeekHttp.class).debug("deepseek_late_signal code={}",code);
        });
    }
    static DeepSeekConversation.Transport open(URI base, ApiKey key, Duration connectTimeout) {
        var http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
        var builder = WebClient.builder().baseUrl(base.toString()).clientConnector(new JdkClientHttpConnector(http))
            .codecs(c -> c.defaultCodecs().maxInMemorySize(65_536))
            .filter((request,next) -> next.exchange(request).flatMap(original -> {
                var size = new AtomicInteger();
                var response = original.mutate().body(body -> body.<DataBuffer>handle((buffer,sink) -> {
                    if (size.addAndGet(buffer.readableByteCount()) > 262_144) {
                        DataBufferUtils.release(buffer); sink.error(new DeepSeekFailure(INVALID_RESPONSE));
                    } else sink.next(buffer);
                }).doOnDiscard(DataBuffer.class,DataBufferUtils::release)).build();
                int status = response.statusCode().value();
                if (status >= 200 && status < 300) {
                    MediaType expected = request.url().getPath().endsWith("/models") ? MediaType.APPLICATION_JSON : MediaType.TEXT_EVENT_STREAM;
                    if (!response.headers().contentType().map(expected::isCompatibleWith).orElse(false))
                        return Mono.error(new DeepSeekFailure(INVALID_RESPONSE,status));
                    return Mono.just(response);
                }
                // Only known machine error codes are examined; raw provider text is never propagated.
                if (status != 400 && status != 422) return Mono.error(statusFailure(status,""));
                return response.bodyToMono(JsonNode.class).onErrorComplete().defaultIfEmpty(com.fasterxml.jackson.databind.node.NullNode.instance)
                    .flatMap(body -> Mono.error(statusFailure(status,body.path("error").path("code").asText())));
            }));
        var api = OpenAiApi.builder().baseUrl(base.toString()).completionsPath("/chat/completions")
            .apiKey(key).webClientBuilder(builder).build();
        var web = builder.clone().defaultHeaders(headers -> {
            if (!(key instanceof org.springframework.ai.model.NoopApiKey)) headers.setBearerAuth(key.getValue());
        }).build();
        return new DeepSeekConversation.Transport(api,web,http::shutdownNow);
    }
    private static DeepSeekFailure statusFailure(int status, String code) {
        var result = switch (status) {
            case 401 -> AUTHENTICATION_FAILED;
            case 402 -> INSUFFICIENT_BALANCE;
            case 403 -> PERMISSION_DENIED;
            case 404 -> MODEL_NOT_FOUND;
            case 429 -> RATE_LIMITED;
            case 400,422 -> switch (code) {
                case "model_not_found", "invalid_model", "model_not_exist" -> MODEL_NOT_FOUND;
                default -> INVALID_REQUEST;
            };
            default -> status >= 500 && status <= 599 ? SERVER_ERROR : INVALID_RESPONSE;
        };
        return new DeepSeekFailure(result,status);
    }
}
