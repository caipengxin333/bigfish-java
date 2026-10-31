package cn.bigfish.conversation;

import cn.bigfish.contract.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import static cn.bigfish.conversation.DeepSeekFailure.Code.*;

/** Bounded Spring AI adapter. No network or credential check is performed at application startup. */
public final class DeepSeekConversation implements ConversationPort, AutoCloseable {
    public record Transport(OpenAiApi api, WebClient web, Runnable close) {
        public Transport(OpenAiApi api, Runnable close) { this(api,null,close); }
    }
    public record Policy(Duration readTimeout, Duration retryDelay, int maxAttempts) {
        public Policy {
            if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero() || readTimeout.compareTo(Duration.ofSeconds(30))>0
                || retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(Duration.ofSeconds(1))>0
                || maxAttempts < 1 || maxAttempts > 2) throw new IllegalArgumentException("INVALID_POLICY");
        }
        public static Policy defaults() { return new Policy(Duration.ofSeconds(5),Duration.ofMillis(250),2); }
    }
    private record Recent(DeepSeekFailure.Diagnostic value, long at) {}
    private final Supplier<Transport> transports;
    private final Policy policy;
    private final ConcurrentHashMap<String, Request> active = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(4);
    private final Map<String,Recent> recent = new LinkedHashMap<>();
    private volatile boolean closed;
    public DeepSeekConversation(Supplier<Transport> transports) { this(transports,Policy.defaults()); }
    public DeepSeekConversation(Supplier<Transport> transports, Policy policy) {
        this.transports = Objects.requireNonNull(transports); this.policy = Objects.requireNonNull(policy);
    }
    public static DeepSeekConversation create(boolean allowPaid, String key) { return create(allowPaid,key,Policy.defaults()); }
    public static DeepSeekConversation create(boolean allowPaid, String key, Policy policy) {
        requireEnabled(allowPaid,key);
        return new DeepSeekConversation(() -> DeepSeekHttp.open(DeepSeekHttp.OFFICIAL,new SimpleApiKey(key),Duration.ofSeconds(3)),policy);
    }
    static void requireEnabled(boolean allowPaid, String key) {
        if (!allowPaid || key == null || key.isBlank()) throw new DeepSeekFailure(PROVIDER_DISABLED);
    }
    @Override public CompletionStage<Void> reply(String id, LiveEvent event, ConfigSnapshot config,
                                                Duration deadline, Consumer<String> sink) {
        if (closed) return CompletableFuture.failedFuture(new DeepSeekFailure(CLOSED));
        if (id == null || id.isBlank() || id.length()>128 || !valid(event,config,deadline) || sink == null)
            return CompletableFuture.failedFuture(new DeepSeekFailure(INVALID_REQUEST));
        if (!capacity.tryAcquire()) return CompletableFuture.failedFuture(new DeepSeekFailure(BUSY));
        var request = new Request(id,sink);
        if (active.putIfAbsent(id,request) != null) {
            capacity.release(); return CompletableFuture.failedFuture(new DeepSeekFailure(BUSY));
        }
        if (closed) { request.cancel(); return request.result; }
        try {
            var body = ModelOptionsUtils.jsonToObject(ModelOptionsUtils.toJsonString(Map.of(
                "model",config.settings().model(),"max_tokens",config.settings().maxOutputTokens(),"stream",true,
                "reasoning_effort","none", // Live responses do not need thinking; ignore reasoning fields even if sent.
                "messages",List.of(Map.of("role","system","content",config.settings().persona()),
                    Map.of("role","user","content",event.payload().get("content"))))),OpenAiApi.ChatCompletionRequest.class);
            Mono<String> work = Mono.defer(() -> {
                request.attempts.incrementAndGet();
                return Mono.using(transports::get, transport -> {
                    var answer = new Answer();
                    return transport.api().chatCompletionStream(body)
                        .timeout(policy.readTimeout(),Mono.error(new DeepSeekFailure(READ_TIMEOUT)))
                        .doOnNext(answer::append).then(Mono.fromSupplier(answer::finish))
                        .onErrorMap(error -> {
                            var failure=DeepSeekFailure.sanitize(error);
                            return answer.chunks>0 && failure.diagnostic().code()==NETWORK_ERROR
                                ? new DeepSeekFailure(INCOMPLETE_RESPONSE) : failure;
                        });
                },transport -> transport.close().run());
            }).onErrorMap(DeepSeekFailure::sanitize);
            if (policy.maxAttempts()>1) work = work.retryWhen(Retry.from(signals -> signals.concatMap(signal -> {
                if (signal.totalRetries() >= policy.maxAttempts()-1 || !DeepSeekFailure.retryable(signal.failure()))
                    return Mono.error(signal.failure());
                return Mono.delay(policy.retryDelay());
            })));
            // The one wall-clock deadline encloses all attempts and backoff, not each SSE chunk.
            request.subscription.update(work.timeout(deadline,Mono.error(new DeepSeekFailure(OVERALL_TIMEOUT)))
                .contextWrite(DeepSeekHttp::redactedLateErrors)
                .subscribe(request::success,request::failure));
        } catch (RuntimeException error) { request.failure(error); }
        return request.result;
    }
    @Override public void cancel(String id) { var request = active.get(id); if (request != null) request.cancel(); }
    @Override public void close() { closed = true; active.values().forEach(Request::cancel); }
    public synchronized Optional<DeepSeekFailure.Diagnostic> diagnostic(String id) {
        recent.entrySet().removeIf(e -> System.nanoTime()-e.getValue().at() >= TimeUnit.MINUTES.toNanos(5));
        return Optional.ofNullable(recent.get(id)).map(Recent::value);
    }
    private synchronized void remember(String id, DeepSeekFailure.Diagnostic value) {
        if (recent.size()>=128) recent.remove(recent.keySet().iterator().next());
        recent.put(id,new Recent(value,System.nanoTime()));
    }
    private final class Request {
        final String id;
        final Consumer<String> sink;
        final long start = System.nanoTime();
        final AtomicBoolean terminal = new AtomicBoolean();
        final AtomicInteger attempts = new AtomicInteger();
        final Disposable.Swap subscription = Disposables.swap();
        final CompletableFuture<Void> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) { return Request.this.cancel(); }
        };
        Request(String id, Consumer<String> sink) { this.id = id; this.sink = sink; }
        boolean claim() {
            if (!terminal.compareAndSet(false,true)) return false;
            subscription.dispose(); active.remove(id,this); capacity.release(); return true;
        }
        DeepSeekFailure.Diagnostic outcome(DeepSeekFailure.Code code, int status) {
            var value = new DeepSeekFailure.Diagnostic(code,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),attempts.get(),status);
            remember(id,value); return value;
        }
        void success(String text) {
            if (!claim()) return;
            // No adapter/global/request monitor is held around caller code or completion callbacks.
            // Success commits delivery before cancellation; the engine still fences the session/epoch.
            try { sink.accept(text); outcome(SUCCESS,200); result.complete(null); }
            catch (RuntimeException error) { result.completeExceptionally(new DeepSeekFailure(outcome(CALLBACK_FAILED,0))); }
        }
        void failure(Throwable error) {
            if (!claim()) return;
            var cause = DeepSeekFailure.sanitize(error).diagnostic();
            result.completeExceptionally(new DeepSeekFailure(outcome(cause.code(),cause.httpStatus())));
        }
        boolean cancel() {
            if (!claim()) return false;
            outcome(CANCELLED,0);
            // Completing exceptionally with CancellationException retains CompletableFuture.isCancelled().
            result.completeExceptionally(new CancellationException(CANCELLED.name())); return true;
        }
    }
    private static final class Answer {
        final StringBuilder text = new StringBuilder();
        boolean finished;
        int chunks;
        void append(OpenAiApi.ChatCompletionChunk chunk) {
            if (++chunks>4096 || chunk.choices()==null || chunk.choices().size()>1) throw new DeepSeekFailure(INVALID_RESPONSE);
            for (var choice : chunk.choices()) {
                if (choice.index()!=null && choice.index()!=0) throw new DeepSeekFailure(INVALID_RESPONSE);
                var delta = choice.delta();
                if (delta!=null) {
                    if (delta.toolCalls()!=null && !delta.toolCalls().isEmpty()) throw new DeepSeekFailure(INVALID_RESPONSE);
                    String content = delta.content(); // reasoningContent is deliberately never used.
                    if (content!=null && !content.isEmpty()) {
                        if (finished) throw new DeepSeekFailure(INVALID_RESPONSE);
                        if (text.length()+content.length()>2000) throw new DeepSeekFailure(OUTPUT_LIMIT);
                        text.append(content);
                    }
                }
                if (choice.finishReason()!=null) {
                    switch (choice.finishReason().name()) {
                        case "STOP" -> finished = true;
                        case "LENGTH" -> throw new DeepSeekFailure(OUTPUT_TRUNCATED);
                        case "CONTENT_FILTER" -> throw new DeepSeekFailure(CONTENT_FILTERED);
                        default -> throw new DeepSeekFailure(INVALID_RESPONSE);
                    }
                }
            }
        }
        String finish() {
            if (!finished) throw new DeepSeekFailure(INCOMPLETE_RESPONSE);
            if (text.toString().isBlank()) throw new DeepSeekFailure(INVALID_RESPONSE);
            return text.toString();
        }
    }
    private static boolean valid(LiveEvent event, ConfigSnapshot config, Duration deadline) {
        if (event==null || event.type()!=LiveEvent.Type.COMMENT || event.payload()==null || config==null || config.settings()==null
            || deadline==null || deadline.isNegative() || deadline.isZero() || deadline.compareTo(Duration.ofSeconds(60))>0) return false;
        var settings = config.settings();
        return event.payload().get("content") instanceof String content && !content.isBlank() && content.length()<=2000
            && settings.model()!=null && settings.model().matches("[A-Za-z0-9._:/-]{1,100}")
            && settings.persona()!=null && settings.persona().length()<=8000 && settings.maxOutputTokens()>=1 && settings.maxOutputTokens()<=2048;
    }
}
