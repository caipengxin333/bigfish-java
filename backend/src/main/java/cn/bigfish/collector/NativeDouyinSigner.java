package cn.bigfish.collector;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/**
 * In-process native signer, no network or script execution. Platform acceptance needs live verification.
 * Protocol field mapping adapted from the same MIT upstream as NativeWebcastSignature;
 * its full notice is in that source and in the packaged META-INF/services descriptor.
 */
public final class NativeDouyinSigner implements DouyinSigner {
    public static final String SOURCE_REVISION = "af34f7c2ea2c4df90c6f4659e967fb31815d9255";
    private static final String SDK = "1.0.15";
    private static final URI PUSH = URI.create("wss://webcast100-ws-web-lf.douyin.com/webcast/im/push/v2/");
    private static final String[] SIGNED_FIELDS = {"live_id", "aid", "version_code", "webcast_sdk_version", "room_id",
            "sub_room_id", "sub_channel_id", "did_rule", "user_unique_id", "device_platform", "device_type", "ac", "identity"};
    private final NativeWebcastSignature generator;
    private final Clock clock;

    /** Public no-argument constructor for the explicit ServiceLoader entry. */
    public NativeDouyinSigner() { this(Clock.systemUTC(), new NativeWebcastSignature()); }
    NativeDouyinSigner(Clock clock, NativeWebcastSignature generator) { this.clock = clock; this.generator = generator; }

    @Override public Provenance provenance() {
        return new Provenance("https://github.com/jwwsjlm/douyinLive/blob/" + SOURCE_REVISION + "/internal/webcastsign/native.go",
                SOURCE_REVISION, "MIT");
    }

    @Override public SignedTarget sign(Request request) throws DouyinFailure, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        validate(request);
        var issuedAt = clock.instant();
        Map<String, String> values = parameters(request, issuedAt.toEpochMilli());
        values.put("signature", generator.sign(signingStub(values)));
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        String query = values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        // This is a local freshness budget, not a claim about a server-issued expiry timestamp.
        return new SignedTarget(URI.create(PUSH + "?" + query), request.nonce(), issuedAt, issuedAt.plusSeconds(30),
                DouyinConnection.Heartbeat.WEBSOCKET_PING);
    }

    private void validate(Request request) throws DouyinFailure {
        if (request == null || request.internalRoomId() == null || !request.internalRoomId().matches("[0-9]{1,30}")
                || request.userUniqueId() == null || !request.userUniqueId().matches("[0-9]{1,30}")
                || request.userAgent() == null || request.userAgent().isBlank() || request.userAgent().length() > 2048
                || request.userAgent().contains("\r") || request.userAgent().contains("\n")
                || request.nonce() == null || request.nonce().isBlank() || request.nonce().length() > 128) {
            throw new DouyinFailure(SIGNATURE_INVALID);
        }
        var now = clock.instant();
        if (request.requestedAt() == null || request.requestedAt().isAfter(now)
                || Duration.between(request.requestedAt(), now).compareTo(Duration.ofSeconds(10)) > 0) {
            throw new DouyinFailure(SIGNATURE_EXPIRED);
        }
    }

    private static Map<String, String> parameters(Request request, long now) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("app_name", "douyin_web"); fields.put("version_code", "180800");
        fields.put("webcast_sdk_version", SDK); fields.put("update_version_code", SDK);
        fields.put("compress", "gzip"); fields.put("device_platform", "web"); fields.put("cookie_enabled", "true");
        fields.put("browser_language", "zh-CN"); fields.put("browser_name", "Mozilla");
        fields.put("browser_version", request.userAgent().startsWith("Mozilla/") ? request.userAgent().substring(8) : request.userAgent());
        fields.put("browser_online", "true"); fields.put("tz_name", "Asia/Shanghai");
        // A new bootstrap request has no server-issued cursor. Never replay the upstream's historical sample cursor/wrds_v.
        fields.put("cursor", "");
        fields.put("internal_ext", "internal_src:dim|wss_push_room_id:" + request.internalRoomId()
                + "|wss_push_did:" + request.userUniqueId() + "|first_req_ms:" + now + "|fetch_time:" + now
                + "|seq:1|wss_info:0-" + now + "-0-0");
        fields.put("host", "https://live.douyin.com"); fields.put("aid", "6383"); fields.put("live_id", "1");
        fields.put("did_rule", "3"); fields.put("endpoint", "live_pc"); fields.put("support_wrds", "1");
        fields.put("user_unique_id", request.userUniqueId()); fields.put("im_path", "/webcast/im/fetch/");
        fields.put("identity", "audience"); fields.put("need_persist_msg_count", "15");
        fields.put("insert_task_id", ""); fields.put("live_reason", ""); fields.put("room_id", request.internalRoomId());
        fields.put("heartbeatDuration", "0");
        return fields;
    }

    /** Same ordered protocol fields as the MIT upstream; all values come from the actual target query. */
    static byte[] signingStub(Map<String, String> fields) {
        String canonical = java.util.Arrays.stream(SIGNED_FIELDS).map(key -> key + "=" + fields.getOrDefault(key, ""))
                .collect(Collectors.joining(","));
        return NativeWebcastSignature.md5(canonical.getBytes(StandardCharsets.UTF_8));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    @Override public String toString() { return "NativeDouyinSigner[MIT, offline-native, live-unverified]"; }
}
