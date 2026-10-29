package cn.bigfish.collector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;

/** Experimental field adapter. Source/limitations are recorded in docs/handoffs/collector.md. */
final class DouyinProtocol {
    record Batch(byte[] acknowledgement, List<IncomingMessage> messages) {
        @Override public String toString() { return "Batch[redacted]"; }
    }
    private final int maxBytes;
    private final CollectorDiagnostics diagnostics;

    DouyinProtocol(int maxBytes, CollectorDiagnostics diagnostics) {
        this.maxBytes = maxBytes; this.diagnostics = diagnostics;
    }

    Batch decode(byte[] bytes, String resolvedRoom) throws IOException {
        if (bytes.length > maxBytes) throw ProtoWire.invalid();
        var push = ProtoWire.parse(bytes);
        String type = push.text(7);
        if (type.equals("hb") || type.equals("ack")) return new Batch(null, List.of());
        if (!type.isEmpty() && !type.equals("msg")) {
            diagnostics.increment(UNKNOWN); return new Batch(null, List.of());
        }
        byte[] payload = push.bytes(8);
        String encoding = push.text(6);
        // Some captures put gzip in a header instead of payloadEncoding; magic bytes cover both.
        boolean gzip = payload.length >= 2 && payload[0] == (byte) 0x1f && payload[1] == (byte) 0x8b;
        if (!encoding.isEmpty() && !encoding.equals("gzip") && !encoding.equals("identity")) throw ProtoWire.invalid();
        if (encoding.equals("gzip") || gzip) {
            try (var stream = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                payload = stream.readNBytes(maxBytes + 1);
                if (payload.length > maxBytes) throw ProtoWire.invalid();
            }
        }
        var response = ProtoWire.parse(payload);
        byte[] ack = null;
        if (response.number(9) != 0) {
            byte[] extension = response.bytes(5);
            if (extension.length > 8192 || !push.has(2)) throw ProtoWire.invalid();
            ack = ProtoWire.ack(push.number(2), extension);
        }
        var messages = new ArrayList<IncomingMessage>();
        for (byte[] message : response.repeatedBytes(1, 512)) {
            try {
                IncomingMessage decoded = message(message, resolvedRoom);
                if (decoded != null) messages.add(decoded);
            } catch (RuntimeException exception) { diagnostics.error(MALFORMED, 0); }
        }
        return new Batch(ack, List.copyOf(messages));
    }

    private IncomingMessage message(byte[] bytes, String resolvedRoom) {
        var envelope = ProtoWire.parse(bytes);
        String method = envelope.text(1);
        String kind = switch (method) {
            case "WebcastChatMessage" -> "COMMENT";
            case "WebcastGiftMessage" -> "GIFT";
            case "WebcastMemberMessage" -> "ENTRANCE";
            case "WebcastSocialMessage" -> "FOLLOW";
            case "WebcastLikeMessage" -> "LIKE";
            case "WebcastControlMessage" -> "CONTROL";
            default -> "UNKNOWN";
        };
        if (kind.equals("UNKNOWN")) { diagnostics.increment(UNKNOWN); return null; }
        var body = envelope.nested(2);
        var common = body.nested(1);
        String room = common.id(3);
        if (!room.isEmpty() && !room.equals(resolvedRoom)) throw ProtoWire.invalid();
        if (kind.equals("CONTROL")) {
            if (body.number(2) != 3) return null;
            kind = "ENDED";
        }
        if (kind.equals("FOLLOW") && body.number(4) != 1) { diagnostics.increment(UNKNOWN); return null; }
        var user = body.nested(kind.equals("GIFT") ? 7 : kind.equals("LIKE") ? 5 : kind.equals("ENDED") ? 0 : 2);
        String userId = user.id(1);
        if (userId.isEmpty()) userId = user.id(1028);
        String messageId = common.id(2);
        if (messageId.isEmpty()) messageId = envelope.id(3);
        String giftId = "", giftName = "", combo = "";
        Long value = null;
        long quantity = 0;
        if (kind.equals("GIFT")) {
            var gift = body.nested(15);
            giftId = body.id(2);
            if (giftId.isEmpty()) giftId = gift.id(5);
            giftName = gift.text(16);
            // Observed legacy/new wire variants. Counts are cumulative, never multiplied together.
            quantity = Math.max(body.count(4), Math.max(body.count(5), body.count(6)));
            combo = body.id(11);
            if (gift.has(12)) value = gift.count(12);
        }
        return new IncomingMessage(kind, messageId, resolvedRoom, userId, user.text(3),
                Math.multiplyExact(common.count(4), 1000), kind.equals("COMMENT") ? body.text(3) : "",
                giftId, giftName, quantity, combo, value, kind.equals("LIKE") ? body.count(2) : 0);
    }
}
