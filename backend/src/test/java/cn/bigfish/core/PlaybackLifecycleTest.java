package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.playback.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PlaybackLifecycleTest {
    @TempDir Path directory;
    static final class Peer implements PlaybackCoordinator.Peer {
        final List<String> frames=new ArrayList<>();
        public String id() { return "reused-transport-id"; }
        public boolean send(String type,String session,Object payload) { frames.add(type); return true; }
        public void close() {}
    }
    @Test void leaseOwnershipIncludesConnectionIdentitySessionPlayerAndGeneration() throws Exception {
        var clock=new CoreRig.MutableClock(); var assets=new AudioAssets(directory,clock);
        var playback=new PlaybackCoordinator(clock,assets); playback.begin("s");
        var old=new Peer(); var current=new Peer();
        playback.hello(old,"page"); long oldLease=playback.status().generation(); playback.disconnected(old);
        playback.hello(current,"page"); long lease=playback.status().generation();
        playback.ready(old,"s","page",lease); assertThat(playback.status().ready()).isFalse();
        playback.ready(current,"old-session","page",lease); assertThat(playback.status().ready()).isFalse();
        playback.ready(current,"s","other-page",lease); assertThat(playback.status().ready()).isFalse();
        playback.ready(current,"s","page",oldLease); assertThat(playback.status().ready()).isFalse();
        playback.ready(current,"s","page",lease); assertThat(playback.status().ready()).isTrue();
        long epoch=playback.status().epoch();
        var task=new PlaybackTask("new","s",1,"COMMENT","fixture","/api/v1/audio/new",50,clock.instant().plusSeconds(90));
        playback.enqueue(task,new AudioAssets.Content(new byte[]{1},"audio/wav"),8,epoch,Duration.ofSeconds(25)); playback.tick(8);
        var started=new PlaybackAck("new","s","page",lease,PlaybackAck.State.STARTED,clock.instant(),null);
        playback.acknowledge(current,started);
        playback.acknowledge(old,new PlaybackAck("new","s","page",lease,PlaybackAck.State.COMPLETED,clock.instant(),null));
        playback.disconnected(old); playback.disconnecting(old);
        assertThat(playback.status().playing()).isTrue(); assertThat(playback.status().epoch()).isEqualTo(epoch);
        assertThat(playback.deliveryAllowed(old,"new")).isFalse(); assertThat(playback.deliveryAllowed(current,"new")).isTrue();
        clock.advance(Duration.ofSeconds(14));
        playback.heartbeat(old,"s","page",lease);
        playback.heartbeat(current,"s","wrong-page",lease);
        playback.heartbeat(current,"wrong-session","page",lease);
        playback.heartbeat(current,"s","page",oldLease);
        clock.advance(Duration.ofSeconds(1)); // Invalid traffic never extends the actual 15-second lease.
        playback.ready(current,"s","page",lease); playback.heartbeat(current,"s","page",lease);
        assertThat(playback.status().connected()).isFalse(); assertThat(playback.status().ready()).isFalse();
        assertThat(playback.status().playing()).isFalse(); assertThat(assets.size()).isZero();
        var next=new Peer(); playback.hello(next,"page"); long nextLease=playback.status().generation();
        assertThat(nextLease).isGreaterThan(lease); assertThat(playback.status().ready()).isFalse();
        playback.disconnected(current); // Delayed old close cannot revoke this new owner.
        playback.ready(next,"s","page",nextLease); assertThat(playback.status().ready()).isTrue();
        assertThat(playback.status().queued()).isZero();
        playback.stop("fixture-stop");
    }
    @Test void stopRacingReadyHeartbeatAndCompletionCannotResurrectLease() throws Exception {
        var clock=new CoreRig.MutableClock(); var assets=new AudioAssets(directory,clock);
        var playback=new PlaybackCoordinator(clock,assets);
        try(var threads=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int round=0;round<20;round++) {
                String session="s"+round; playback.begin(session); var peer=new Peer();
                playback.hello(peer,"page"); long lease=playback.status().generation();
                playback.ready(peer,session,"page",lease);
                var task=new PlaybackTask("task"+round,session,1,"GIFT","fixture","/api/v1/audio/x",60,clock.instant().plusSeconds(90));
                playback.enqueue(task,new AudioAssets.Content(new byte[]{1},"audio/wav"),8,playback.status().epoch()); playback.tick(8);
                playback.acknowledge(peer,new PlaybackAck(task.taskId(),session,"page",lease,PlaybackAck.State.STARTED,clock.instant(),null));
                var gate=new CountDownLatch(1); var callbacks=new ArrayList<Future<?>>();
                for(Runnable callback:List.<Runnable>of(() -> playback.stop("SESSION_STOPPED"),
                    () -> playback.ready(peer,session,"page",lease), () -> playback.heartbeat(peer,session,"page",lease),
                    () -> playback.acknowledge(peer,new PlaybackAck(task.taskId(),session,"page",lease,PlaybackAck.State.COMPLETED,clock.instant(),null)),
                    () -> playback.disconnected(peer))) {
                    callbacks.add(threads.submit(() -> { gate.await(); callback.run(); return null; }));
                }
                gate.countDown(); for(var callback:callbacks) callback.get(2,TimeUnit.SECONDS);
                assertThat(playback.status().connected()).isFalse(); assertThat(playback.status().ready()).isFalse();
                assertThat(playback.status().playing()).isFalse(); assertThat(playback.status().queued()).isZero();
                assertThat(assets.size()).isZero();
            }
        }
    }
}
