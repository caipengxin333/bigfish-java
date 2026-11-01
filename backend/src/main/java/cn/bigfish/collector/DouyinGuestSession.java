package cn.bigfish.collector;

import java.net.HttpCookie;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/** Independent guest bootstrap. Each attempt owns its cookie/visitor data; no disk or cross-session cache. */
final class DouyinGuestSession {
    static final String USER_AGENT = "Mozilla/5.0 (compatible; BigFishCollector/0.1; Java/21)";
    private static final URI HOME = URI.create("https://live.douyin.com/");
    private final DouyinRoomResolver resolver;
    private final Clock clock;

    DouyinGuestSession(DouyinRoomResolver resolver, Clock clock) { this.resolver = resolver; this.clock = clock; }

    Access resolve(String input, String expectedRoom) throws DouyinFailure, InterruptedException {
        var requested = DouyinRoomInput.parse(input);
        // Fresh context on every reconnect: no cached login, fixed visitor ID, fabricated token or challenge execution.
        var home = resolver.fetch(HOME, Map.of("User-Agent", USER_AGENT));
        Cookie cookie = updateCookie(null, home.setCookies(), clock.instant());
        cookie.validate(clock.instant());
        var page = resolver.fetch(requested.pageUri(), DouyinCredentials.headers(cookie.header(), USER_AGENT, requested));
        var room = DouyinRoomPage.parse(page.body(), requested);
        if (!room.live() || expectedRoom != null && !expectedRoom.equals(room.internalRoomId())) return new Access(room, null);
        cookie = updateCookie(cookie, page.setCookies(), clock.instant());
        cookie.validate(clock.instant());
        String visitor = DouyinRoomPage.guestId(page.body());
        var credentials = new DouyinCredentials(cookie.header(), USER_AGENT, visitor, cookie.expiresAt());
        credentials.validate(clock.instant());
        return new Access(room, credentials);
    }

    /** Only the platform-issued ttwid is forwarded, never arbitrary Set-Cookie or login cookies. */
    static Cookie updateCookie(Cookie previous, List<String> headers, Instant now) throws DouyinFailure {
        if (headers.size() > 32 || headers.stream().mapToLong(String::length).sum() > 16384) throw new DouyinFailure(RESPONSE_TOO_LARGE);
        Cookie replacement = null;
        for (String header : headers) {
            if (!header.stripLeading().startsWith("ttwid=")) continue;
            try {
                List<HttpCookie> parsed = HttpCookie.parse(header);
                if (parsed.size() != 1) throw new IllegalArgumentException();
                HttpCookie cookie = parsed.getFirst();
                String domain = cookie.getDomain(), path = cookie.getPath(), value = cookie.getValue();
                if (!cookie.getName().equals("ttwid") || value == null
                        || !value.matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]{1,4096}")
                        || domain != null && !List.of("douyin.com", ".douyin.com", "live.douyin.com", ".live.douyin.com").contains(domain)
                        || path != null && !path.equals("/")) throw new IllegalArgumentException();
                long age = cookie.getMaxAge();
                boolean explicitAge = java.util.Arrays.stream(header.split(";"))
                        .skip(1).anyMatch(attribute -> attribute.split("=", 2)[0].strip().equalsIgnoreCase("max-age"));
                if (age == 0 || explicitAge && age < 0) throw new DouyinFailure(CREDENTIALS_EXPIRED);
                // A session cookie has no server expiry. Five minutes is our local upper budget, never a validity claim.
                var expires = now.plusSeconds(age < 0 ? 300 : Math.min(300, age));
                if (replacement != null && !replacement.value().equals(value)) throw new IllegalArgumentException();
                if (replacement != null && replacement.expiresAt().isBefore(expires)) expires = replacement.expiresAt();
                replacement = new Cookie(value, expires);
            } catch (IllegalArgumentException ignored) { throw new DouyinFailure(GUEST_COOKIE_INVALID); }
        }
        Cookie result = replacement == null ? previous : replacement;
        if (result == null) throw new DouyinFailure(GUEST_COOKIE_MISSING);
        result.validate(now);
        return result;
    }

    record Cookie(String value, Instant expiresAt) {
        String header() { return "ttwid=" + value; }
        void validate(Instant now) throws DouyinFailure {
            if (!expiresAt.isAfter(now.plusSeconds(5))) throw new DouyinFailure(CREDENTIALS_EXPIRED);
        }
        @Override public String toString() { return "GuestCookie[redacted]"; }
    }
    record Access(DouyinRoom room, DouyinCredentials credentials) {
        @Override public String toString() { return "GuestAccess[redacted]"; }
    }
}
