package cn.bigfish.collector;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Sensitive connection material stays in memory and is never included in toString or an event. */
public final class DouyinConnection {
    public enum Heartbeat { WEBSOCKET_PING, BINARY_FRAME }
    final String internalRoomId;
    final URI endpoint;
    final Map<String, String> headers;
    final boolean ended;
    final Heartbeat heartbeat;

    public DouyinConnection(String internalRoomId, URI endpoint, Map<String, String> headers, Heartbeat heartbeat) {
        this(internalRoomId, endpoint, headers, heartbeat, false);
    }

    private DouyinConnection(String room, URI endpoint, Map<String, String> headers, Heartbeat heartbeat, boolean ended) {
        if (room == null || !room.matches("[0-9]{1,30}")) throw new IllegalArgumentException("Invalid internal room ID");
        if (!ended) {
            String host = endpoint == null ? null : endpoint.getHost();
            if (host == null || !"wss".equals(endpoint.getScheme())
                    || !host.matches("webcast[0-9]+-ws-web-[a-z0-9-]+\\.douyin\\.com")
                    || !"/webcast/im/push/v2/".equals(endpoint.getPath())
                    || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                    || endpoint.getUserInfo() != null || endpoint.getFragment() != null || heartbeat == null) {
                throw new IllegalArgumentException("Unsupported Douyin endpoint");
            }
            Set<String> allowed = Set.of("cookie", "user-agent", "origin", "referer");
            if (headers == null || headers.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || !allowed.contains(entry.getKey().toLowerCase(Locale.ROOT)) || entry.getValue() == null
                    || entry.getValue().length() > 16384 || entry.getValue().contains("\r") || entry.getValue().contains("\n"))) {
                throw new IllegalArgumentException("Invalid connection headers");
            }
        }
        this.internalRoomId = room; this.endpoint = endpoint;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.heartbeat = heartbeat; this.ended = ended;
    }

    public static DouyinConnection ended(String internalRoomId) {
        return new DouyinConnection(internalRoomId, null, Map.of(), Heartbeat.WEBSOCKET_PING, true);
    }
    @Override public String toString() { return "DouyinConnection[redacted]"; }
}
