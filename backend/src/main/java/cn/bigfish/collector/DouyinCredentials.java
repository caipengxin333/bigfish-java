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
    /** Resolve the visitor from the same Cookie/UA room page. No browser access or storage. */
    public DouyinCredentials(String cookie, String userAgent, Instant expiresAt) {
        this(cookie, userAgent, null, expiresAt);
    }
    /** Local input checks only. Null/empty visitor requests page discovery; this does not verify login. */
    public void validateForResolution(Instant now) throws DouyinFailure {
        if (cookie == null || cookie.isBlank() || userAgent == null || userAgent.isBlank())
            throw new DouyinFailure(CREDENTIALS_MISSING);
        if (cookie.length() > 16384 || userAgent.length() > 2048 || !headerText(cookie) || !headerText(userAgent)
                || hasManualVisitor() && !userUniqueId.matches("[1-9][0-9]{0,29}") || expiresAt == null)
            throw new DouyinFailure(CREDENTIALS_INVALID);
        if (!expiresAt.isAfter(now)) throw new DouyinFailure(CREDENTIALS_EXPIRED);
    }
    void validate(Instant now) throws DouyinFailure {
        validateForResolution(now);
        if (!hasManualVisitor()) throw new DouyinFailure(CREDENTIALS_MISSING);
    }
    private static boolean headerText(String value) { return value.chars().allMatch(c -> c >= 32 && c <= 126); }
    boolean hasManualVisitor() { return userUniqueId != null && !userUniqueId.isEmpty(); }
    DouyinCredentials withVisitor(String visitor) { return new DouyinCredentials(cookie, userAgent, visitor, expiresAt); }
    /** Presence of nonempty login-named fields, NOT proof the platform accepts this session. */
    public boolean hasLoginFields() {
        if (cookie == null || cookie.length() > 16384) return false;
        for (String entry : cookie.split(";")) {
            String[] pair = entry.strip().split("=", 2);
            if (pair.length == 2 && (pair[0].equals("sessionid") || pair[0].equals("sessionid_ss"))
                    && !pair[1].isBlank()) return true;
        }
        return false;
    }
    Map<String, String> headers(DouyinRoomInput room) {
        return headers(cookie, userAgent, room);
    }
    static Map<String, String> headers(String cookie, String userAgent, DouyinRoomInput room) {
        return Map.of("Cookie", cookie, "User-Agent", userAgent,
                "Origin", "https://live.douyin.com", "Referer", room.pageUri().toString());
    }
    String userAgent() { return userAgent; }
    String userUniqueId() { return userUniqueId; }
    @Override public String toString() { return "DouyinCredentials[redacted]"; }
}
