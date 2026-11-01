package cn.bigfish.configuration;

import cn.bigfish.collector.*;
import cn.bigfish.contract.CollectorPort;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionCollectorsTest {
    @Test void defaultFactorySelectsExistingCollectorsWithoutConnectingOnConstruction() {
        var factory=new SessionCollectors();
        var mock=factory.mock(new byte[0]);
        var douyin=factory.douyin(room -> { throw new AssertionError("Construction must not resolve"); });
        assertThat(mock.port()).isInstanceOf(MockCollector.class);
        assertThat(douyin.port()).isInstanceOf(DouyinCollector.class);
        for(var handle:new SessionCollectors.Handle[]{mock,douyin}) {
            assertThat(handle.port().state()).isEqualTo(CollectorPort.State.STOPPED);
            assertThat(handle.diagnostics().count(CollectorDiagnostics.Counter.HANDSHAKE_ATTEMPT)).isZero();
            assertThat(handle.released().getAsBoolean()).isTrue();
            handle.port().stop();
        }
    }
    @Test void trustedResolverIdBindsBeforeUnchangedSignerAndClosedSessionNeverSigns() throws Exception {
        var bound=new AtomicBoolean(); var signed=new AtomicBoolean();
        var provenance=new NativeDouyinSigner().provenance();
        DouyinSigner delegate=new DouyinSigner() {
            public Provenance provenance() { return provenance; }
            public SignedTarget sign(Request request) {
                assertThat(bound).isTrue(); signed.set(true); return null;
            }
        };
        var request=new DouyinSigner.Request("987654321","fixture-visitor","fixture-agent","fixture-nonce",Instant.now());
        var observer=SessionCollectors.bindingSigner(delegate,id -> {
            assertThat(id).isEqualTo("987654321"); bound.set(true); return true;
        });
        assertThat(observer.provenance()).isSameAs(provenance);
        observer.sign(request); assertThat(signed).isTrue(); signed.set(false);
        var closed=SessionCollectors.bindingSigner(delegate,id -> false);
        assertThatThrownBy(() -> closed.sign(request)).isInstanceOf(InterruptedException.class);
        assertThat(signed).isFalse();
    }
}
