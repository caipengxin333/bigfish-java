package cn.bigfish.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.ai.model.SimpleApiKey;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import static cn.bigfish.conversation.DeepSeekFailure.Code.*;

/** Explicit GET /models only. Never equates authentication or model presence with successful generation. */
public final class DeepSeekConnectionProbe implements AutoCloseable {
    public enum Verification { NOT_CHECKED, PASSED, FAILED }
    public record Check(Verification authentication, Verification selectedModel, Verification generation,
                        List<String> availableModels, DeepSeekFailure.Diagnostic diagnostic) {
        public Check { availableModels = List.copyOf(availableModels); }
    }
    private record Pending(CompletableFuture<Check> result, Disposable.Swap subscription) {}
    private final Supplier<DeepSeekConversation.Transport> transports;
    private final ConcurrentHashMap<String,Pending> active = new ConcurrentHashMap<>();
    private final Semaphore capacity = new Semaphore(2);
    private volatile boolean closed;
    public DeepSeekConnectionProbe(Supplier<DeepSeekConversation.Transport> transports) { this.transports = Objects.requireNonNull(transports); }
    public static DeepSeekConnectionProbe create(boolean allowPaid, String key) {
        DeepSeekConversation.requireEnabled(allowPaid,key);
        return new DeepSeekConnectionProbe(() -> DeepSeekHttp.open(DeepSeekHttp.OFFICIAL,new SimpleApiKey(key),Duration.ofSeconds(3)));
    }
    /** selectedModel=null lists the catalog without choosing a model. No model names are assumed. */
    public CompletionStage<Check> check(String requestId, String selectedModel, Duration deadline) {
        if (closed) return CompletableFuture.failedFuture(new DeepSeekFailure(CLOSED));
        if (requestId==null || requestId.isBlank() || requestId.length()>128 || deadline==null || deadline.isNegative()
            || deadline.isZero() || deadline.compareTo(Duration.ofSeconds(15))>0
            || (selectedModel!=null && !selectedModel.matches("[A-Za-z0-9._:/-]{1,100}")))
            return CompletableFuture.failedFuture(new DeepSeekFailure(INVALID_REQUEST));
        if (!capacity.tryAcquire()) return CompletableFuture.failedFuture(new DeepSeekFailure(BUSY));
        var result = new CompletableFuture<Check>(); var subscription = Disposables.swap();
        var pending = new Pending(result,subscription);
        if (active.putIfAbsent(requestId,pending)!=null) { capacity.release(); return CompletableFuture.failedFuture(new DeepSeekFailure(BUSY)); }
        result.whenComplete((value,error) -> { subscription.dispose(); active.remove(requestId,pending); capacity.release(); });
        if (closed) { result.cancel(false); return result; }
        long start = System.nanoTime();
        subscription.update(Mono.using(transports::get, transport -> transport.web().get().uri("/models")
            .retrieve().bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(new DeepSeekFailure(INVALID_RESPONSE)))
            .timeout(Duration.ofSeconds(5),Mono.error(new DeepSeekFailure(READ_TIMEOUT))).map(DeepSeekConnectionProbe::models),
            transport -> transport.close().run())
            .timeout(deadline,Mono.error(new DeepSeekFailure(OVERALL_TIMEOUT)))
            .map(models -> new Check(Verification.PASSED,selectedModel==null ? Verification.NOT_CHECKED
                : models.contains(selectedModel) ? Verification.PASSED : Verification.FAILED,Verification.NOT_CHECKED,models,
                diagnostic(start,selectedModel!=null && !models.contains(selectedModel) ? MODEL_NOT_FOUND : SUCCESS,200)))
            .onErrorResume(error -> {
                var failure = DeepSeekFailure.sanitize(error).diagnostic();
                return Mono.just(new Check(failure.code()==AUTHENTICATION_FAILED ? Verification.FAILED : Verification.NOT_CHECKED,
                    Verification.NOT_CHECKED,Verification.NOT_CHECKED,List.of(),diagnostic(start,failure.code(),failure.httpStatus())));
            }).contextWrite(DeepSeekHttp::redactedLateErrors)
            .subscribe(result::complete,error -> result.completeExceptionally(new DeepSeekFailure(INVALID_RESPONSE))));
        return result;
    }
    public void cancel(String id) { var pending = active.get(id); if (pending!=null) pending.result().cancel(false); }
    @Override public void close() { closed=true; active.values().forEach(p -> p.result().cancel(false)); }
    private static DeepSeekFailure.Diagnostic diagnostic(long start, DeepSeekFailure.Code code, int status) {
        return new DeepSeekFailure.Diagnostic(code,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),1,status);
    }
    private static List<String> models(JsonNode root) {
        if (!"list".equals(root.path("object").asText()) || !root.path("data").isArray() || root.path("data").size()>100)
            throw new DeepSeekFailure(INVALID_RESPONSE);
        var models = new LinkedHashSet<String>();
        for (var node : root.path("data")) {
            if (!node.path("id").isTextual() || !node.path("id").asText().matches("[A-Za-z0-9._:/-]{1,100}")
                || !models.add(node.path("id").asText())) throw new DeepSeekFailure(INVALID_RESPONSE);
        }
        return List.copyOf(models);
    }
}
