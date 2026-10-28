package cn.bigfish.contract;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
public interface SpeechPort {
    CompletionStage<AudioAsset> synthesize(String taskId, String text, String voice, Duration deadline);
    void cancel(String taskId);
    record AudioAsset(Path path, String mimeType, Duration duration) {}
}
