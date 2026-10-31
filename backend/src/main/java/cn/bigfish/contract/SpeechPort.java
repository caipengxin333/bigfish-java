package cn.bigfish.contract;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
public interface SpeechPort {
    CompletionStage<AudioAsset> synthesize(String taskId, String text, String voice, Duration deadline);
    void cancel(String taskId);
    /** Release only this owned asset after the consumer has loaded it; reusable test tones are exempt. */
    default boolean release(AudioAsset asset) { return false; }
    record AudioAsset(Path path, String mimeType, Duration duration) {}
}
