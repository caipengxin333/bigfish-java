package cn.bigfish.collector;

import java.net.URI;
import java.time.Instant;

/**
 * Signing boundary. The bundled native implementation is MIT-derived and needs separate online verification.
 * Implementations must honor interruption, bound execution to ten seconds, and never log inputs/results.
 * Cookie is intentionally absent: signing code must not forward the user's login to a third party.
 */
public interface DouyinSigner {
    Provenance provenance();
    SignedTarget sign(Request request) throws Exception;

    record Provenance(String source, String revision, String license) {
        public boolean isDeclared() {
            return source != null && source.startsWith("https://") && source.length() <= 2048
                    && revision != null && revision.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
                    && license != null && !license.isBlank() && license.length() <= 128;
        }
        @Override public String toString() { return "Provenance[redacted]"; }
    }
    record Request(String internalRoomId, String userUniqueId, String userAgent, String nonce, Instant requestedAt) {
        @Override public String toString() { return "SigningRequest[redacted]"; }
    }
    record SignedTarget(URI endpoint, String nonce, Instant issuedAt, Instant expiresAt, DouyinConnection.Heartbeat heartbeat) {
        @Override public String toString() { return "SignedTarget[redacted]"; }
    }
}
