package cn.bigfish.speech;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Alibaba POP RPC v1 signature. Never log the canonical string, signed body or credentials. */
final class NlsRpcSignature {
    private NlsRpcSignature() {}
    static Map<String, String> parameters(String accessKeyId, Instant now, String nonce) {
        return Map.of("AccessKeyId", accessKeyId, "Action", "CreateToken", "Version", "2019-02-28",
            "RegionId", "cn-shanghai", "Format", "JSON", "SignatureMethod", "HMAC-SHA1",
            "SignatureVersion", "1.0", "SignatureNonce", nonce,
            "Timestamp", now.truncatedTo(ChronoUnit.SECONDS).toString());
    }
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }
    static String canonical(Map<String, String> parameters) {
        StringJoiner result = new StringJoiner("&");
        new TreeMap<>(parameters).forEach((key, value) -> {
            if (!key.equals("Signature")) result.add(encode(key) + "=" + encode(value));
        });
        return result.toString();
    }
    static String signature(String method, Map<String, String> parameters, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String toSign = method + "&" + encode("/") + "&" + encode(canonical(parameters));
            return Base64.getEncoder().encodeToString(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) { throw new SpeechFailure(SpeechFailure.Code.TOKEN_SIGNATURE_INVALID); }
    }
    static String postBody(String id, String secret, Instant now, String nonce) {
        var parameters = parameters(id, now, nonce);
        return canonical(parameters) + "&Signature=" + encode(signature("POST", parameters, secret));
    }
}
