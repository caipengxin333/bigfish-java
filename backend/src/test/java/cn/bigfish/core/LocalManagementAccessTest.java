package cn.bigfish.core;

import cn.bigfish.management.LocalManagementAccess;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class LocalManagementAccessTest {
    final LocalManagementAccess policy=new LocalManagementAccess();
    MockHttpServletRequest request() {
        var request=new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.setScheme("http");
        request.addHeader("Host","127.0.0.1:15174"); request.addHeader("Origin","http://127.0.0.1:15174");
        request.addHeader("X-BigFish-Local-Management","1"); request.setContentType("application/json");
        request.addHeader("Sec-Fetch-Site","same-origin"); return request;
    }
    @Test void localSameOriginProxyAndReadOnlyRequestsPassWithoutGlobalCorsRelaxation() {
        policy.require(request(),true);
        var read=request(); read.removeHeader("Origin"); read.removeHeader("Content-Type"); policy.require(read,false);
        var ipv6=request(); ipv6.setRemoteAddr("::1"); ipv6.removeHeader("Host"); ipv6.addHeader("Host","[::1]:18081");
        ipv6.removeHeader("Origin"); ipv6.addHeader("Origin","http://[::1]:18081"); policy.require(ipv6,true);
    }
    @Test void crossSiteNullOriginDnsRebindingMissingHeadersAndForwardedRequestsFailClosed() {
        for(String origin:new String[]{"null","https://evil.invalid","http://127.0.0.1:18080","http://127.0.0.1:15174/path"}) {
            var request=request(); request.removeHeader("Origin"); request.addHeader("Origin",origin);
            assertThatThrownBy(() -> policy.require(request,true)).hasMessage("ORIGIN_REJECTED");
        }
        var missing=request(); missing.removeHeader("Origin"); assertThatThrownBy(() -> policy.require(missing,true)).hasMessage("ORIGIN_REQUIRED");
        var remote=request(); remote.setRemoteAddr("203.0.113.1"); assertThatThrownBy(() -> policy.require(remote,true)).hasMessage("LOCAL_ACCESS_REQUIRED");
        var rebinding=request(); rebinding.removeHeader("Host"); rebinding.addHeader("Host","evil.invalid:18081");
        assertThatThrownBy(() -> policy.require(rebinding,true)).hasMessage("LOCAL_ACCESS_REQUIRED");
        var cross=request(); cross.removeHeader("Sec-Fetch-Site"); cross.addHeader("Sec-Fetch-Site","cross-site");
        assertThatThrownBy(() -> policy.require(cross,false)).hasMessage("CROSS_SITE_REJECTED");
        var form=request(); form.setContentType("text/plain"); assertThatThrownBy(() -> policy.require(form,true)).hasMessage("JSON_REQUIRED");
        var ambiguous=request(); ambiguous.addHeader("Origin","http://other.invalid");
        assertThatThrownBy(() -> policy.require(ambiguous,true)).hasMessage("AMBIGUOUS_REQUEST_HEADERS");
        var forwarded=request(); forwarded.addHeader("X-Forwarded-For","127.0.0.1");
        assertThatThrownBy(() -> policy.require(forwarded,true)).hasMessage("FORWARDED_ACCESS_UNSUPPORTED");
        var header=request(); header.removeHeader("X-BigFish-Local-Management");
        assertThatThrownBy(() -> policy.require(header,false)).hasMessage("MANAGEMENT_HEADER_REQUIRED");
    }
}
