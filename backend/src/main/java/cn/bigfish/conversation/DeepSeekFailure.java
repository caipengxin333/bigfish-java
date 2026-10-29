package cn.bigfish.conversation;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.ConnectException;
import java.io.IOException;
import java.util.concurrent.*;

/** No upstream message, URL, response body or cause is retained. Safe for integration diagnostics. */
public final class DeepSeekFailure extends IllegalStateException {
    public enum Code {
        SUCCESS, PROVIDER_DISABLED, BUSY, CLOSED, INVALID_REQUEST,
        AUTHENTICATION_FAILED, INSUFFICIENT_BALANCE, PERMISSION_DENIED, MODEL_NOT_FOUND,
        RATE_LIMITED, SERVER_ERROR, CONNECT_TIMEOUT, READ_TIMEOUT, OVERALL_TIMEOUT,
        NETWORK_ERROR, CANCELLED, INVALID_RESPONSE, INCOMPLETE_RESPONSE,
        OUTPUT_LIMIT, OUTPUT_TRUNCATED, CONTENT_FILTERED, CALLBACK_FAILED
    }
    public record Diagnostic(Code code, long elapsedMs, int attempts, int httpStatus) {}
    private final Diagnostic diagnostic;
    public DeepSeekFailure(Code code) { this(new Diagnostic(code,0,0,0)); }
    public DeepSeekFailure(Code code, int status) { this(new Diagnostic(code,0,0,status)); }
    public DeepSeekFailure(Diagnostic diagnostic) { super(diagnostic.code().name()); this.diagnostic = diagnostic; }
    public Diagnostic diagnostic() { return diagnostic; }
    static DeepSeekFailure sanitize(Throwable error) {
        for (int i=0; i<8 && error != null; i++, error=error.getCause()) {
            if (error instanceof DeepSeekFailure failure) return failure;
            if (error instanceof HttpConnectTimeoutException) return new DeepSeekFailure(Code.CONNECT_TIMEOUT);
            if (error instanceof HttpTimeoutException || error instanceof TimeoutException) return new DeepSeekFailure(Code.READ_TIMEOUT);
            if (error instanceof CancellationException) return new DeepSeekFailure(Code.CANCELLED);
            if (error instanceof org.springframework.core.codec.CodecException || error instanceof IllegalArgumentException
                || error instanceof com.fasterxml.jackson.core.JsonProcessingException
                || error instanceof org.springframework.core.io.buffer.DataBufferLimitException)
                return new DeepSeekFailure(Code.INVALID_RESPONSE);
            if (error instanceof IOException || error instanceof ConnectException) return new DeepSeekFailure(Code.NETWORK_ERROR);
        }
        return new DeepSeekFailure(Code.NETWORK_ERROR);
    }
    static boolean retryable(Throwable error) {
        Code code = sanitize(error).diagnostic.code();
        return code == Code.RATE_LIMITED || code == Code.SERVER_ERROR;
    }
}
