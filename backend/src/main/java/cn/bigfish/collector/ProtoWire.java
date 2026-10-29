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

/** Original minimal wire reader, based on protobuf.dev's wire-format specification, not generated code. */
final class ProtoWire {
    private record Value(int wire, long number, byte[] data) { }
    private final Map<Integer, List<Value>> fields = new HashMap<>();
    private final ByteBuffer input;

    private ProtoWire(byte[] bytes) {
        input = ByteBuffer.wrap(bytes);
        int count = 0;
        while (input.hasRemaining()) {
            if (++count > 4096) throw invalid();
            long tag = varint();
            if (tag <= 0 || tag > 0xffff_ffffL || (tag >>> 3) == 0) throw invalid();
            int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            Value value;
            switch (wire) {
                case 0 -> value = new Value(wire, varint(), null);
                case 1 -> { skip(8); value = new Value(wire, 0, null); }
                case 2 -> {
                    long length = varint();
                    if (length < 0 || length > input.remaining()) throw invalid();
                    byte[] payload = new byte[(int) length]; input.get(payload);
                    value = new Value(wire, 0, payload);
                }
                case 5 -> { skip(4); value = new Value(wire, 0, null); }
                default -> throw invalid(); // Deprecated groups are not part of this supported subset.
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
        if (value.wire() != 2) throw invalid();
        return value.data();
    }
    List<byte[]> repeatedBytes(int field, int maxCount) {
        var values = fields.getOrDefault(field, List.of());
        if (values.size() > maxCount) throw invalid();
        return values.stream().map(value -> {
            if (value.wire() != 2) throw invalid(); return value.data();
        }).toList();
    }
    ProtoWire nested(int field) { return parse(bytes(field)); }
    String text(int field) { return utf8(bytes(field)); }
    long number(int field) {
        Value value = last(field);
        if (value == null) return 0;
        if (value.wire() != 0) throw invalid();
        return value.number();
    }
    String id(int field) {
        Value value = last(field);
        if (value == null) return "";
        if (value.wire() == 0) return value.number() == 0 ? "" : Long.toUnsignedString(value.number());
        String text = text(field);
        if (text.isEmpty() || text.equals("0")) return "";
        if (!text.matches("[0-9]{1,30}")) throw invalid();
        return text;
    }
    long count(int field) {
        Value value = last(field);
        if (value == null) return 0;
        long number;
        if (value.wire() == 0) number = value.number();
        else {
            String text = text(field);
            if (!text.matches("[0-9]{1,19}")) throw invalid();
            try { number = Long.parseLong(text); } catch (NumberFormatException exception) { throw invalid(); }
        }
        if (number < 0) throw invalid();
        return number;
    }
    private long varint() {
        long result = 0;
        for (int index = 0; index < 10; index++) {
            if (!input.hasRemaining()) throw invalid();
            int value = Byte.toUnsignedInt(input.get());
            if (index == 9 && value > 1) throw invalid();
            result |= (long) (value & 127) << (index * 7);
            if ((value & 128) == 0) return result;
        }
        throw invalid();
    }
    private void skip(int count) {
        if (input.remaining() < count) throw invalid(); input.position(input.position() + count);
    }
    private static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { throw invalid(); }
    }
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid protocol packet"); }

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
