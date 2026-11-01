package cn.bigfish.conversation;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static cn.bigfish.conversation.DeepSeekFixture.*;
import static org.assertj.core.api.Assertions.*;

class DeepSeekStabilityTest {
    @Test void repeatedHttpRateLimitsAndServerFailuresReleaseEveryAttemptAndRecover() throws Exception {
        try(var f=new DeepSeekFixture((e,n) -> {
            switch(n%4) {
                case 1,2 -> json(e,429,"{\"error\":{\"message\":\"unsafe\"}}");
                case 3 -> json(e,503,"{}");
                default -> stream(e,success());
            }
        }); var a=new DeepSeekConversation(f.transports(),new DeepSeekConversation.Policy(Duration.ofSeconds(2),Duration.ZERO,2))) {
            var outputs=new AtomicInteger();
            for(int cycle=0;cycle<20;cycle++) {
                var error=failure(a.reply("limited-"+cycle,event(),config(),Duration.ofSeconds(3),s -> outputs.incrementAndGet()));
                assertThat(error.diagnostic().code()).isEqualTo(DeepSeekFailure.Code.RATE_LIMITED);
                assertThat(error.diagnostic().attempts()).isEqualTo(2);
                a.reply("recovery-"+cycle,event(),config(),Duration.ofSeconds(3),s -> outputs.incrementAndGet()).toCompletableFuture().get(4,TimeUnit.SECONDS);
                assertThat(a.diagnostic("recovery-"+cycle).orElseThrow().attempts()).isEqualTo(2);
                assertThat(outputs.get()).isEqualTo(cycle+1); assertThat(f.closed.get()).isEqualTo(f.calls.get());
            }
            assertThat(f.calls.get()).isEqualTo(80); assertThat(f.closed.get()).isEqualTo(80);
        }
    }
}
