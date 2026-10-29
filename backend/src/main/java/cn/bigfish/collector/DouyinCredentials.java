package cn.bigfish.collector;

import java.time.Instant;
import java.util.Map;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/** Backend-only, per-attempt credentials. Never serialize this object or persist it in config. */
public final class DouyinCredentials {
    private final String cookie;
    private final String userAgent;
    private final String userUniqueId;
    private final Instant expiresAt;

    public DouyinCredentials(String cookie, String userAgent, String userUniqueId, Instant expiresAt) {
        this.cookie = cookie; this.userAgent = userAgent; this.userUniqueId = userUniqueId; this.expiresAt = expiresAt;
    }
    void validate(Instant now) throws DouyinFailure {
        if (cookie == null || cookie.isBlank() || userAgent == null || userAgent.isBlank()
                || userUniqueId == null || userUniqueId.isBlank()) throw new DouyinFailure(CREDENTIALS_MISSING);
        if (cookie.length() > 16384 || userAgent.length() > 2048 || cookie.contains("\r") || cookie.contains("\n")
                || userAgent.contains("\r") || userAgent.contains("\n") || !userUniqueId.matches("[0-9]{1,30}")
                || expiresAt == null) throw new DouyinFailure(CREDENTIALS_INVALID);
        if (!expiresAt.isAfter(now)) throw new DouyinFailure(CREDENTIALS_EXPIRED);
    }
    Map<String, String> headers(DouyinRoomInput room) {
        return Map.of("Cookie", cookie, "User-Agent", userAgent,
                "Origin", "https://live.douyin.com", "Referer", room.pageUri().toString());
    }
    String userAgent() { return userAgent; }
    String userUniqueId() { return userUniqueId; }
    @Override public String toString() { return "DouyinCredentials[redacted]"; }
}
