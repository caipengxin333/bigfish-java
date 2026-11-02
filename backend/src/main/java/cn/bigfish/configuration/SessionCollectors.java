package cn.bigfish.configuration;

import cn.bigfish.collector.*;
import cn.bigfish.contract.CollectorPort;
import java.io.ByteArrayInputStream;
import java.time.Clock;
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
    private final Clock clock;
    private final DouyinCredentialSettings settings;

    /** Standalone/default callers stay guest; Spring explicitly supplies its server environment. */
    public SessionCollectors() { this(ignored -> null, Clock.systemUTC()); }
    public SessionCollectors(Function<String,String> environment, Clock clock) {
        this(new DouyinCredentialSettings(environment,clock),clock);
    }
    public SessionCollectors(DouyinCredentialSettings settings,Clock clock) {
        this.settings=settings; this.clock=clock;
        var view=settings.status();
        log.info("douyin_connection mode={} source={} version={} validation={} platform=UNVERIFIED",view.mode(),view.source(),view.version(),view.validation());
    }
    public DouyinCredentialSettings settings() { return settings; }
    public CredentialMode credentialMode() { return CredentialMode.valueOf(settings.current().mode().name()); }
    /** Purely local configuration evidence, never a login/connectivity probe. */
    public String configurationStatus() {
        return settings.status().validation();
    }

    public Handle mock(byte[] input) {
        var collector = new MockCollector(() -> new ByteArrayInputStream(input));
        return new Handle(collector,collector.diagnostics(),() -> collector.resources().released());
    }

    public Handle douyin(Predicate<String> bindInternalRoom) {
        return create(settings.forCollector(),bindInternalRoom,false);
    }
    /** One provider resolve attempt only, no business ingress. Caller owns stop and release gating. */
    public Handle check(DouyinCredentialSettings.Snapshot snapshot) { return create(snapshot,ignored -> true,true); }
    private Handle create(DouyinCredentialSettings.Snapshot snapshot,Predicate<String> bindInternalRoom,boolean boundedCheck) {
        try { snapshot.requireConnection(clock); }
        catch(DouyinCredentialSettings.Failure failure) {
            log.warn("douyin_connection_rejected mode={} source={} version={} validation={} platform=UNVERIFIED",snapshot.mode(),snapshot.source(),snapshot.version(),failure.code);
            throw new ConfigurationFailure(failure.code);
        }
        // Both existing providers resolve the room before signing and opening WSS. Observe only
        // its trusted room ID, never infer a binding from an incoming message or retain credentials.
        var signer=bindingSigner(new NativeDouyinSigner(),bindInternalRoom);
        var provider = snapshot.mode()==DouyinCredentialSettings.Mode.GUEST ? guestProvider(signer)
            : externalProvider(() -> snapshot.externalCredentials(clock),signer);
        log.info("douyin_connection_selected mode={} source={} version={} validation={} platform=UNVERIFIED",snapshot.mode(),snapshot.source(),snapshot.version(),snapshot.validation(clock));
        var collector = new DouyinCollector(provider,boundedCheck);
        return new Handle(collector,collector.diagnostics(),() -> collector.resources().released()
            && provider.inFlightRequests()==0 && provider.signingWorkers()==0);
    }

    protected WebDouyinConnectionProvider guestProvider(DouyinSigner signer) { return WebDouyinConnectionProvider.guest(signer); }
    protected WebDouyinConnectionProvider externalProvider(WebDouyinConnectionProvider.CredentialSource source, DouyinSigner signer) {
        return new WebDouyinConnectionProvider(source,signer);
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
