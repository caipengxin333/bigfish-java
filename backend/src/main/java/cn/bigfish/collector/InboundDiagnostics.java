package cn.bigfish.collector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.util.function.BiConsumer;
import org.slf4j.LoggerFactory;

/** Collector-local telemetry. Fixed labels, validated method names and numeric shapes; no message data. */
final class InboundDiagnostics {
    enum Stage { ASSEMBLY, QUEUE, PUSH, PAYLOAD, DECOMPRESS, RESPONSE, ACK_META, MESSAGE_LIST,
        ENVELOPE, BODY, COMMON, ROOM, USER, GIFT, EVENT, NORMALIZE, METHOD, DEDUP, DELIVERY_QUEUE, DISPATCH, DIAGNOSTICS, ACK_SEND }
    enum Reason { OK, SIZE_LIMIT, TTL, QUEUE_FULL, INVALID_TAG, TRUNCATED_VARINT, VARINT_OVERFLOW,
        LENGTH_OUT_OF_BOUNDS, UNSUPPORTED_WIRE, FIELD_LIMIT, WIRE_MISMATCH, INVALID_UTF8,
        INVALID_NUMBER, UNSUPPORTED_ENCODING, HEADER_CONFLICT, INVALID_GZIP, ROOM_MISMATCH,
        INVALID_EVENT, ACK_LOG_MISSING, UNKNOWN_TYPE, ACK_HEADER, ACK_RESPONSE, NO_ACK, SEEN, MISSING_GIFT_ID,
        DUPLICATE_ID, DUPLICATE_COMBO, ENQUEUED, CANCELLED, SINK_RETURNED, SINK_FAILED, LOG_CAPACITY, ACK_SENT, ACK_SEND_FAILED, ACK_BLOCKED }
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
    record Snapshot(Map<Key, Long> counts, List<Sample> samples, long omittedKeys,
                    Map<String, Long> methods, Map<Key, Long> totals) { }
    private final Map<Key, Long> counts = new LinkedHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private long omittedKeys;

    // Max 128 counter keys, 64 samples total and 3 per stage/reason (across all kinds).
    synchronized void record(Stage stage, Reason reason, Kind kind, Shape shape) {
        var key = new Key(stage, reason, kind);
        if (counts.containsKey(key) || counts.size() < 128) counts.merge(key, 1L, Long::sum);
        else omittedKeys++;
        changes++;
        totals.merge(key, 1L, Long::sum); // Key space is finite enums; sampling never drops these counts.
        accumulateLog(key, shape);
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
    synchronized Snapshot snapshot() { return new Snapshot(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts)),
            List.copyOf(samples), omittedKeys, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(methods)),
            java.util.Collections.unmodifiableMap(new LinkedHashMap<>(totals))); }

    enum Credentials { GUEST, EXTERNAL, UNSPECIFIED }
    enum Level { DEBUG, INFO, WARN }
    record Trace(long generation, long frame, int message, String method) {
        Trace { method = safeMethod(method); }
        Trace message(int index) { return new Trace(generation, frame, index, "UNREAD"); }
        Trace method(String name) { return new Trace(generation, frame, message, name); }
    }
    final class Scope implements AutoCloseable {
        private final Trace previous;
        private Scope(Trace trace) { previous = local.get(); local.set(trace); }
        @Override public void close() { if (previous == null) local.remove(); else local.set(previous); }
    }
    private record LogKey(Key key, String method) { }
    private static final class Bucket {
        long total, printed, lastPrintMillis;
        Instant time;
        Trace trace;
        Shape shape;
        Instant firstTime;
        Trace firstTrace;
        Shape firstShape;
    }
    private static final String[] SUPPORTED = {"WebcastChatMessage", "WebcastGiftMessage", "WebcastMemberMessage",
            "WebcastSocialMessage", "WebcastLikeMessage", "WebcastControlMessage"};
    private static final java.util.Set<String> STATISTICS = java.util.Set.of("WebcastRoomUserSeqMessage",
            "WebcastRoomStatsMessage", "WebcastRoomRankMessage", "WebcastRoomDataSyncMessage");
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(InboundDiagnostics.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Map<Key, Long> totals = new LinkedHashMap<>();
    private final Map<String, Long> methods = new LinkedHashMap<>();
    private final Map<LogKey, Bucket> logs = new LinkedHashMap<>();
    private final ThreadLocal<Trace> local = new ThreadLocal<>();
    private volatile Trace connection = new Trace(0, 0, 0, "FRAME");
    private long frames, lastSummaryMillis, changes, summaryChanges = -1;
    private String session = "UNBOUND";
    private Clock logClock = Clock.systemUTC();
    private Credentials credentials = Credentials.UNSPECIFIED;
    private boolean enabled;
    private final BiConsumer<Level, String> writer;

    InboundDiagnostics() { this((level, line) -> {
        switch (level) {
            case DEBUG -> LOG.debug("collector_inbound {}", line);
            case INFO -> LOG.info("collector_inbound {}", line);
            case WARN -> LOG.warn("collector_inbound {}", line);
        }
    }); }
    InboundDiagnostics(BiConsumer<Level, String> writer) {
        this.writer = writer;
        for (String method : SUPPORTED) methods.put(method, 0L);
        methods.put("OTHER_METHOD", 0L); methods.put("INVALID_METHOD", 0L);
    }

    synchronized void begin(String id, Clock clock, boolean enable) {
        // Backend IDs are UUIDs. Never echo arbitrary caller-provided session text.
        session = id != null && id.matches("(?:probe-)?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                ? id : "redacted-" + java.util.UUID.randomUUID();
        local.remove();
        logClock = clock; enabled = enable; frames = 0; lastSummaryMillis = clock.millis();
        connection = new Trace(0, 0, 0, "FRAME");
        changes = 0; summaryChanges = -1;
        counts.clear(); samples.clear(); totals.clear(); methods.clear(); logs.clear(); omittedKeys = 0;
        for (String method : SUPPORTED) methods.put(method, 0L);
        methods.put("OTHER_METHOD", 0L); methods.put("INVALID_METHOD", 0L);
    }
    synchronized void credentials(Credentials value) { credentials = value; }
    synchronized void nextConnection() { connection = new Trace(connection.generation + 1, 0, 0, "FRAME"); frames = 0; }
    synchronized Trace nextFrame() { return new Trace(connection.generation, ++frames, 0, "FRAME"); }
    Trace trace() { Trace trace = local.get(); return trace == null ? connection : trace; }
    Scope use(Trace trace) { return new Scope(trace); }

    synchronized void methodSeen(String raw) {
        String name = safeMethod(raw);
        // Reserve known methods even if unknown method cardinality reaches its cap.
        if (!methods.containsKey(name) && methods.size() >= 64) name = "OTHER_METHOD";
        methods.merge(name, 1L, Long::sum);
        local.set(trace().method(name));
        record(Stage.METHOD, Reason.SEEN, kind(name), null);
    }
    static String safeMethod(String raw) {
        if (raw == null) return "INVALID_METHOD";
        if (java.util.Set.of("FRAME", "UNREAD", "NONE", "OTHER_METHOD", "INVALID_METHOD").contains(raw)) return raw;
        if (raw.length() <= 80 && raw.matches("Webcast[A-Za-z]{1,66}Message")
                && !raw.toLowerCase(java.util.Locale.ROOT).matches(".*(cookie|token|signature|authorization).*")) return raw;
        return "INVALID_METHOD";
    }
    static Kind kind(String method) {
        return switch (method) {
            case "WebcastChatMessage" -> Kind.COMMENT;
            case "WebcastGiftMessage" -> Kind.GIFT;
            case "WebcastMemberMessage" -> Kind.ENTRANCE;
            case "WebcastSocialMessage" -> Kind.FOLLOW;
            case "WebcastLikeMessage" -> Kind.LIKE;
            case "WebcastControlMessage" -> Kind.CONTROL;
            default -> Kind.UNKNOWN;
        };
    }
    static Kind eventKind(String kind) {
        try { return Kind.valueOf(kind); } catch (IllegalArgumentException exception) { return Kind.UNKNOWN; }
    }
    private static boolean failure(Reason reason) {
        return switch (reason) {
            case OK, SEEN, UNKNOWN_TYPE, ACK_HEADER, ACK_RESPONSE, NO_ACK, ENQUEUED,
                    CANCELLED, SINK_RETURNED, ACK_SENT, DUPLICATE_ID, DUPLICATE_COMBO -> false;
            default -> true;
        };
    }
    private static boolean noteworthy(Key key) {
        return failure(key.reason) || key.reason == Reason.UNKNOWN_TYPE || key.stage == Stage.METHOD
                || key.stage == Stage.DEDUP || key.stage == Stage.DELIVERY_QUEUE || key.stage == Stage.DISPATCH
                || key.stage == Stage.NORMALIZE || (key.stage == Stage.EVENT && key.kind == Kind.GIFT);
    }
    private void accumulateLog(Key key, Shape shape) {
        if (!enabled || !noteworthy(key)) return;
        var trace = trace();
        var logKey = new LogKey(key, trace.method);
        if (!logs.containsKey(logKey) && logs.size() >= 255)
            logKey = new LogKey(new Key(Stage.DIAGNOSTICS, Reason.LOG_CAPACITY, Kind.NONE), "OTHER_METHOD");
        var bucket = logs.computeIfAbsent(logKey, ignored -> new Bucket());
        bucket.total++; bucket.time = logClock.instant(); bucket.trace = trace; bucket.shape = shape;
        if (bucket.total == 1) { bucket.firstTime = bucket.time; bucket.firstTrace = trace; bucket.firstShape = shape; }
    }

    // Called by collector workers, never by a JDK WebSocket callback. No new threads or raw event queue.
    void flushLogs(boolean force) {
        List<Map.Entry<Level, String>> lines = new ArrayList<>();
        synchronized (this) {
            if (!enabled) return;
            long now = logClock.millis();
            for (var entry : logs.entrySet()) {
                var key = entry.getKey(); var b = entry.getValue();
                if (b.total == b.printed || (!force && b.printed > 0 && now - b.lastPrintMillis < 10000)) continue;
                boolean first = b.printed == 0;
                var fields = base(first ? b.firstTime : b.time, first ? b.firstTrace : b.trace);
                Shape sampled = first ? b.firstShape : b.shape;
                fields.put("sample", first ? "FIRST_OCCURRENCE" : "LATEST_OCCURRENCE");
                fields.put("stage", key.key.stage.name()); fields.put("type", key.key.kind.name());
                fields.put("code", key.key.reason.name()); fields.put("total", b.total);
                fields.put("delta", b.total - b.printed); fields.put("summary", b.printed > 0);
                fields.put("countScope", "SESSION");
                Field field = sampled == null || sampled.fields.isEmpty() ? new Field(0, -1, 0) : sampled.fields.getFirst();
                fields.put("field", field.number); fields.put("wire", field.wire); fields.put("fieldLength", field.length);
                fields.put("dataLength", sampled == null ? 0 : sampled.inputBytes);
                Level level = failure(key.key.reason) ? Level.WARN : STATISTICS.contains(key.method) ? Level.DEBUG : Level.INFO;
                lines.add(Map.entry(level, json(fields)));
                b.printed = b.total; b.lastPrintMillis = now;
            }
            if ((force && changes != summaryChanges) || (!force && now - lastSummaryMillis >= 30000)) {
                var fields = base(logClock.instant(), connection);
                fields.put("stage", "WINDOW"); fields.put("code", "COUNTS"); fields.put("methods", new LinkedHashMap<>(methods));
                fields.put("giftMethod", methods.getOrDefault("WebcastGiftMessage", 0L));
                fields.put("giftParsed", total(Stage.EVENT, Reason.OK, Kind.GIFT));
                fields.put("giftParseFailed", totals.entrySet().stream().filter(e -> e.getKey().kind == Kind.GIFT
                        && e.getKey().stage != Stage.NORMALIZE && e.getKey().stage != Stage.DELIVERY_QUEUE
                        && e.getKey().stage != Stage.DISPATCH && failure(e.getKey().reason)).mapToLong(Map.Entry::getValue).sum());
                fields.put("giftNormalizeFailed", totals.entrySet().stream().filter(e -> e.getKey().stage == Stage.NORMALIZE
                        && e.getKey().kind == Kind.GIFT && failure(e.getKey().reason)).mapToLong(Map.Entry::getValue).sum());
                fields.put("giftDuplicates", total(Stage.DEDUP, Reason.DUPLICATE_ID, Kind.GIFT) + total(Stage.DEDUP, Reason.DUPLICATE_COMBO, Kind.GIFT));
                fields.put("giftQueueDropped", totals.entrySet().stream().filter(e -> e.getKey().stage == Stage.DELIVERY_QUEUE
                        && e.getKey().kind == Kind.GIFT && e.getKey().reason != Reason.ENQUEUED).mapToLong(Map.Entry::getValue).sum());
                fields.put("giftSinkReturned", total(Stage.DISPATCH, Reason.SINK_RETURNED, Kind.GIFT));
                fields.put("giftSinkFailed", total(Stage.DISPATCH, Reason.SINK_FAILED, Kind.GIFT));
                fields.put("unattributedFailures", totals.entrySet().stream().filter(e -> (e.getKey().kind == Kind.NONE || e.getKey().kind == Kind.UNKNOWN)
                        && failure(e.getKey().reason)).mapToLong(Map.Entry::getValue).sum());
                fields.put("countScope", "SESSION"); fields.put("final", force);
                lines.add(Map.entry(Level.INFO, json(fields))); lastSummaryMillis = now; summaryChanges = changes;
            }
        }
        for (var line : lines) {
            try { writer.accept(line.getKey(), line.getValue()); }
            catch (RuntimeException ignored) { /* Logging must never break collection or expose a cause. */ }
        }
    }
    private long total(Stage stage, Reason reason, Kind kind) { return totals.getOrDefault(new Key(stage, reason, kind), 0L); }
    private Map<String, Object> base(Instant time, Trace trace) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("time", time.toString()); fields.put("sessionId", session); fields.put("credentials", credentials.name());
        fields.put("generation", trace.generation); fields.put("frame", trace.frame);
        fields.put("message", trace.message); fields.put("method", trace.method);
        return fields;
    }
    private static String json(Map<String, Object> fields) {
        try { return JSON.writeValueAsString(fields); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { return "{\"code\":\"DIAGNOSTIC_SERIALIZATION_FAILED\"}"; }
    }
}
