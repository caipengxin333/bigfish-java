package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.playback.*;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics;
import cn.bigfish.management.diagnostics.RuntimeDiagnostics.*;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PlaybackDiagnosticsTest {
    @TempDir Path directory;
    private PlaybackTask task(RuntimeDiagnostics d,CoreRig.MutableClock clock,int priority,int ttl) {
        String id=UUID.randomUUID().toString(); d.received("s",id,LiveEvent.Type.GIFT,1);
        return new PlaybackTask(id,"s",1,"GIFT","private text","/api/v1/audio/"+id,priority,clock.instant().plusSeconds(ttl));
    }
    @Test void startAndCompletionTimeoutsHaveDifferentReasonsAndReconnectRecovers() throws Exception {
        var clock=new CoreRig.MutableClock(); var assets=new AudioAssets(directory,clock); var d=new RuntimeDiagnostics(clock);
        var p=new PlaybackCoordinator(clock,assets); p.trackWith(d); p.begin("s");
        var content=new AudioAssets.Content(new byte[]{1},"audio/wav");
        for(boolean start:new boolean[]{false,true}) {
            var peer=new CoreRig.Peer(); p.hello(peer,peer.id()); p.ready(peer,"s",peer.id(),p.status().generation());
            var task=task(d,clock,60,60); long generation=p.status().generation();
            p.enqueue(task,content,8,p.status().epoch(),Duration.ofMillis(300)); p.tick(8);
            if(start) p.acknowledge(peer,new PlaybackAck(task.taskId(),"s",peer.id(),generation,PlaybackAck.State.STARTED,clock.instant(),null));
            clock.advance(Duration.ofSeconds(6)); p.tick(8);
            assertThat(d.snapshot().lastFailure().reason()).isEqualTo(start ? Reason.COMPLETION_TIMEOUT : Reason.START_ACK_TIMEOUT);
            assertThat(assets.size()).isZero(); assertThat(peer.closed).isTrue();
            long seq=d.snapshot().history().getLast().sequence();
            p.acknowledge(peer,new PlaybackAck(task.taskId(),"s",peer.id(),generation,PlaybackAck.State.COMPLETED,clock.instant(),null));
            assertThat(d.snapshot().history().getLast().sequence()).isEqualTo(seq);
            p.disconnected(peer);
        }
        var peer=new CoreRig.Peer(); p.hello(peer,peer.id()); p.ready(peer,"s",peer.id(),p.status().generation());
        var good=task(d,clock,60,60); p.enqueue(good,content,8,p.status().epoch(),Duration.ofMillis(300)); p.tick(8);
        p.acknowledge(peer,new PlaybackAck(good.taskId(),"s",peer.id(),p.status().generation(),PlaybackAck.State.STARTED,clock.instant(),null));
        p.acknowledge(peer,new PlaybackAck(good.taskId(),"s",peer.id(),p.status().generation(),PlaybackAck.State.COMPLETED,clock.instant(),null));
        assertThat(d.snapshot().lastPlaybackSuccessAt()).isEqualTo(clock.instant());
        assertThat(assets.size()).isZero(); p.stop("SESSION_STOPPED");
    }
    @Test void priorityRejectionShortTtlStopAndOldAcknowledgementAreRecordedSafely() throws Exception {
        var clock=new CoreRig.MutableClock(); var assets=new AudioAssets(directory,clock); var d=new RuntimeDiagnostics(clock);
        var p=new PlaybackCoordinator(clock,assets); p.trackWith(d); p.begin("s");
        var peer=new CoreRig.Peer(); p.hello(peer,peer.id()); p.ready(peer,"s",peer.id(),p.status().generation());
        var content=new AudioAssets.Content(new byte[]{1},"audio/wav");
        var gift=task(d,clock,60,10); var low=task(d,clock,50,60);
        assertThat(p.enqueue(gift,content,1,p.status().epoch(),Duration.ofSeconds(2))).isTrue();
        assertThat(p.enqueue(low,content,1,p.status().epoch(),Duration.ofSeconds(2))).isFalse();
        assertThat(d.snapshot().history().getLast().reason()).isEqualTo(Reason.QUEUE_FULL);
        assertThat(assets.size()).isEqualTo(1); p.tick(1);
        assertThat(peer.tasks).isEmpty(); assertThat(d.snapshot().history().getLast().reason()).isEqualTo(Reason.INSUFFICIENT_PLAYBACK_TTL);
        var current=task(d,clock,60,60); p.enqueue(current,content,1,p.status().epoch(),Duration.ofSeconds(2)); p.tick(1);
        p.stop("SESSION_STOPPED");
        assertThat(d.snapshot().history().getLast().stage()).isEqualTo(Stage.CANCELLED);
        assertThat(d.snapshot().history().getLast().reason()).isEqualTo(Reason.SESSION_STOPPED);
        p.begin("new"); p.acknowledge(peer,new PlaybackAck(current.taskId(),"s",peer.id(),1,PlaybackAck.State.COMPLETED,Instant.MAX,"unsafe"));
        assertThat(d.snapshot().history()).isEmpty(); assertThat(d.snapshot().lastPlaybackSuccessAt()).isNull(); assertThat(assets.size()).isZero();
    }
}
