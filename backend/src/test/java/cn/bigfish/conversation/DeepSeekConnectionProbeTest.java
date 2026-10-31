package cn.bigfish.conversation;

import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static cn.bigfish.conversation.DeepSeekFailure.Code.*;
import static cn.bigfish.conversation.DeepSeekConnectionProbe.Verification.*;
import static cn.bigfish.conversation.DeepSeekFixture.*;
import static org.assertj.core.api.Assertions.*;

class DeepSeekConnectionProbeTest {
    @Test void authModelAndGenerationAreSeparateAndUnknownModelsAreNotGuessed() throws Exception {
        try(var f=new DeepSeekFixture((e,n) -> {
            assertThat(e.getRequestMethod()).isEqualTo("GET"); assertThat(e.getRequestURI().getPath()).isEqualTo("/models");
            json(e,200,"{\"object\":\"list\",\"data\":[{\"id\":\"fresh-model-id\",\"max_output_tokens\":4096}]}");
        }); var p=new DeepSeekConnectionProbe(f.transports())) {
            var valid=p.check("list","fresh-model-id",Duration.ofSeconds(2)).toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertThat(valid.authentication()).isEqualTo(PASSED); assertThat(valid.selectedModel()).isEqualTo(PASSED);
            assertThat(valid.generation()).isEqualTo(NOT_CHECKED); assertThat(valid.availableModels()).containsExactly("fresh-model-id");
            var unknown=p.check("bad","unknown",Duration.ofSeconds(2)).toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertThat(unknown.authentication()).isEqualTo(PASSED); assertThat(unknown.selectedModel()).isEqualTo(FAILED);
            assertThat(unknown.diagnostic().code()).isEqualTo(MODEL_NOT_FOUND); assertThat(unknown.generation()).isEqualTo(NOT_CHECKED);
            assertThat(f.closed.get()).isEqualTo(2);
        }
    }
    @Test void authFailureDoesNotCountAsModelOrGenerationVerification() throws Exception {
        try(var f=new DeepSeekFixture((e,n) -> json(e,401,"{\"error\":{\"message\":\"unsafe\"}}")); var p=new DeepSeekConnectionProbe(f.transports())) {
            var result=p.check("auth","model",Duration.ofSeconds(2)).toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertThat(result.authentication()).isEqualTo(FAILED); assertThat(result.selectedModel()).isEqualTo(NOT_CHECKED);
            assertThat(result.generation()).isEqualTo(NOT_CHECKED); assertThat(result.diagnostic().code()).isEqualTo(AUTHENTICATION_FAILED);
            assertThat(result.toString()).doesNotContain("unsafe"); assertThat(f.calls.get()).isEqualTo(1);
        }
    }
    @Test void modelCountResponseSizeAndIdentifiersAreBounded() throws Exception {
        String excessive="{\"object\":\"list\",\"data\":["+java.util.stream.IntStream.range(0,101).mapToObj(i -> "{\"id\":\"m"+i+"\"}").collect(java.util.stream.Collectors.joining(","))+"]}";
        for(String body:java.util.List.of(excessive,"{\"object\":\"list\",\"data\":[{\"id\":\"unsafe\\nname\"}]}","{\"padding\":\""+"x".repeat(70_000)+"\"}")) {
            try(var f=new DeepSeekFixture((e,n) -> json(e,200,body)); var p=new DeepSeekConnectionProbe(f.transports())) {
                var result=p.check("bounded",null,Duration.ofSeconds(2)).toCompletableFuture().get(3,TimeUnit.SECONDS);
                assertThat(result.diagnostic().code()).isEqualTo(INVALID_RESPONSE);
                assertThat(result.availableModels()).isEmpty(); assertThat(f.closed.get()).isEqualTo(1);
            }
        }
    }
    @Test void probeHasOverallDeadlineAndCancellationReleasesTransport() throws Exception {
        var entered=new CountDownLatch(1);
        try(var f=new DeepSeekFixture((e,n) -> { entered.countDown(); Thread.sleep(1500); json(e,200,"{}"); });
            var p=new DeepSeekConnectionProbe(f.transports())) {
            var timeout=p.check("timeout",null,Duration.ofMillis(100)).toCompletableFuture().get(1,TimeUnit.SECONDS);
            assertThat(timeout.diagnostic().code()).isEqualTo(OVERALL_TIMEOUT);
            var cancelled=p.check("cancel",null,Duration.ofSeconds(2)).toCompletableFuture();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); p.cancel("cancel");
            assertThat(cancelled).isCancelled(); assertThat(f.closed.get()).isEqualTo(2);
        }
    }
}
