package cn.bigfish.event;

import cn.bigfish.contract.LiveEvent;
import java.time.Instant;
import java.util.*;

public final class EventValidator {
    private EventValidator() {}
    public static LiveEvent normalize(LiveEvent e, Instant now) {
        if (e == null || e.schemaVersion() != 1 || e.type() == null || !id(e.eventId())
            || !id(e.sessionId()) || !id(e.roomId()) || e.userId() == null || e.userId().length() > 128
            || e.username() == null || e.username().length() > 1000 || e.payload() == null) {
            throw new IllegalArgumentException("INVALID_EVENT");
        }
        var p = new HashMap<>(e.payload());
        boolean valid = switch (e.type()) {
            case COMMENT -> p.keySet().equals(Set.of("content")) && text(p.get("content"), 2000, false);
            case GIFT -> p.keySet().equals(Set.of("giftId", "giftName", "quantity", "comboId", "unitValue", "valueUnit"))
                && text(p.get("giftId"), 128, false) && text(p.get("giftName"), 100, false)
                && integer(p.get("quantity"), 1, 1_000_000) && text(p.get("comboId"), 128, true)
                && (p.get("unitValue") == null || integer(p.get("unitValue"), 0, 1_000_000_000))
                && "DOUYIN_DIAMOND".equals(p.get("valueUnit"));
            case FOLLOW, ENTRANCE -> p.isEmpty();
            case LIKE -> p.keySet().equals(Set.of("count")) && integer(p.get("count"), 1, 1_000_000);
        };
        if (!valid) throw new IllegalArgumentException("INVALID_PAYLOAD");
        if (e.type() == LiveEvent.Type.COMMENT) p.put("content", safe((String)p.get("content"), 2000));
        if (e.type() == LiveEvent.Type.GIFT) p.put("giftName", safe((String)p.get("giftName"), 100));
        return new LiveEvent(1, e.eventId(), e.sessionId(), e.roomId(), e.type(), e.userId(),
            safe(e.username(), 80), now, Collections.unmodifiableMap(p));
    }
    public static String safe(String value, int max) {
        String clean = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").strip();
        return clean.substring(0, Math.min(max, clean.length()));
    }
    private static boolean id(String s) { return text(s, 128, false); }
    private static boolean text(Object value, int max, boolean empty) {
        return value instanceof String s && s.length() <= max && (empty || !s.isBlank())
            && s.codePoints().noneMatch(Character::isISOControl);
    }
    private static boolean integer(Object value, long min, long max) {
        return (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)
            && ((Number)value).longValue() >= min && ((Number)value).longValue() <= max;
    }
}
