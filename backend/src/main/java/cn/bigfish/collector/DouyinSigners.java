package cn.bigfish.collector;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/** Load exactly one provider after the caller's explicit opt-in; presence is not proof of platform acceptance. */
public final class DouyinSigners {
    private DouyinSigners() { }
    public static DouyinSigner load() throws DouyinFailure { return load(DouyinSigner.class.getClassLoader()); }

    static DouyinSigner load(ClassLoader loader) throws DouyinFailure {
        try {
            // Determine ambiguity before invoking any provider constructor.
            var candidates = ServiceLoader.load(DouyinSigner.class, loader).stream().limit(2).toList();
            if (candidates.isEmpty()) throw new DouyinFailure(SIGNER_UNAVAILABLE);
            if (candidates.size() != 1) throw new DouyinFailure(SIGNER_CONFIGURATION_INVALID);
            var signer = candidates.getFirst().get();
            WebDouyinConnectionProvider.requireSigner(signer);
            return signer;
        } catch (ServiceConfigurationError | RuntimeException error) { throw new DouyinFailure(SIGNER_CONFIGURATION_INVALID); }
    }
}
