package cn.bigfish.collector;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/** Fresh credentials, page and signing request for each resolve; retains no signed URL or cookie. */
public final class WebDouyinConnectionProvider implements DouyinConnectionProvider {
    @FunctionalInterface public interface CredentialSource { DouyinCredentials current() throws DouyinFailure; }
    private final CredentialSource credentials;
    private final DouyinRoomResolver resolver;
    private final DouyinSigner signer;
    private final Clock clock;
    private final Duration signingTimeout;
    private final DouyinGuestSession guest;
    private volatile Thread signingWorker;
    private volatile InboundDiagnostics.CredentialEvidence credentialEvidence = InboundDiagnostics.CredentialEvidence.NONE;
    private final java.util.concurrent.atomic.AtomicLong signingAttempts = new java.util.concurrent.atomic.AtomicLong();

    public WebDouyinConnectionProvider(CredentialSource credentials, DouyinSigner signer) {
        this(credentials, signer, new DouyinRoomResolver(), Clock.systemUTC());
    }
    /** Ready to resolve from just a canonical room link/number; no credential environment is required. */
    public static WebDouyinConnectionProvider guest() { return guest(new NativeDouyinSigner()); }
    public static WebDouyinConnectionProvider guest(DouyinSigner signer) {
        return guest(signer, new DouyinRoomResolver(), Clock.systemUTC());
    }
    static WebDouyinConnectionProvider guest(DouyinSigner signer, DouyinRoomResolver resolver, Clock clock) {
        return new WebDouyinConnectionProvider(signer, resolver, clock);
    }
    private WebDouyinConnectionProvider(DouyinSigner signer, DouyinRoomResolver resolver, Clock clock) {
        this.credentials = null; this.signer = signer; this.resolver = resolver; this.clock = clock;
        this.signingTimeout = Duration.ofSeconds(10); this.guest = new DouyinGuestSession(resolver, clock);
    }
    WebDouyinConnectionProvider(CredentialSource credentials, DouyinSigner signer,
                                DouyinRoomResolver resolver, Clock clock) {
        this(credentials, signer, resolver, clock, Duration.ofSeconds(10));
    }
    WebDouyinConnectionProvider(CredentialSource credentials, DouyinSigner signer,
                                DouyinRoomResolver resolver, Clock clock, Duration signingTimeout) {
        this.credentials = java.util.Objects.requireNonNull(credentials); this.signer = signer;
        this.guest = null;
        this.resolver = java.util.Objects.requireNonNull(resolver); this.clock = clock;
        this.signingTimeout = signingTimeout;
    }

    @Override public DouyinConnection resolve(String input) throws Exception {
        return resolve(input, null);
    }

    @Override public DouyinConnection resolve(String input, String expectedInternalRoomId) throws Exception {
        credentialEvidence = InboundDiagnostics.CredentialEvidence.NONE;
        DouyinRoomInput requested = DouyinRoomInput.parse(input);
        DouyinCredentials current;
        DouyinRoom room;
        if (guest != null) {
            requireSigner(signer);
            var access = guest.resolve(requested.webRoomId(), expectedInternalRoomId);
            current = access.credentials(); room = access.room();
            if (current != null) credentialEvidence = new InboundDiagnostics.CredentialEvidence(true, false, InboundDiagnostics.VisitorSource.PAGE);
        } else {
            try { current = credentials.current(); }
            catch (RuntimeException exception) { throw new DouyinFailure(CREDENTIALS_INVALID); }
            if (current == null) throw new DouyinFailure(CREDENTIALS_MISSING);
            current.validateForResolution(clock.instant());
            credentialEvidence = new InboundDiagnostics.CredentialEvidence(true, current.hasLoginFields(), InboundDiagnostics.VisitorSource.UNRESOLVED);
            // Do not touch the platform when receive cannot proceed. Room-only validation uses resolver directly.
            requireSigner(signer);
            var access = resolver.resolveExternal(requested.webRoomId(), current, expectedInternalRoomId);
            room = access.room(); current = access.credentials();
            credentialEvidence = new InboundDiagnostics.CredentialEvidence(true, credentialEvidence.loginFieldsPresent(), access.visitorSource());
        }
        if (expectedInternalRoomId != null && !expectedInternalRoomId.equals(room.internalRoomId())) {
            return DouyinConnection.ended(room.internalRoomId());
        }
        if (!room.live()) return DouyinConnection.ended(room.internalRoomId());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        var request = new DouyinSigner.Request(room.internalRoomId(), current.userUniqueId(), current.userAgent(),
                UUID.randomUUID().toString(), clock.instant());
        DouyinSigner.SignedTarget signed = sign(request);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        current.validate(clock.instant());
        validateSigned(signed, request);
        try {
            return new DouyinConnection(room.internalRoomId(), signed.endpoint(), current.headers(requested), signed.heartbeat());
        } catch (IllegalArgumentException exception) { throw new DouyinFailure(SIGNATURE_INVALID); }
    }
    private DouyinSigner.SignedTarget sign(DouyinSigner.Request request) throws DouyinFailure, InterruptedException {
        // No executor queue: at most one signing worker, including a plugin which ignores cancellation.
        var task = new FutureTask<DouyinSigner.SignedTarget>(() -> signer.sign(request));
        Thread worker;
        synchronized (this) {
            if (signingWorker != null && signingWorker.isAlive()) throw new DouyinFailure(SIGNER_FAILED);
            worker = Thread.ofVirtual().name("bigfish-douyin-sign").unstarted(task);
            signingWorker = worker;
            signingAttempts.incrementAndGet();
            worker.start();
        }
        try { return task.get(signingTimeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException exception) { throw new DouyinFailure(SIGNER_TIMEOUT); }
        catch (ExecutionException exception) {
            if (exception.getCause() instanceof DouyinFailure failure) throw failure;
            throw new DouyinFailure(SIGNER_FAILED);
        } finally {
            task.cancel(true);
            // Bounded cleanup; a misbehaving extension remains visible in signingWorkers(), never hidden.
            boolean interrupted = Thread.interrupted();
            try { worker.join(100); }
            catch (InterruptedException exception) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    static void requireSigner(DouyinSigner signer) throws DouyinFailure {
        try {
            if (signer == null || signer.provenance() == null || !signer.provenance().isDeclared()) throw new DouyinFailure(SIGNER_UNAVAILABLE);
        } catch (RuntimeException exception) { throw new DouyinFailure(SIGNER_UNAVAILABLE); }
    }
    private void validateSigned(DouyinSigner.SignedTarget signed, DouyinSigner.Request request) throws DouyinFailure {
        if (signed == null || signed.endpoint() == null || signed.nonce() == null || !signed.nonce().equals(request.nonce())) {
            throw new DouyinFailure(SIGNATURE_INVALID);
        }
        var now = clock.instant();
        if (signed.issuedAt() == null || signed.expiresAt() == null || signed.issuedAt().isBefore(request.requestedAt())
                || signed.issuedAt().isAfter(now) || !signed.expiresAt().isAfter(now.plusSeconds(5))
                || Duration.between(signed.issuedAt(), signed.expiresAt()).compareTo(Duration.ofSeconds(60)) > 0
                || Duration.between(request.requestedAt(), now).compareTo(Duration.ofSeconds(10)) > 0) {
            throw new DouyinFailure(SIGNATURE_EXPIRED);
        }
        try {
            String query = signed.endpoint().getRawQuery();
            if (query == null || query.length() > 16384) throw new IllegalArgumentException();
            var values = new HashMap<String, String>();
            for (String pair : query.split("&", -1)) {
                String[] entry = pair.split("=", 2);
                if (entry.length != 2) throw new IllegalArgumentException();
                String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
                if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            }
            if (!request.internalRoomId().equals(values.get("room_id")) || !request.userUniqueId().equals(values.get("user_unique_id"))
                    || values.getOrDefault("signature", "").isBlank()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw new DouyinFailure(SIGNATURE_INVALID); }
    }
    public int inFlightRequests() { return resolver.inFlightRequests(); }
    public long requestsStarted() { return resolver.requestsStarted(); }
    public int signingWorkers() { var worker = signingWorker; return worker != null && worker.isAlive() ? 1 : 0; }
    public long signingAttempts() { return signingAttempts.get(); }
    InboundDiagnostics.Credentials credentialMode() {
        return guest == null ? InboundDiagnostics.Credentials.EXTERNAL : InboundDiagnostics.Credentials.GUEST;
    }
    InboundDiagnostics.CredentialEvidence credentialEvidence() { return credentialEvidence; }
    @Override public String toString() { return "WebDouyinConnectionProvider[redacted]"; }
}
