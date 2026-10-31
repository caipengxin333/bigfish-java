package cn.bigfish.conversation;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static cn.bigfish.conversation.DeepSeekFailure.Code.*;
import static cn.bigfish.conversation.DeepSeekFixture.*;
import static org.assertj.core.api.Assertions.*;

class DeepSeekRecoveryTest {
    @Test void httpFailuresAreDistinctSanitizedBoundedAndNextRequestRecovers() throws Exception {
        var cases=Map.of(401,AUTHENTICATION_FAILED,402,INSUFFICIENT_BALANCE,403,PERMISSION_DENIED,404,MODEL_NOT_FOUND,
            400,INVALID_REQUEST,422,INVALID_REQUEST,429,RATE_LIMITED,500,SERVER_ERROR,503,SERVER_ERROR);
        for(var entry:cases.entrySet()) {
            int attempts=entry.getKey()>=500 || entry.getKey()==429 ? 2 : 1;
            try(var f=new DeepSeekFixture((e,n) -> {
                if(n<=attempts) json(e,entry.getKey(),"{\"error\":{\"message\":\"unsafe-upstream-marker\"}}"); else stream(e,success());
            }); var adapter=f.adapter()) {
                var output=new ArrayList<String>();
                var error=failure(adapter.reply("bad",event(),config(),Duration.ofSeconds(3),output::add));
                assertThat(error.diagnostic().code()).isEqualTo(entry.getValue());
                assertThat(error.diagnostic().httpStatus()).isEqualTo(entry.getKey());
                assertThat(error.diagnostic().attempts()).isEqualTo(attempts);
                assertThat(error.diagnostic().elapsedMs()).isBetween(0L,3000L);
                assertThat(error.getCause()).isNull(); assertThat(error.toString()).doesNotContain("unsafe-upstream-marker");
                assertThat(output).isEmpty(); assertThat(f.calls.get()).isEqualTo(attempts);
                adapter.reply("recovered",event(),config(),Duration.ofSeconds(2),output::add).toCompletableFuture().get(3,TimeUnit.SECONDS);
                assertThat(output).containsExactly("hello"); assertThat(f.calls.get()).isEqualTo(attempts+1);
                assertThat(f.closed.get()).isEqualTo(attempts+1);
            }
        }
    }
    @Test void unknownModelMachineCodeIsNotRetriedAndDoesNotExposeServerBody() throws Exception {
        try(var f=new DeepSeekFixture((e,n) -> json(e,400,"{\"error\":{\"code\":\"model_not_found\",\"message\":\"unsafe\"}}")); var a=f.adapter()) {
            assertThat(failure(a.reply("unknown",event(),config(),Duration.ofSeconds(2),s -> {})).diagnostic().code()).isEqualTo(MODEL_NOT_FOUND);
            assertThat(f.calls.get()).isEqualTo(1);
        }
    }
    @Test void retryCanRecoverButCannotOutliveOverallBudget() throws Exception {
        try(var f=new DeepSeekFixture((e,n) -> { if(n==1) json(e,503,"{}"); else stream(e,success()); }); var a=f.adapter()) {
            var output=new ArrayList<String>();
            a.reply("ok",event(),config(),Duration.ofSeconds(2),output::add).toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertThat(output).containsExactly("hello"); assertThat(a.diagnostic("ok").orElseThrow().attempts()).isEqualTo(2);
        }
        try(var f=new DeepSeekFixture((e,n) -> json(e,429,"{}"));
            var a=new DeepSeekConversation(f.transports(),new DeepSeekConversation.Policy(Duration.ofSeconds(1),Duration.ofMillis(500),2))) {
            var error=failure(a.reply("budget",event(),config(),Duration.ofMillis(100),s -> {}));
            assertThat(error.diagnostic().code()).isEqualTo(OVERALL_TIMEOUT);
            Thread.sleep(550); assertThat(f.calls.get()).isEqualTo(1);
        }
    }
    @Test void malformedPartialTruncatedAndOversizedOutputNeverPublishPartialText() throws Exception {
        var cases=Map.of(chunk("partial"),INCOMPLETE_RESPONSE,"data: {broken}\n\n",INVALID_RESPONSE,
            chunk("partial")+end("length"),OUTPUT_TRUNCATED,chunk("filtered")+end("content_filter"),CONTENT_FILTERED,
            chunk("x".repeat(2001))+end("stop"),OUTPUT_LIMIT,end("stop"),INVALID_RESPONSE);
        for(var entry:cases.entrySet()) {
            try(var f=new DeepSeekFixture((e,n) -> stream(e,entry.getKey())); var a=f.adapter()) {
                var emitted=new AtomicBoolean();
                assertThat(failure(a.reply("bad-stream",event(),config(),Duration.ofSeconds(2),s -> emitted.set(true))).diagnostic().code()).isEqualTo(entry.getValue());
                assertThat(emitted).isFalse(); assertThat(f.calls.get()).isEqualTo(1); assertThat(f.closed.get()).isEqualTo(1);
            }
        }
    }
    @Test void thinkingIsIgnoredAndEachTaskKeepsItsOwnConfiguration() throws Exception {
        var firstStarted=new CountDownLatch(1); var finishFirst=new CountDownLatch(1);
        try(var f=new DeepSeekFixture((e,n) -> {
            if(n==1) { firstStarted.countDown(); finishFirst.await(2,TimeUnit.SECONDS); }
            stream(e,"data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"do-not-broadcast\"}}]}\n\n"+success());
        }); var a=f.adapter()) {
            var out=new CopyOnWriteArrayList<String>();
            var old=a.reply("old",event(),config("model-old","old persona",128,1),Duration.ofSeconds(3),out::add);
            assertThat(firstStarted.await(1,TimeUnit.SECONDS)).isTrue();
            a.reply("new",event(),config("model-new","new persona",256,2),Duration.ofSeconds(2),out::add).toCompletableFuture().get(3,TimeUnit.SECONDS);
            finishFirst.countDown(); old.toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertThat(out).containsExactly("hello","hello");
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper(); var bodies=f.bodies.stream().map(s -> {
                try { return mapper.readTree(s); } catch(Exception e) { throw new AssertionError(e); }
            }).toList();
            assertThat(bodies.get(0).path("model").asText()).isEqualTo("model-old");
            assertThat(bodies.get(1).path("model").asText()).isEqualTo("model-new");
            assertThat(bodies.get(0).path("max_tokens").asInt()).isEqualTo(128);
            assertThat(bodies.get(1).path("max_tokens").asInt()).isEqualTo(256);
            assertThat(bodies.get(0).path("messages").get(0).path("content").asText()).isEqualTo("old persona");
            assertThat(bodies.get(1).path("messages").get(0).path("content").asText()).isEqualTo("new persona");
            assertThat(bodies.get(0).path("reasoning_effort").asText()).isEqualTo("none");
        }
    }
    @Test void idleReadTimeoutIsDistinctAndNextRequestRecovers() throws Exception {
        try(var f=new DeepSeekFixture((e,n) -> { if(n==1) Thread.sleep(1000); stream(e,success()); });
            var a=new DeepSeekConversation(f.transports(),new DeepSeekConversation.Policy(Duration.ofMillis(150),Duration.ZERO,1))) {
            assertThat(failure(a.reply("idle",event(),config(),Duration.ofSeconds(2),s -> {})).diagnostic().code()).isEqualTo(READ_TIMEOUT);
            var out=new ArrayList<String>();
            a.reply("next",event(),config(),Duration.ofSeconds(2),out::add).toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertThat(out).containsExactly("hello");
        }
    }
    @Test void connectTimeoutAndNetworkFailureHaveSanitizedCodes() throws Exception {
        for(var entry:Map.of(new java.net.http.HttpConnectTimeoutException("unsafe"),CONNECT_TIMEOUT,
            new java.net.ConnectException("unsafe"),NETWORK_ERROR).entrySet()) {
            try(var a=new DeepSeekConversation(() -> { throw new CompletionException(entry.getKey()); })) {
                var error=failure(a.reply("failed-connect",event(),config(),Duration.ofSeconds(1),s -> {}));
                assertThat(error.diagnostic().code()).isEqualTo(entry.getValue());
                assertThat(error.getCause()).isNull(); assertThat(error.getMessage()).doesNotContain("unsafe");
            }
        }
    }
}
