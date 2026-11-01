package cn.bigfish.collector;

import cn.bigfish.contract.LiveEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;

/** Per-session state survives reconnects, but never crosses a new start(). */
final class EventNormalizer {
    private record Combo(String room, String user, String gift, String group) { }
    private final String sessionId;
    private final ExpiringCache<String, Boolean> messages;
    private final ExpiringCache<Combo, Long> combos;
    private final CollectorDiagnostics diagnostics;

    EventNormalizer(String sessionId, CollectorOptions options, CollectorDiagnostics diagnostics) {
        this.sessionId = sessionId;
        this.diagnostics = diagnostics;
        messages = new ExpiringCache<>(options.cacheCapacity(), options.cacheTtl());
        combos = new ExpiringCache<>(options.cacheCapacity(), options.cacheTtl());
    }

    Optional<LiveEvent> normalize(IncomingMessage input, Instant receivedAt) {
        diagnostics.increment(RECEIVED);
        LiveEvent.Type type;
        try { type = LiveEvent.Type.valueOf(input.kind()); }
        catch (IllegalArgumentException exception) { diagnostics.increment(UNKNOWN); return Optional.empty(); }
        require(!input.roomId().isBlank() && input.roomId().length() <= 100);
        require(input.userId().length() <= 100 && input.messageId().length() <= 200
                && input.username().length() <= 512 && input.comboId().length() <= 200);
        var payload = new LinkedHashMap<String, Object>();
        Combo combo = null;
        long cumulative = 0;
        switch (type) {
            case COMMENT -> {
                require(!input.content().isBlank() && input.content().length() <= 4096);
                payload.put("content", input.content());
            }
            case GIFT -> {
                if (input.giftId().isBlank()) throw ProtoWire.fault(InboundDiagnostics.Reason.MISSING_GIFT_ID, 0, -1, 0);
                require(input.giftId().length() <= 100
                        && input.giftName().length() <= 512 && input.cumulativeQuantity() >= 0
                        && (input.unitValue() == null || input.unitValue() >= 0));
                cumulative = Math.max(1, input.cumulativeQuantity());
                require(cumulative <= Integer.MAX_VALUE);
                long previous = 0;
                // With no stable user/group, only message-id dedup is safe. Never merge by nickname.
                if (!input.userId().isBlank() && !input.comboId().isBlank()) {
                    combo = new Combo(input.roomId(), input.userId(), input.giftId(), input.comboId());
                    Long old = combos.get(combo, receivedAt);
                    previous = old == null ? 0 : old;
                }
                if (cumulative <= previous) {
                    diagnostics.increment(DUPLICATE);
                    diagnostics.inbound().record(InboundDiagnostics.Stage.DEDUP, InboundDiagnostics.Reason.DUPLICATE_COMBO,
                            InboundDiagnostics.Kind.GIFT, null); return Optional.empty();
                }
                payload.put("giftId", input.giftId());
                payload.put("giftName", input.giftName());
                payload.put("quantity", Math.toIntExact(cumulative - previous));
                payload.put("comboId", input.comboId());
                payload.put("unitValue", input.unitValue());
                payload.put("valueUnit", "DOUYIN_DIAMOND");
            }
            case LIKE -> { require(input.likeCount() > 0); payload.put("count", input.likeCount()); }
            case ENTRANCE, FOLLOW -> { }
        }
        String eventId = input.messageId().isBlank() ? fallbackId(input) : input.messageId();
        if (messages.get(eventId, receivedAt) != null) {
            diagnostics.increment(DUPLICATE);
            diagnostics.inbound().record(InboundDiagnostics.Stage.DEDUP, InboundDiagnostics.Reason.DUPLICATE_ID,
                    InboundDiagnostics.eventKind(input.kind()), null); return Optional.empty();
        }
        messages.put(eventId, true, receivedAt);
        if (combo != null) combos.put(combo, cumulative, receivedAt);
        // Map.copyOf rejects the contract's legitimate unknown unitValue=null.
        Map<String, Object> immutablePayload = Collections.unmodifiableMap(payload);
        return Optional.of(new LiveEvent(1, eventId, sessionId, input.roomId(), type, input.userId(),
                input.username(), receivedAt, immutablePayload));
    }

    private static String fallbackId(IncomingMessage input) {
        // Refuse ambiguous packets rather than randomly identify repeated deliveries.
        require(input.createdAtMillis() > 0);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{input.kind(), input.roomId(), input.userId(),
                    Long.toString(input.createdAtMillis()), input.content(), input.giftId(), input.comboId(),
                    Long.toString(input.cumulativeQuantity()), Long.toString(input.likeCount())}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':'); digest.update(bytes);
            }
            return "derived-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid collector event");
    }
}
