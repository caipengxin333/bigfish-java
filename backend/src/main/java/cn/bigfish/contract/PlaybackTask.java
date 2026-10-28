package cn.bigfish.contract;
import java.time.Instant;

/** audioUrl is a relative, same-origin URL; never a local filesystem path. */
public record PlaybackTask(String taskId, String sessionId, long configVersion, String type,
                           String text, String audioUrl, int priority, Instant expiresAt) {}
