package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.playback.*;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PlaybackCoordinatorTest {
    @Test void durationControlsCompletionDeadlineAndShortTtlNeverStarts() throws Exception {
        var clock=new CoreRig.MutableClock(); var assets=new AudioAssets(directory,clock);
        var playback=new PlaybackCoordinator(clock,assets); playback.begin("s");
        var peer=new CoreRig.Peer(); playback.hello(peer,"p"); playback.ready(peer,"s","p",1);
        var expiredSoon=new PlaybackTask("short-ttl","s",1,"GIFT","hi","/api/v1/audio/short-ttl",60,clock.instant().plusSeconds(10));
        var content=new AudioAssets.Content(new byte[]{1},"audio/wav");
        playback.enqueue(expiredSoon,content,8,playback.status().epoch(),Duration.ofSeconds(2)); playback.tick(8);
        assertThat(peer.tasks).isEmpty(); assertThat(assets.get("short-ttl")).isEmpty();
        var task=new PlaybackTask("play","s",1,"GIFT","hi","/api/v1/audio/play",60,clock.instant().plusSeconds(60));
        playback.enqueue(task,content,8,playback.status().epoch(),Duration.ofSeconds(2)); playback.tick(8);
        playback.acknowledge(peer,new PlaybackAck("play","s","p",1,PlaybackAck.State.STARTED,clock.instant(),null));
        clock.advance(Duration.ofSeconds(6)); playback.tick(8); assertThat(playback.status().playing()).isTrue();
        clock.advance(Duration.ofSeconds(1)); playback.tick(8); assertThat(playback.status().playing()).isFalse();
        assertThat(peer.closed).isTrue(); assertThat(assets.get("play")).isEmpty();
    }
    @TempDir Path directory;
    @Test void newSessionWaitsForOldTransportToCloseBeforeGrantingAnotherLease() throws Exception {
        var clock = new CoreRig.MutableClock(); var assets = new AudioAssets(directory,clock);
        var playback = new PlaybackCoordinator(clock,assets); playback.begin("old");
        var first = new CoreRig.Peer(); var second = new CoreRig.Peer();
        playback.hello(first,"p1"); playback.begin("new"); playback.hello(second,"p2");
        assertThat(second.types).containsExactly("PLAYER_REJECTED");
        playback.disconnected(first); playback.hello(second,"p2");
        assertThat(second.types).containsExactly("PLAYER_REJECTED","PLAYER_LEASE");
    }
    @Test void leaseGenerationReadySingleFlightAndRevokedAudio() throws Exception {
        var clock = new CoreRig.MutableClock();
        var assets = new AudioAssets(directory,clock);
        var playback = new PlaybackCoordinator(clock,assets);
        playback.begin("s");
        var first = new CoreRig.Peer(); var second = new CoreRig.Peer();
        playback.hello(first,"same-player-id"); playback.hello(second,"same-player-id");
        assertThat(second.types).containsExactly("PLAYER_REJECTED");
        long generation = playback.status().generation();
        playback.ready(second,"s","same-player-id",generation);
        assertThat(playback.status().ready()).isFalse();
        playback.ready(first,"s","same-player-id",generation);
        for (int i=0;i<100;i++) {
            var task = new PlaybackTask(UUID.randomUUID().toString(),"s",1,"GIFT","谢谢","/api/v1/audio/test",60,clock.instant().plusSeconds(120));
            playback.enqueue(task,new AudioAssets.Content(new byte[]{1},"audio/wav"),8,playback.status().epoch());
            assertThat(playback.status().queued()).isLessThanOrEqualTo(8);
        }
        playback.tick(8); playback.tick(8);
        assertThat(first.tasks).hasSize(1);
        var task = first.tasks.getFirst();
        assertThat(assets.get(task.taskId())).isPresent();
        playback.acknowledge(first,new PlaybackAck(task.taskId(),"s","same-player-id",generation-1,PlaybackAck.State.COMPLETED,clock.instant(),null));
        assertThat(playback.status().playing()).isTrue();
        playback.acknowledge(first,new PlaybackAck(task.taskId(),"s","same-player-id",generation,PlaybackAck.State.COMPLETED,clock.instant(),null));
        assertThat(playback.status().playing()).isTrue(); // Must START before completing.
        playback.acknowledge(first,new PlaybackAck(task.taskId(),"s","same-player-id",generation,PlaybackAck.State.STARTED,clock.instant(),null));
        playback.disconnected(first);
        assertThat(assets.get(task.taskId())).isEmpty();
        playback.hello(second,"other"); playback.ready(second,"s","other",playback.status().generation()); playback.tick(8);
        assertThat(second.tasks).isEmpty(); // Started tasks are never replayed after reconnect.
    }
    @Test void expiredLeaseCannotResurrectAndStartTimeoutClosesPlayer() throws Exception {
        var clock = new CoreRig.MutableClock(); var assets = new AudioAssets(directory,clock);
        var playback = new PlaybackCoordinator(clock,assets); playback.begin("s");
        var peer = new CoreRig.Peer(); playback.hello(peer,"p");
        long lease = playback.status().generation();
        clock.advance(Duration.ofSeconds(15)); playback.heartbeat(peer,"s","p",lease);
        assertThat(playback.status().connected()).isFalse();
        var next = new CoreRig.Peer(); playback.hello(next,"next"); playback.ready(next,"s","next",playback.status().generation());
        var task = new PlaybackTask(UUID.randomUUID().toString(),"s",1,"COMMENT","hi","/api/v1/audio/test",50,clock.instant().plusSeconds(60));
        playback.enqueue(task,new AudioAssets.Content(new byte[]{1},"audio/wav"),8,playback.status().epoch());
        playback.tick(8); clock.advance(Duration.ofSeconds(5)); playback.tick(8);
        assertThat(next.closed).isTrue(); assertThat(next.types).contains("CANCEL_TASK");
        assertThat(assets.get(task.taskId())).isEmpty();
    }
    @Test void repeatedStartedDoesNotExtendFinishDeadlineAndExpiryDropsBeforePlay() throws Exception {
        var clock = new CoreRig.MutableClock(); var assets = new AudioAssets(directory,clock);
        var playback = new PlaybackCoordinator(clock,assets); playback.begin("s");
        var peer = new CoreRig.Peer(); playback.hello(peer,"p"); playback.ready(peer,"s","p",1);
        var task = new PlaybackTask(UUID.randomUUID().toString(),"s",1,"COMMENT","hi","/api/v1/audio/test",50,clock.instant().plusSeconds(120));
        playback.enqueue(task,new AudioAssets.Content(new byte[]{1},"audio/wav"),8,playback.status().epoch()); playback.tick(8);
        var ack = new PlaybackAck(task.taskId(),"s","p",1,PlaybackAck.State.STARTED,clock.instant(),null);
        playback.acknowledge(peer,ack);
        for (int i=0;i<3;i++) { clock.advance(Duration.ofSeconds(10)); playback.heartbeat(peer,"s","p",1); playback.acknowledge(peer,ack); }
        assertThat(playback.status().playing()).isFalse();
        assertThat(peer.types).contains("CANCEL_TASK");
    }
}
