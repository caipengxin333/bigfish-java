package cn.bigfish.collector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import static cn.bigfish.collector.CollectorDiagnostics.Counter.*;
import cn.bigfish.collector.InboundDiagnostics.Stage;
import cn.bigfish.collector.InboundDiagnostics.Reason;
import cn.bigfish.collector.InboundDiagnostics.Kind;
import cn.bigfish.collector.InboundDiagnostics.Encoding;
import cn.bigfish.collector.InboundDiagnostics.Shape;

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
        Stage stage = Stage.PUSH;
        int length = bytes.length;
        var trace = diagnostics.inbound();
        try {
            if (bytes.length > maxBytes) throw ProtoWire.fault(Reason.SIZE_LIMIT, 0, -1, bytes.length);
            var push = ProtoWire.parse(bytes);
            trace.record(stage, Reason.OK, new Shape(length, 0, 0, 0, Encoding.NONE, false, push.shape()));
            stage = Stage.PAYLOAD;
            String type = push.text(7);
            if (type.equals("hb") || type.equals("ack")) return new Batch(null, List.of());
            if (!type.isEmpty() && !type.equals("msg")) {
                trace.record(stage, Reason.UNKNOWN_TYPE, null);
                diagnostics.increment(UNKNOWN); return new Batch(null, List.of());
            }
            byte[] payload = push.bytes(8);
            String encoding = push.text(6);
            Encoding label = switch (encoding) {
                case "" -> Encoding.NONE;
                case "pb" -> Encoding.PB;
                case "gzip" -> Encoding.GZIP;
                case "identity" -> Encoding.IDENTITY;
                default -> Encoding.OTHER;
            };
            HeaderInfo headers = headers(push);
            boolean magic = payload.length >= 2 && payload[0] == (byte) 0x1f && payload[1] == (byte) 0x8b;
            if (label == Encoding.OTHER) throw ProtoWire.fault(Reason.UNSUPPORTED_ENCODING, 6, 2, encoding.length());
            trace.record(stage, Reason.OK, new Shape(bytes.length, payload.length, 0, 0, label, magic, push.shape()));
            // payload_encoding describes serialization (pb); compress_type describes compression.
            // Legacy payload_encoding=gzip and unmarked gzip remain supported under the same byte cap.
            if (headers.compression.equals("identity") && (magic || label == Encoding.GZIP))
                throw ProtoWire.fault(Reason.HEADER_CONFLICT, 5, 2, 0);
            boolean gzip = magic || label == Encoding.GZIP || headers.compression.equals("gzip");
            stage = Stage.DECOMPRESS;
            length = payload.length;
            if (gzip) {
                try (var stream = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    payload = stream.readNBytes(maxBytes + 1);
                    if (payload.length > maxBytes) throw ProtoWire.fault(Reason.SIZE_LIMIT, 8, 2, payload.length);
                }
            }
            trace.record(stage, Reason.OK, new Shape(length, payload.length, 0, 0, label, gzip, List.of()));
            stage = Stage.RESPONSE;
            length = payload.length;
            var response = ProtoWire.parse(payload);
            trace.record(stage, Reason.OK, new Shape(length, 0, 0, 0, label, gzip, response.shape()));
            stage = Stage.ACK_META;
            byte[] ack = null;
            boolean needAck = response.number(9) != 0;
            byte[] extension = headers.ackExtension;
            Reason ackSource = Reason.ACK_HEADER;
            if (extension.length == 0 && needAck) {
                extension = response.bytes(5); ackSource = Reason.ACK_RESPONSE;
            }
            if (extension.length != 0 || needAck) {
                if (extension.length > 8192) throw ProtoWire.fault(Reason.SIZE_LIMIT, 5, 2, extension.length);
                if (!push.has(2)) throw ProtoWire.fault(Reason.ACK_LOG_MISSING, 2, -1, 0);
                ack = ProtoWire.ack(push.number(2), extension);
            } else ackSource = Reason.NO_ACK;
            trace.record(stage, ackSource, Shape.bytes(extension.length));
            stage = Stage.MESSAGE_LIST;
            var entries = response.repeated(1, 512);
            trace.record(stage, Reason.OK, new Shape(length, 0, 0, entries.size(), label, gzip, response.shape()));
            var messages = new ArrayList<IncomingMessage>();
            for (var entry : entries) {
                byte[] message;
                try { message = entry.bytes(1); }
                catch (RuntimeException exception) {
                    trace.failure(stage, Kind.NONE, exception, 0); diagnostics.error(MALFORMED, 0); continue;
                }
                IncomingMessage decoded = message(message, resolvedRoom);
                if (decoded != null) messages.add(decoded);
            }
            return new Batch(ack, List.copyOf(messages));
        } catch (IOException exception) {
            trace.record(stage, Reason.INVALID_GZIP, Shape.bytes(length)); throw exception;
        } catch (RuntimeException exception) {
            // Only fixed fault metadata is retained. The exception text/cause is never rendered.
            trace.failure(stage, Kind.NONE, exception, length);
            throw exception;
        }
    }

    private record HeaderInfo(String compression, byte[] ackExtension) {
        @Override public String toString() { return "HeaderInfo[redacted]"; }
    }
    private HeaderInfo headers(ProtoWire push) {
        String compression = "";
        byte[] extension = new byte[0];
        boolean hasExtension = false;
        int total = 0;
        for (byte[] bytes : push.repeatedBytes(5, 64)) {
            total += bytes.length;
            if (total > 65536) throw ProtoWire.fault(Reason.SIZE_LIMIT, 5, 2, total);
            var header = ProtoWire.parse(bytes);
            String key = header.text(1);
            if (key.equals("compress_type")) {
                String value = header.text(2);
                if (!value.equals("gzip") && !value.equals("identity"))
                    throw ProtoWire.fault(Reason.UNSUPPORTED_ENCODING, 5, 2, value.length());
                if (!compression.isEmpty() && !compression.equals(value))
                    throw ProtoWire.fault(Reason.HEADER_CONFLICT, 5, 2, 0);
                compression = value;
            } else if (key.equals("im-internal_ext")) {
                byte[] value = header.bytes(2);
                if (value.length > 8192) throw ProtoWire.fault(Reason.SIZE_LIMIT, 5, 2, value.length);
                if (hasExtension && !java.util.Arrays.equals(extension, value))
                    throw ProtoWire.fault(Reason.HEADER_CONFLICT, 5, 2, 0);
                extension = value; hasExtension = true;
            }
        }
        return new HeaderInfo(compression, extension);
    }

    private IncomingMessage message(byte[] bytes, String resolvedRoom) {
        Stage stage = Stage.ENVELOPE;
        Kind category = Kind.NONE;
        try {
            var envelope = ProtoWire.parse(bytes);
            diagnostics.inbound().record(stage, Reason.OK, Shape.bytes(bytes.length));
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
            category = Kind.valueOf(kind);
            if (kind.equals("UNKNOWN")) {
                diagnostics.inbound().record(stage, Reason.UNKNOWN_TYPE, category, null);
                diagnostics.increment(UNKNOWN); return null;
            }
            stage = Stage.BODY;
            var body = envelope.nested(2);
            diagnostics.inbound().record(stage, Reason.OK, category, new Shape(bytes.length, 0, 0, 0, Encoding.NONE, false, body.shape()));
            stage = Stage.COMMON;
            var common = body.nested(1);
            String room = common.id(3);
            diagnostics.inbound().record(stage, Reason.OK, category, null);
            stage = Stage.ROOM;
            if (!room.isEmpty() && !room.equals(resolvedRoom)) throw ProtoWire.fault(Reason.ROOM_MISMATCH, 3, -1, 0);
            diagnostics.inbound().record(stage, Reason.OK, category, null);
            stage = Stage.EVENT;
            if (kind.equals("CONTROL")) {
                if (body.number(2) != 3) return null;
                kind = "ENDED";
            }
            if (kind.equals("FOLLOW") && body.number(4) != 1) { diagnostics.increment(UNKNOWN); return null; }
            stage = Stage.USER;
            var user = body.nested(kind.equals("GIFT") ? 7 : kind.equals("LIKE") ? 5 : kind.equals("ENDED") ? 0 : 2);
            String userId = user.id(1);
            if (userId.isEmpty()) userId = user.id(1028);
            String messageId = common.id(2);
            if (messageId.isEmpty()) messageId = envelope.id(3);
            diagnostics.inbound().record(stage, Reason.OK, category, null);
            stage = Stage.GIFT;
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
            stage = Stage.EVENT;
            var result = new IncomingMessage(kind, messageId, resolvedRoom, userId, user.text(3),
                    Math.multiplyExact(common.count(4), 1000), kind.equals("COMMENT") ? body.text(3) : "",
                    giftId, giftName, quantity, combo, value, kind.equals("LIKE") ? body.count(2) : 0);
            diagnostics.inbound().record(stage, Reason.OK, category, null);
            return result;
        } catch (RuntimeException exception) {
            diagnostics.inbound().failure(stage, category, exception, bytes.length);
            diagnostics.error(MALFORMED, 0);
            return null;
        }
    }
}
