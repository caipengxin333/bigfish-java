package cn.bigfish.collector;

import java.time.Duration;

/** Limits are local to a collector instance, not changes to the public runtime contract. */
public record CollectorOptions(int queueCapacity, Duration queueTtl, int cacheCapacity,
                               Duration cacheTtl, Duration reconnectBase, Duration reconnectMax,
                               Duration heartbeatInterval, Duration idleTimeout, int maxFrameBytes) {
    public CollectorOptions {
        if (queueCapacity < 1 || cacheCapacity < 1 || maxFrameBytes < 128
                || !positive(queueTtl) || !positive(cacheTtl) || !positive(reconnectBase)
                || !positive(reconnectMax) || !positive(heartbeatInterval) || !positive(idleTimeout)
                || reconnectMax.compareTo(reconnectBase) < 0
                || idleTimeout.compareTo(heartbeatInterval) <= 0) {
            throw new IllegalArgumentException("Invalid collector limits");
        }
    }

    private static boolean positive(Duration duration) {
        return duration != null && duration.toMillis() > 0;
    }

    public static CollectorOptions defaults() {
        return new CollectorOptions(256, Duration.ofSeconds(30), 8192, Duration.ofMinutes(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(45), 2 * 1024 * 1024);
    }
}
