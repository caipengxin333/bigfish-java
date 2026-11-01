package cn.bigfish.collector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal/probe only. Fixed labels and numeric shapes, never values, exceptions or packet bytes. */
final class InboundDiagnostics {
    enum Stage { ASSEMBLY, QUEUE, PUSH, PAYLOAD, DECOMPRESS, RESPONSE, ACK_META, MESSAGE_LIST,
        ENVELOPE, BODY, COMMON, ROOM, USER, GIFT, EVENT, NORMALIZE }
    enum Reason { OK, SIZE_LIMIT, TTL, QUEUE_FULL, INVALID_TAG, TRUNCATED_VARINT, VARINT_OVERFLOW,
        LENGTH_OUT_OF_BOUNDS, UNSUPPORTED_WIRE, FIELD_LIMIT, WIRE_MISMATCH, INVALID_UTF8,
        INVALID_NUMBER, UNSUPPORTED_ENCODING, HEADER_CONFLICT, INVALID_GZIP, ROOM_MISMATCH,
        INVALID_EVENT, ACK_LOG_MISSING, UNKNOWN_TYPE, ACK_HEADER, ACK_RESPONSE, NO_ACK }
    enum Kind { NONE, COMMENT, GIFT, FOLLOW, ENTRANCE, LIKE, CONTROL, UNKNOWN }
    enum Encoding { NONE, PB, GZIP, IDENTITY, OTHER }
    record Field(int number, int wire, int length) { }
    record Shape(int inputBytes, int outputBytes, int fragments, int messages, Encoding encoding,
                 boolean gzip, List<Field> fields) {
        Shape { fields = List.copyOf(fields.stream().limit(12).toList()); }
        static Shape bytes(int length) { return new Shape(length, 0, 0, 0, Encoding.NONE, false, List.of()); }
    }
    record Key(Stage stage, Reason reason, Kind kind) { }
    record Sample(Key key, Shape shape) { }
    record Snapshot(Map<Key, Long> counts, List<Sample> samples, long omittedKeys) { }
    private final Map<Key, Long> counts = new LinkedHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private long omittedKeys;

    // Max 128 counter keys, 64 samples total and 3 per stage/reason (across all kinds).
    synchronized void record(Stage stage, Reason reason, Kind kind, Shape shape) {
        var key = new Key(stage, reason, kind);
        if (counts.containsKey(key) || counts.size() < 128) counts.merge(key, 1L, Long::sum);
        else omittedKeys++;
        boolean success = reason == Reason.OK || reason == Reason.ACK_HEADER || reason == Reason.ACK_RESPONSE || reason == Reason.NO_ACK;
        // Reserve half the sample budget for failures even after a long healthy receive.
        long successSamples = samples.stream().filter(s -> s.key.reason == Reason.OK || s.key.reason == Reason.ACK_HEADER
                || s.key.reason == Reason.ACK_RESPONSE || s.key.reason == Reason.NO_ACK).count();
        if (shape != null && samples.size() < 64 && (!success || successSamples < 32) && samples.stream().filter(s ->
                s.key.stage == stage && s.key.reason == reason).count() < 3) samples.add(new Sample(key, shape));
    }
    void record(Stage stage, Reason reason, Shape shape) { record(stage, reason, Kind.NONE, shape); }
    void failure(Stage stage, Kind kind, RuntimeException exception, int length) {
        Reason reason = Reason.INVALID_EVENT;
        List<Field> fields = List.of();
        if (exception instanceof ProtoWire.Fault fault) {
            reason = fault.reason;
            fields = List.of(new Field(fault.field, fault.wire, fault.length));
        }
        record(stage, reason, kind, new Shape(length, 0, 0, 0, Encoding.NONE, false, fields));
    }
    synchronized Snapshot snapshot() { return new Snapshot(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts)), List.copyOf(samples), omittedKeys); }
}
