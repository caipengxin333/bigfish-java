package cn.bigfish.conversation;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static cn.bigfish.conversation.DeepSeekFailure.Code.*;
import static cn.bigfish.conversation.DeepSeekFixture.*;
import static org.assertj.core.api.Assertions.*;

class DeepSeekLifecycleTest {
    @Test void cancelReleasesActualStreamAndNextRequestWorks() throws Exception {
        var entered=new CountDownLatch(1); var disconnected=new CountDownLatch(1);
        try(var f=new DeepSeekFixture((e,n) -> {
            if(n!=1) { stream(e,success()); return; }
            e.getResponseHeaders().set("Content-Type","text/event-stream"); e.sendResponseHeaders(200,0); entered.countDown();
            try(var out=e.getResponseBody()) {
                while(true) { out.write(": keep-alive\n\n".getBytes()); out.flush(); Thread.sleep(20); }
            } catch(Exception ignored) { disconnected.countDown(); }
        }); var a=f.adapter()) {
            var emitted=new AtomicBoolean();
            var result=a.reply("cancel",event(),config(),Duration.ofSeconds(3),s -> emitted.set(true)).toCompletableFuture();
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue(); a.cancel("cancel");
            assertThat(result).isCancelled(); assertThat(disconnected.await(2,TimeUnit.SECONDS)).isTrue(); assertThat(emitted).isFalse();
            assertThat(a.diagnostic("cancel").orElseThrow().code()).isEqualTo(CANCELLED);
            a.reply("next",event(),config(),Duration.ofSeconds(2),s -> emitted.set(true)).toCompletableFuture().get(3,TimeUnit.SECONDS);
            assertThat(emitted).isTrue(); assertThat(f.closed.get()).isEqualTo(2);
        }
    }
    @Test void callerCallbackCannotBlockCancellationOrAnotherRequest() throws Exception {
        var callbackEntered=new CountDownLatch(1); var releaseCallback=new CountDownLatch(1);
        try(var f=new DeepSeekFixture((e,n) -> stream(e,success())); var a=f.adapter()) {
            var first=a.reply("committed",event(),config(),Duration.ofSeconds(2),text -> {
                callbackEntered.countDown();
                try { releaseCallback.await(2,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertThat(callbackEntered.await(1,TimeUnit.SECONDS)).isTrue();
            try {
                CompletableFuture.runAsync(() -> a.cancel("committed")).get(500,TimeUnit.MILLISECONDS);
                a.reply("parallel",event(),config(),Duration.ofSeconds(2),s -> {}).toCompletableFuture().get(1,TimeUnit.SECONDS);
            } finally { releaseCallback.countDown(); }
            first.toCompletableFuture().get(1,TimeUnit.SECONDS);
            assertThat(a.diagnostic("committed").orElseThrow().code()).isEqualTo(SUCCESS);
        }
    }
    @Test void capacityAndCloseBoundInFlightRequestsAndSuppressCallbacks() throws Exception {
        var requests=new CountDownLatch(4); var finish=new CountDownLatch(1);
        try(var f=new DeepSeekFixture((e,n) -> { requests.countDown(); finish.await(2,TimeUnit.SECONDS); stream(e,success()); }); var a=f.adapter()) {
            var output=new AtomicInteger(); var futures=new java.util.ArrayList<CompletableFuture<Void>>();
            for(int i=0;i<4;i++) futures.add(a.reply("id-"+i,event(),config(),Duration.ofSeconds(3),s -> output.incrementAndGet()).toCompletableFuture());
            assertThat(requests.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(failure(a.reply("overflow",event(),config(),Duration.ofSeconds(1),s -> {})).diagnostic().code()).isEqualTo(BUSY);
            a.close(); finish.countDown();
            assertThat(futures).allMatch(CompletableFuture::isCancelled); assertThat(output.get()).isZero();
            assertThat(f.closed.get()).isEqualTo(4);
            assertThat(failure(a.reply("after-close",event(),config(),Duration.ofSeconds(1),s -> {})).diagnostic().code()).isEqualTo(CLOSED);
        }
    }
}
