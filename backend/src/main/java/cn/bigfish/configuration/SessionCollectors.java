package cn.bigfish.configuration;

import cn.bigfish.collector.*;
import cn.bigfish.contract.CollectorPort;
import java.io.ByteArrayInputStream;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Lazy session-owned adapters. Constructing this factory or a handle does no network I/O. */
public class SessionCollectors {
    public record Handle(CollectorPort port, CollectorDiagnostics diagnostics, BooleanSupplier released) {}

    public Handle mock(byte[] input) {
        var collector = new MockCollector(() -> new ByteArrayInputStream(input));
        return new Handle(collector,collector.diagnostics(),() -> collector.resources().released());
    }

    public Handle douyin(Predicate<String> bindInternalRoom) {
        // The existing guest provider resolves the room before signing and opening WSS. Observe only
        // its trusted room ID, never infer a binding from an incoming message or retain credentials.
        var provider = WebDouyinConnectionProvider.guest(bindingSigner(new NativeDouyinSigner(),bindInternalRoom));
        var collector = new DouyinCollector(provider);
        return new Handle(collector,collector.diagnostics(),() -> collector.resources().released()
            && provider.inFlightRequests()==0 && provider.signingWorkers()==0);
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
