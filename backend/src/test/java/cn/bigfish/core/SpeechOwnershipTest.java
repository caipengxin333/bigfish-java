package cn.bigfish.core;

import cn.bigfish.contract.*;
import cn.bigfish.conversation.MockConversation;
import cn.bigfish.speech.LocalTestSpeech;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SpeechOwnershipTest {
    @TempDir Path directory;
    private final AtomicInteger released=new AtomicInteger();
    private SpeechPort owned(SpeechPort.AudioAsset asset) {
        return new SpeechPort() {
            public CompletionStage<AudioAsset> synthesize(String id,String text,String voice,Duration deadline) { return CompletableFuture.completedFuture(asset); }
            public void cancel(String id) {}
            public boolean release(AudioAsset value) {
                assertThat(value).isEqualTo(asset);
                try { Files.deleteIfExists(value.path()); released.incrementAndGet(); return true; }
                catch(java.io.IOException e) { throw new IllegalStateException(e); }
            }
        };
    }
    @Test void loadedAudioSurvivesOwnedFileReleaseUntilAcknowledgement() throws Exception {
        var tone=new LocalTestSpeech(directory.resolve("audio"));
        var file=tone.synthesize("owned","你好","test",Duration.ofSeconds(1)).toCompletableFuture().get();
        try(var rig=new CoreRig(directory,new MockConversation(),owned(file))) {
            rig.engine.accept(rig.event("gift",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> released.get()==1 && !rig.peer.tasks.isEmpty());
            assertThat(Files.exists(file.path())).isFalse();
            var task=rig.peer.tasks.getFirst(); assertThat(rig.assets.get(task.taskId())).isPresent();
            rig.ack(task,PlaybackAck.State.STARTED); rig.ack(task,PlaybackAck.State.COMPLETED);
            assertThat(rig.assets.get(task.taskId())).isEmpty();
        }
    }
    @Test void oversizedDurationIsRejectedAndOwnedFileIsStillReleased() throws Exception {
        Path file=directory.resolve("audio").resolve("too-long.wav"); Files.createDirectories(file.getParent()); Files.write(file,new byte[]{1});
        var asset=new SpeechPort.AudioAsset(file,"audio/wav",Duration.ofSeconds(26));
        try(var rig=new CoreRig(directory,new MockConversation(),owned(asset))) {
            rig.engine.accept(rig.event("gift",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> released.get()==1 && !rig.engine.status().speechActive());
            assertThat(rig.peer.tasks).isEmpty(); assertThat(Files.exists(file)).isFalse();
            assertThat(rig.engine.status().lastError()).isEqualTo("NLS_AUDIO_TOO_LONG");
            assertThat(rig.engine.status().failures()).isEqualTo(1);
        }
    }
    @Test void rejectedFileLocationIsReleasedWithoutExposingOrPlayingItsBytes() throws Exception {
        Path file=directory.resolve("outside-audio.wav"); Files.write(file,new byte[]{1});
        try(var rig=new CoreRig(directory,new MockConversation(),owned(new SpeechPort.AudioAsset(file,"audio/wav",Duration.ofSeconds(1))))) {
            rig.engine.accept(rig.event("gift",LiveEvent.Type.GIFT)); rig.clock.advance(Duration.ofMillis(200));
            rig.await(() -> released.get()==1 && !rig.engine.status().speechActive());
            assertThat(rig.peer.tasks).isEmpty(); assertThat(rig.engine.status().failures()).isEqualTo(1);
        }
    }
}
