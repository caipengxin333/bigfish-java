package cn.bigfish.management;

import cn.bigfish.configuration.DouyinCredentialSettings;
import cn.bigfish.configuration.DouyinCredentialSettings.Failure;
import cn.bigfish.contract.DouyinAccessContract.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Guard before reading sensitive JSON; no raw binding errors, request bodies or credentials in responses. */
@RestController
@RequestMapping("/api/v1/douyin/credentials")
public final class DouyinAccessController {
    private final DouyinCredentialSettings settings;
    private final DouyinConnectionChecks checks;
    private final SessionService sessions;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final LocalManagementAccess access=new LocalManagementAccess();
    public DouyinAccessController(DouyinCredentialSettings settings,DouyinConnectionChecks checks,
                                  SessionService sessions,ObjectMapper mapper,Validator validator) {
        this.settings=settings; this.checks=checks; this.sessions=sessions; this.mapper=mapper; this.validator=validator;
    }
    @GetMapping public ResponseEntity<Status> status(HttpServletRequest request) {
        guard(request,false); return response(200,view(settings.status()));
    }
    @PutMapping("/mode") public ResponseEntity<Status> guest(HttpServletRequest request) {
        var value=body(request,SetGuest.class);
        return response(200,view(settings.guest(value.expectedRevision())));
    }
    @PutMapping("/cookie") public ResponseEntity<Status> replace(HttpServletRequest request) {
        var value=body(request,Replace.class);
        return response(200,view(settings.replace(value.expectedRevision(),value.replace(),value.cookie(),value.userAgent(),
            value.userUniqueIdOverride(),value.localLifetimeSeconds()==null ? null : Duration.ofSeconds(value.localLifetimeSeconds()),value.localExpiresAt())));
    }
    @PostMapping("/clear") public ResponseEntity<Status> clear(HttpServletRequest request) {
        var value=body(request,Clear.class);
        return response(200,view(settings.clear(value.expectedRevision(),value.confirm())));
    }
    @PostMapping("/checks") public ResponseEntity<Check> start(HttpServletRequest request) {
        var value=body(request,StartCheck.class);
        return response(202,view(checks.start(value.expectedRevision(),value.roomId(),Duration.ofSeconds(value.durationSeconds()))));
    }
    @GetMapping("/checks/{id}") public ResponseEntity<Check> check(HttpServletRequest request,@PathVariable String id) {
        guard(request,false); return response(200,view(checks.status(id)));
    }
    @PostMapping("/checks/{id}/cancel") public ResponseEntity<Check> cancel(HttpServletRequest request,@PathVariable String id) {
        body(request,Cancel.class); return response(200,view(checks.cancel(id)));
    }
    private void guard(HttpServletRequest request,boolean writing) {
        access.require(request,writing); sessions.reconcile(); checks.tick();
    }
    private <T> T body(HttpServletRequest request,Class<T> type) {
        guard(request,true);
        if(request.getContentLengthLong()>32768) throw new Failure("REQUEST_TOO_LARGE",413);
        try {
            byte[] bytes=request.getInputStream().readNBytes(32769);
            if(bytes.length>32768) throw new Failure("REQUEST_TOO_LARGE",413);
            try(var parser=mapper.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                // Optional record components may be absent; required inputs are enforced below by Bean Validation.
                T value=mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).readValue(parser);
                if(value==null || parser.nextToken()!=null || !validator.validate(value).isEmpty())
                    throw new Failure("INVALID_REQUEST",400);
                return value;
            }
        } catch(Failure known) { throw known; }
        catch(Exception invalid) { throw new Failure("INVALID_REQUEST",400); }
    }
    @ExceptionHandler(Failure.class) public ResponseEntity<?> known(Failure failure) {
        return response(failure.status,Map.of("code",failure.code));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unknown(Exception ignored) {
        return response(500,Map.of("code","CREDENTIAL_OPERATION_FAILED"));
    }
    private static <T> ResponseEntity<T> response(int status,T value) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(value);
    }
    private static Status view(DouyinCredentialSettings.View v) {
        return new Status(v.mode().name(),v.source().name(),v.version(),v.revision(),v.updatedAt(),v.cookieConfigured(),
            v.localExpiresAt(),v.validation(),v.identityMode(),v.activeUse(),v.activeVersion());
    }
    private static Check view(DouyinConnectionChecks.View v) {
        return new Check(v.checkId(),v.configVersion(),v.configRevision(),v.source().name(),v.mode().name(),v.localValidation(),
            v.state().name(),v.reason(),v.cleanupError(),v.connected(),v.comments(),v.gifts(),v.startedAt(),v.deadline(),v.resourcesReleased());
    }
}
