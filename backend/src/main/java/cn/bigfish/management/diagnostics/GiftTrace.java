package cn.bigfish.management.diagnostics;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Local log schema only: no REST fields, upstream IDs, payloads, rendered text or exception objects. */
public record GiftTrace(Instant at, String sessionId, String taskId, String phase, String reason,
                        String relatedTaskId, long configVersion, long quantity, long stageMs, long taskMs) {
    public enum Phase { VALIDATED, DEDUP_PASSED, MERGE_CHECKED, MERGED_INTO, TEMPLATE_QUEUED,
        TEMPLATE_RENDERED, SYNTHESIS_QUEUED, INGRESS_REJECTED }
    public enum Reason { NONE, ROOM_MISMATCH, SESSION_NOT_RUNNING, SESSION_MISMATCH,
        NO_MERGE_CANDIDATE, MERGE_QUANTITY_LIMIT, MISSING_USER_ID, GIFT_MERGED }
    private static final Set<String> PHASES=names(Phase.values(),RuntimeDiagnostics.Stage.values());
    private static final Set<String> REASONS=names(Reason.values(),RuntimeDiagnostics.Reason.values());
    public GiftTrace {
        Objects.requireNonNull(at);
        if (!PHASES.contains(phase) || !REASONS.contains(reason)) throw new IllegalArgumentException("INVALID_GIFT_TRACE_CODE");
        sessionId=safeId(sessionId); taskId=safeId(taskId); relatedTaskId=safeId(relatedTaskId);
        configVersion=Math.max(0,configVersion); quantity=Math.max(0,Math.min(1_000_000,quantity));
        stageMs=Math.max(0,stageMs); taskMs=Math.max(0,taskMs);
    }
    public static GiftTrace lifecycle(RuntimeDiagnostics.Trace trace) {
        return new GiftTrace(trace.at(),trace.sessionId(),trace.taskId(),trace.stage().name(),trace.reason().name(),"",
            trace.configVersion(),0,trace.previousStageMs(),trace.taskElapsedMs());
    }
    public static GiftTrace business(Instant at,String session,String task,Phase phase,Reason reason,String target,long version,long quantity) {
        return new GiftTrace(at,session,task,phase.name(),reason.name(),target,version,quantity,0,0);
    }
    private static Set<String> names(Enum<?>[] left,Enum<?>[] right) {
        return Set.copyOf(Stream.concat(Arrays.stream(left),Arrays.stream(right)).map(Enum::name).toList());
    }
    private static String safeId(String id) {
        return id!=null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            ? id.toLowerCase(Locale.ROOT) : "";
    }
    /** All strings above are enums, UUIDs or ISO instants; no user-controlled JSON escaping surface. */
    public String json(long sequence) {
        return "{\"sequence\":"+sequence+",\"at\":\""+at+"\",\"sessionId\":\""+sessionId+"\",\"taskId\":\""+taskId
            +"\",\"eventType\":\"GIFT\",\"phase\":\""+phase+"\",\"reason\":\""+reason+"\",\"relatedTaskId\":\""+relatedTaskId
            +"\",\"configVersion\":"+configVersion+",\"quantity\":"+quantity+",\"stageMs\":"+stageMs+",\"taskMs\":"+taskMs+"}";
    }
}
