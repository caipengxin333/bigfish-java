package cn.bigfish.core;

import cn.bigfish.playback.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StageSocketLifecycleTest {
    @TempDir Path directory;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final class Socket implements AutoCloseable {
        final WebSocketSession transport=mock(WebSocketSession.class);
        final AtomicBoolean open=new AtomicBoolean(true);
        final BlockingQueue<JsonNode> frames=new ArrayBlockingQueue<>(32);
        final CountDownLatch closing=new CountDownLatch(1), closed=new CountDownLatch(1);
        final CountDownLatch allowClose;
        final StageSocketHandler handler;
        Socket(StageSocketHandler handler,String id,boolean blockClose) throws Exception {
            this.handler=handler; allowClose=new CountDownLatch(blockClose ? 1 : 0);
            when(transport.getId()).thenReturn(id); when(transport.isOpen()).thenAnswer(i -> open.get());
            doAnswer(i -> { frames.add(mapper.readTree(((TextMessage)i.getArgument(0)).getPayload())); return null; })
                .when(transport).sendMessage(any());
            doAnswer(i -> { closeTransport(); return null; }).when(transport).close();
            doAnswer(i -> { closeTransport(); return null; }).when(transport).close(any(CloseStatus.class));
            handler.afterConnectionEstablished(transport);
        }
        void closeTransport() throws Exception {
            closing.countDown(); assertThat(allowClose.await(3,TimeUnit.SECONDS)).isTrue();
            open.set(false); handler.afterConnectionClosed(transport,CloseStatus.NORMAL); closed.countDown();
        }
        void send(String type,String session,Map<String,Object> payload) throws Exception {
            handler.handleMessage(transport,new TextMessage(mapper.writeValueAsString(Map.of("schemaVersion",1,
                "messageId","fixture","type",type,"sessionId",session,"payload",payload))));
        }
        JsonNode next(String type) throws Exception {
            var frame=frames.poll(2,TimeUnit.SECONDS);
            assertThat(frame).as("expected %s",type).isNotNull();
            assertThat(frame.path("type").asText()).isEqualTo(type); return frame;
        }
        long hello() throws Exception {
            send("PLAYER_HELLO","",Map.of("playerId","page"));
            return next("PLAYER_LEASE").path("payload").path("leaseGeneration").asLong();
        }
        public void close() throws Exception {
            allowClose.countDown(); open.set(false); handler.afterConnectionClosed(transport,CloseStatus.NORMAL);
            assertThat(closed.await(3,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void invalidFrameRevokesReadyButKeepsExclusionUntilActualTransportClose() throws Exception {
        var clock=new CoreRig.MutableClock(); var playback=new PlaybackCoordinator(clock,new AudioAssets(directory,clock));
        playback.begin("s"); var handler=new StageSocketHandler(playback,mapper);
        try(var first=new Socket(handler,"first",true); var second=new Socket(handler,"second",false)) {
            long lease=first.hello(); first.send("PLAYER_READY","s",Map.of("playerId","page","leaseGeneration",lease));
            handler.handleMessage(first.transport,new TextMessage("{}"));
            assertThat(first.closing.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(playback.status().connected()).isFalse(); assertThat(playback.status().ready()).isFalse();
            second.send("PLAYER_HELLO","",Map.of("playerId","page")); second.next("PLAYER_REJECTED");
            first.allowClose.countDown(); assertThat(first.closed.await(2,TimeUnit.SECONDS)).isTrue();
            long replacement=second.hello(); assertThat(replacement).isGreaterThan(lease);
            second.send("PLAYER_READY","s",Map.of("playerId","page","leaseGeneration",replacement));
            assertThat(playback.status().ready()).isTrue();
        }
    }
    @Test void delayedOldSocketCallbacksCannotRemoveOrControlSameIdReplacement() throws Exception {
        var clock=new CoreRig.MutableClock(); var playback=new PlaybackCoordinator(clock,new AudioAssets(directory,clock));
        playback.begin("s"); var handler=new StageSocketHandler(playback,mapper);
        try(var old=new Socket(handler,"reused-transport",false)) {
            old.hello(); old.close();
            try(var current=new Socket(handler,"reused-transport",false)) {
                long lease=current.hello();
                old.send("PLAYER_READY","s",Map.of("playerId","page","leaseGeneration",lease));
                assertThat(playback.status().ready()).isFalse();
                current.send("PLAYER_READY","s",Map.of("playerId","page","leaseGeneration",lease));
                handler.afterConnectionClosed(old.transport,CloseStatus.NORMAL);
                handler.handleTransportError(old.transport,new IllegalStateException("private-detail"));
                assertThat(playback.status().connected()).isTrue(); assertThat(playback.status().ready()).isTrue();
                current.send("PLAYER_HEARTBEAT","s",Map.of("playerId","page","leaseGeneration",lease));
                current.next("HEARTBEAT_ACK");
            }
        }
    }
}
