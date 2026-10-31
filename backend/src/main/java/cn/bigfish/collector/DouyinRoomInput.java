package cn.bigfish.collector;

import java.net.URI;
import static cn.bigfish.collector.DouyinFailure.Code.UNSUPPORTED_ROOM_URL;

/** A web room number is not an internal broadcast room ID. Only this canonical host is fetched. */
public record DouyinRoomInput(String webRoomId) {
    public DouyinRoomInput {
        if (webRoomId == null || !webRoomId.matches("[0-9]{1,30}")) throw new IllegalArgumentException("Invalid web room ID");
    }
    public static DouyinRoomInput parse(String input) throws DouyinFailure {
        if (input == null || input.length() > 2048) throw new DouyinFailure(UNSUPPORTED_ROOM_URL);
        String value = input.strip();
        if (value.matches("[0-9]{1,30}")) return new DouyinRoomInput(value);
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"live.douyin.com".equals(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/[0-9]{1,30}/?")) throw new IllegalArgumentException();
            return new DouyinRoomInput(uri.getPath().replace("/", ""));
        } catch (IllegalArgumentException exception) { throw new DouyinFailure(UNSUPPORTED_ROOM_URL); }
    }
    URI pageUri() { return URI.create("https://live.douyin.com/" + webRoomId); }
    @Override public String toString() { return "DouyinRoomInput[redacted]"; }
}
