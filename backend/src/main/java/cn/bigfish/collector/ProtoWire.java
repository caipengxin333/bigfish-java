package cn.bigfish.collector;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static cn.bigfish.collector.InboundDiagnostics.Reason.*;

/** Original minimal wire reader, based on protobuf.dev's wire-format specification, not generated code. */
final class ProtoWire {
    record Value(int wire, long number, byte[] data) {
        byte[] bytes(int field) {
            if (wire != 2) throw fault(WIRE_MISMATCH, field, wire, data == null ? 0 : data.length);
            return data;
        }
        @Override public String toString() { return "Value[redacted]"; }
    }
    static final class Fault extends IllegalArgumentException {
        final InboundDiagnostics.Reason reason;
        final int field, wire, length;
        Fault(InboundDiagnostics.Reason reason, int field, int wire, int length) {
            super("Invalid protocol packet"); this.reason = reason; this.field = field; this.wire = wire; this.length = length;
        }
    }
    private int currentField, currentWire = -1;
    private final Map<Integer, List<Value>> fields = new HashMap<>();
    private final ByteBuffer input;

    private ProtoWire(byte[] bytes) {
        input = ByteBuffer.wrap(bytes);
        int count = 0;
        while (input.hasRemaining()) {
            if (++count > 4096) throw fault(FIELD_LIMIT, 0, -1, 0);
            currentField = 0; currentWire = -1;
            long tag = varint();
            if (tag <= 0 || tag > 0xffff_ffffL || (tag >>> 3) == 0) throw fault(INVALID_TAG, 0, -1, 0);
            int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            currentField = field; currentWire = wire;
            Value value;
            switch (wire) {
                case 0 -> value = new Value(wire, varint(), null);
                case 1 -> { skip(8); value = new Value(wire, 0, null); }
                case 2 -> {
                    long length = varint();
                    if (length < 0 || length > input.remaining()) throw fault(LENGTH_OUT_OF_BOUNDS, field, wire, length < 0 || length > Integer.MAX_VALUE ? -1 : (int) length);
                    byte[] payload = new byte[(int) length]; input.get(payload);
                    value = new Value(wire, 0, payload);
                }
                case 5 -> { skip(4); value = new Value(wire, 0, null); }
                default -> throw fault(UNSUPPORTED_WIRE, field, wire, 0); // Deprecated groups are not part of this supported subset.
            }
            fields.computeIfAbsent(field, unused -> new ArrayList<>()).add(value);
        }
    }

    static ProtoWire parse(byte[] bytes) { return new ProtoWire(bytes); }
    boolean has(int field) { return fields.containsKey(field); }
    private Value last(int field) {
        var values = fields.get(field); return values == null ? null : values.getLast();
    }
    byte[] bytes(int field) {
        Value value = last(field);
        if (value == null) return new byte[0];
        return value.bytes(field);
    }
    List<Value> repeated(int field, int maxCount) {
        var values = fields.getOrDefault(field, List.of());
        if (values.size() > maxCount) throw fault(FIELD_LIMIT, field, -1, 0);
        return List.copyOf(values);
    }
    List<byte[]> repeatedBytes(int field, int maxCount) {
        return repeated(field, maxCount).stream().map(value -> value.bytes(field)).toList();
    }
    List<InboundDiagnostics.Field> shape() {
        return fields.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream().map(value -> new InboundDiagnostics.Field(
                        entry.getKey(), value.wire, value.data == null ? 0 : value.data.length))).limit(12).toList();
    }
    ProtoWire nested(int field) { return parse(bytes(field)); }
    String text(int field) { return utf8(bytes(field), field); }
    long number(int field) {
        Value value = last(field);
        if (value == null) return 0;
        if (value.wire() != 0) throw fault(WIRE_MISMATCH, field, value.wire(), value.data == null ? 0 : value.data.length);
        return value.number();
    }
    String id(int field) {
        Value value = last(field);
        if (value == null) return "";
        if (value.wire() == 0) return value.number() == 0 ? "" : Long.toUnsignedString(value.number());
        String text = text(field);
        if (text.isEmpty() || text.equals("0")) return "";
        if (!text.matches("[0-9]{1,30}")) throw fault(INVALID_NUMBER, field, value.wire(), text.length());
        return text;
    }
    long count(int field) {
        Value value = last(field);
        if (value == null) return 0;
        long number;
        if (value.wire() == 0) number = value.number();
        else {
            String text = text(field);
            if (!text.matches("[0-9]{1,19}")) throw fault(INVALID_NUMBER, field, value.wire(), text.length());
            try { number = Long.parseLong(text); } catch (NumberFormatException exception) { throw fault(INVALID_NUMBER, field, value.wire(), text.length()); }
        }
        if (number < 0) throw fault(INVALID_NUMBER, field, value.wire(), 0);
        return number;
    }
    private long varint() {
        long result = 0;
        for (int index = 0; index < 10; index++) {
            if (!input.hasRemaining()) throw fault(TRUNCATED_VARINT, currentField, currentWire, 0);
            int value = Byte.toUnsignedInt(input.get());
            if (index == 9 && value > 1) throw fault(VARINT_OVERFLOW, currentField, currentWire, 0);
            result |= (long) (value & 127) << (index * 7);
            if ((value & 128) == 0) return result;
        }
        throw fault(VARINT_OVERFLOW, currentField, currentWire, 0);
    }
    private void skip(int count) {
        if (input.remaining() < count) throw fault(LENGTH_OUT_OF_BOUNDS, currentField, currentWire, count); input.position(input.position() + count);
    }
    private static String utf8(byte[] bytes, int field) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { throw fault(INVALID_UTF8, field, 2, bytes.length); }
    }
    static Fault invalid() { return fault(INVALID_EVENT, 0, -1, 0); }
    static Fault fault(InboundDiagnostics.Reason reason, int field, int wire, int length) {
        return new Fault(reason, field, wire, length);
    }

    static byte[] heartbeat() { var out = new ByteArrayOutputStream(); field(out, 7, "hb".getBytes(StandardCharsets.UTF_8)); return out.toByteArray(); }
    static byte[] ack(long logId, byte[] extension) {
        var out = new ByteArrayOutputStream(); writeVarint(out, 2L << 3); writeVarint(out, logId);
        field(out, 7, "ack".getBytes(StandardCharsets.UTF_8)); field(out, 8, extension);
        return out.toByteArray();
    }
    private static void field(ByteArrayOutputStream out, int number, byte[] bytes) {
        writeVarint(out, ((long) number << 3) | 2); writeVarint(out, bytes.length); out.writeBytes(bytes);
    }
    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~127L) != 0) { out.write((int) (value & 127) | 128); value >>>= 7; }
        out.write((int) value);
    }
}
