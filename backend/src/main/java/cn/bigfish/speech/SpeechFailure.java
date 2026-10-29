package cn.bigfish.speech;

/** Safe, stable error codes only. Never attach provider bodies, credentials or raw transport causes. */
public final class SpeechFailure extends RuntimeException {
    public enum Code {
        DISABLED, MISSING_CREDENTIALS, TOKEN_EXPIRED, TOKEN_REJECTED, TOKEN_REFRESH_FAILED,
        TOKEN_REFRESH_TIMEOUT, CLOSED, INVALID_TASK, INVALID_TEXT, TEXT_TOO_LONG, UNSUPPORTED_VOICE,
        BUSY, COOLDOWN, DEADLINE_EXCEEDED, CONNECT_TIMEOUT, READ_TIMEOUT, TRANSPORT_FAILED,
        RATE_LIMITED, PROVIDER_UNAVAILABLE, INVALID_PARAMETERS, HTTP_REJECTED, INVALID_RESPONSE,
        INVALID_WAV, AUDIO_TOO_LARGE, AUDIO_TOO_LONG, AUDIO_SILENT, FILE_LIMIT, FILE_IO, CANCELLED
    }
    private final Code code;
    public SpeechFailure(Code code) { super("NLS_" + code.name()); this.code = code; }
    public Code code() { return code; }
}
