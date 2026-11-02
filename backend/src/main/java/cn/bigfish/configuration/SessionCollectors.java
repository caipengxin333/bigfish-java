package cn.bigfish.configuration;

import cn.bigfish.collector.*;
import cn.bigfish.contract.CollectorPort;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lazy session-owned adapters. Constructing this factory or a handle does no network I/O. */
public class SessionCollectors {
    private static final Logger log = LoggerFactory.getLogger(SessionCollectors.class);
    public enum CredentialMode { GUEST, EXTERNAL, INVALID }
    public static final class ConfigurationFailure extends RuntimeException {
        private final String code;
        private ConfigurationFailure(String code) { super(code, null, false, false); this.code=code; }
        public String code() { return code; }
    }
    public record Handle(CollectorPort port, CollectorDiagnostics diagnostics, BooleanSupplier released) {}
    private final Function<String,String> environment;
    private final Clock clock;
    private final CredentialMode credentialMode;

    /** Standalone/default callers stay guest; Spring explicitly supplies its server environment. */
    public SessionCollectors() { this(ignored -> null, Clock.systemUTC()); }
    public SessionCollectors(Function<String,String> environment, Clock clock) {
        this.environment=environment; this.clock=clock;
        String mode;
        try { mode=environment.apply("BIGFISH_DOUYIN_CREDENTIAL_MODE"); }
        catch (RuntimeException invalidProperty) { mode="invalid"; }
        credentialMode=mode==null || mode.equals("guest") ? CredentialMode.GUEST
            : mode.equals("external") ? CredentialMode.EXTERNAL : CredentialMode.INVALID;
        String validation=configurationStatus();
        log.info("douyin_connection mode={} validation={} platform=UNVERIFIED",credentialMode,validation);
    }

    public CredentialMode credentialMode() { return credentialMode; }
    /** Purely local configuration evidence, never a login/connectivity probe. */
    public String configurationStatus() {
        if (credentialMode==CredentialMode.INVALID) return "CREDENTIAL_MODE_INVALID";
        if (credentialMode==CredentialMode.GUEST) return "GUEST_AUTO";
        try { externalCredentials(); return "LOCAL_VALID"; }
        catch (DouyinFailure failure) { return failure.code().name(); }
    }

    public Handle mock(byte[] input) {
        var collector = new MockCollector(() -> new ByteArrayInputStream(input));
        return new Handle(collector,collector.diagnostics(),() -> collector.resources().released());
    }

    public Handle douyin(Predicate<String> bindInternalRoom) {
        String validation=configurationStatus();
        if (credentialMode==CredentialMode.INVALID || credentialMode==CredentialMode.EXTERNAL && !validation.equals("LOCAL_VALID")) {
            log.warn("douyin_connection_rejected mode={} validation={} platform=UNVERIFIED",credentialMode,validation);
            throw new ConfigurationFailure(validation);
        }
        // Both existing providers resolve the room before signing and opening WSS. Observe only
        // its trusted room ID, never infer a binding from an incoming message or retain credentials.
        var signer=bindingSigner(new NativeDouyinSigner(),bindInternalRoom);
        var provider = credentialMode==CredentialMode.GUEST ? guestProvider(signer) : externalProvider(this::externalCredentials,signer);
        log.info("douyin_connection_selected mode={} validation={} platform=UNVERIFIED",credentialMode,validation);
        var collector = new DouyinCollector(provider);
        return new Handle(collector,collector.diagnostics(),() -> collector.resources().released()
            && provider.inFlightRequests()==0 && provider.signingWorkers()==0);
    }

    protected WebDouyinConnectionProvider guestProvider(DouyinSigner signer) { return WebDouyinConnectionProvider.guest(signer); }
    protected WebDouyinConnectionProvider externalProvider(WebDouyinConnectionProvider.CredentialSource source, DouyinSigner signer) {
        return new WebDouyinConnectionProvider(source,signer);
    }

    private DouyinCredentials externalCredentials() throws DouyinFailure {
        try { return readExternalCredentials(); }
        catch (RuntimeException invalidProperty) { throw new DouyinFailure(DouyinFailure.Code.CREDENTIALS_INVALID); }
    }
    private DouyinCredentials readExternalCredentials() throws DouyinFailure {
        // Match the probe's names and local checks. Collector's package-private validate remains authoritative
        // before every HTTP attempt; no reflective access or collector changes are needed for startup checks.
        Instant expires;
        String expiry=environment.apply("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT");
        try { expires=expiry==null ? null : Instant.parse(expiry); }
        catch (java.time.format.DateTimeParseException ignored) { throw new DouyinFailure(DouyinFailure.Code.CREDENTIALS_INVALID); }
        String cookie=environment.apply("BIGFISH_DOUYIN_COOKIE"), agent=environment.apply("BIGFISH_DOUYIN_USER_AGENT"),
            visitor=environment.apply("BIGFISH_DOUYIN_USER_UNIQUE_ID");
        if (cookie==null || cookie.isBlank() || agent==null || agent.isBlank() || visitor==null || visitor.isBlank())
            throw new DouyinFailure(DouyinFailure.Code.CREDENTIALS_MISSING);
        if (cookie.length()>16384 || agent.length()>2048 || cookie.contains("\r") || cookie.contains("\n")
            || agent.contains("\r") || agent.contains("\n") || !visitor.matches("[0-9]{1,30}") || expires==null)
            throw new DouyinFailure(DouyinFailure.Code.CREDENTIALS_INVALID);
        if (!expires.isAfter(clock.instant())) throw new DouyinFailure(DouyinFailure.Code.CREDENTIALS_EXPIRED);
        return new DouyinCredentials(cookie,agent,visitor,expires);
    }

    static DouyinSigner bindingSigner(DouyinSigner delegate, Predicate<String> binding) {
        return new DouyinSigner() {
            @Override public Provenance provenance() { return delegate.provenance(); }
            @Override public SignedTarget sign(Request request) throws Exception {
                if (Thread.currentThread().isInterrupted() || !binding.test(request.internalRoomId()))
                    throw new InterruptedException("SESSION_CLOSED");
                return delegate.sign(request);
            }
        };
    }
}
