package cn.bigfish.collector;

/** Safe diagnostics: no upstream message, URL, response body or exception cause is retained. */
public final class DouyinFailure extends Exception {
    public enum Code {
        UNSUPPORTED_ROOM_URL(false), CREDENTIALS_MISSING(false), CREDENTIALS_EXPIRED(false),
        CREDENTIALS_INVALID(false), AUTH_REQUIRED(false), CHALLENGE_REQUIRED(false),
        REGION_RESTRICTED(false), PAGE_STRUCTURE_CHANGED(false), REDIRECT_REJECTED(false),
        RESPONSE_TOO_LARGE(false), HTTP_TIMEOUT(true), HTTP_UNAVAILABLE(true), RATE_LIMITED(false),
        SIGNER_UNAVAILABLE(false), SIGNATURE_EXPIRED(false), SIGNATURE_INVALID(false),
        SIGNER_FAILED(false), SIGNER_TIMEOUT(false), HANDSHAKE_REJECTED(false), PROBE_DISABLED(false), ROOM_REQUIRED(false),
        PROBE_CONFIGURATION_INVALID(false), ATTEMPT_LIMIT(false);

        private final boolean retryable;
        Code(boolean retryable) { this.retryable = retryable; }
        public boolean retryable() { return retryable; }
    }
    private final Code code;
    public DouyinFailure(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
