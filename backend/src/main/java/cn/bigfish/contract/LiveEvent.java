package cn.bigfish.contract;

import java.time.Instant;
import java.util.Map;

/** Contract v1. Platform ids stay strings: JavaScript cannot safely represent all 64-bit ids. */
public record LiveEvent(int schemaVersion, String eventId, String sessionId, String roomId,
                        Type type, String userId, String username, Instant receivedAt,
                        Map<String, Object> payload) {
    public enum Type { COMMENT, GIFT, FOLLOW, ENTRANCE, LIKE }
}
