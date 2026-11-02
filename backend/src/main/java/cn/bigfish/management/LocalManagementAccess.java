package cn.bigfish.management;

import cn.bigfish.configuration.DouyinCredentialSettings.Failure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;

/** Internal policy for the proposed sensitive routes. Not registered against existing APIs. */
public final class LocalManagementAccess {
    public void require(HttpServletRequest request,boolean writing) {
        if(!loopback(request.getRemoteAddr())) deny("LOCAL_ACCESS_REQUIRED");
        String host=single(request,"Host");
        URI target=parse(request.getScheme()+"://"+host);
        if(!loopback(target.getHost()) || target.getRawUserInfo()!=null || !emptyPath(target) || target.getRawQuery()!=null || target.getRawFragment()!=null)
            deny("LOCAL_ACCESS_REQUIRED");
        if(!"http".equals(target.getScheme()) && !"https".equals(target.getScheme())) deny("LOCAL_ACCESS_REQUIRED");
        // No proxy trust inferred from headers; Vite's normal same-origin proxy preserves Host and Origin.
        for(String name:List.of("Forwarded","X-Forwarded-Host","X-Forwarded-For","X-Forwarded-Proto"))
            if(request.getHeader(name)!=null) deny("FORWARDED_ACCESS_UNSUPPORTED");
        String fetch=single(request,"Sec-Fetch-Site");
        if(fetch!=null && !fetch.equals("same-origin") && !fetch.equals("same-site") && !fetch.equals("none")) deny("CROSS_SITE_REJECTED");
        if(!"1".equals(single(request,"X-BigFish-Local-Management"))) deny("MANAGEMENT_HEADER_REQUIRED");
        String origin=single(request,"Origin");
        if(writing && origin==null) deny("ORIGIN_REQUIRED");
        if(origin!=null) {
            URI from=parse(origin);
            if(!Objects.equals(target.getScheme(),from.getScheme()) || !Objects.equals(target.getHost(),from.getHost())
                || port(target)!=port(from) || from.getRawUserInfo()!=null || !emptyPath(from) || from.getRawQuery()!=null || from.getRawFragment()!=null)
                deny("ORIGIN_REJECTED");
        }
        if(writing) {
            String content=single(request,"Content-Type");
            if(content==null || !content.split(";",2)[0].strip().equalsIgnoreCase("application/json")) deny("JSON_REQUIRED");
        }
    }
    private static String single(HttpServletRequest request,String header) {
        List<String> values=Collections.list(request.getHeaders(header));
        if(values.size()>1) deny("AMBIGUOUS_REQUEST_HEADERS");
        return values.isEmpty() ? null : values.getFirst();
    }
    private static URI parse(String input) {
        try {
            URI uri=URI.create(input);
            if(uri.getHost()==null || uri.getPort()>65535 || uri.getPort()==0) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException invalid) { throw new Failure("ORIGIN_REJECTED",403); }
    }
    private static boolean emptyPath(URI uri) { return uri.getRawPath()==null || uri.getRawPath().isEmpty(); }
    private static int port(URI uri) { return uri.getPort()<0 ? "https".equals(uri.getScheme()) ? 443 : 80 : uri.getPort(); }
    private static boolean loopback(String value) { return Set.of("127.0.0.1","localhost","::1","[::1]","0:0:0:0:0:0:0:1").contains(value==null ? "" : value); }
    private static void deny(String code) { throw new Failure(code,403); }
}
