package cn.bigfish.configuration;

import cn.bigfish.collector.DouyinCredentials;
import cn.bigfish.collector.DouyinFailure;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Process-local secrets and exclusive ownership; never a business config or HTTP DTO. */
public final class DouyinCredentialSettings {
    public enum Mode { GUEST, EXTERNAL, INVALID }
    public enum Source { DEFAULT, ENVIRONMENT, RUNTIME }
    public enum Use { INTERACTION, CHECK }
    public static final class Failure extends RuntimeException {
        public final String code;
        public final int status;
        public Failure(String code,int status) { super(code,null,false,false); this.code=code; this.status=status; }
    }
    /** Safe metadata only. Revision changes across process restarts as well as updates. */
    public record View(Mode mode,Source source,long version,String revision,Instant updatedAt,
                       boolean cookieConfigured,Instant localExpiresAt,String validation,String identityMode,
                       String activeUse,Long activeVersion) {}
    public static final class Snapshot {
        private final Mode mode;
        private final Source source;
        private final long version;
        private final String revision=UUID.randomUUID().toString();
        private final Instant updatedAt,expires;
        private final String cookie,agent,visitor,invalid;
        private Snapshot(Mode mode,Source source,long version,Instant at,String cookie,String agent,String visitor,Instant expires,String invalid) {
            this.mode=mode; this.source=source; this.version=version; updatedAt=at;
            this.cookie=cookie; this.agent=agent; this.visitor=visitor; this.expires=expires; this.invalid=invalid;
        }
        public Mode mode() { return mode; }
        public Source source() { return source; }
        public long version() { return version; }
        public String revision() { return revision; }
        public String validation(Clock clock) {
            if(invalid!=null) return invalid;
            if(mode==Mode.GUEST) return "GUEST_AUTO";
            if(!expires.isAfter(clock.instant())) return "CREDENTIALS_EXPIRED";
            return "LOCAL_VALID"; // Visitor discovery is now supported by the external provider.
        }
        public void requireConnection(Clock clock) {
            String result=validation(clock);
            if(!result.equals("GUEST_AUTO") && !result.equals("LOCAL_VALID")) throw new Failure(result,503);
        }
        public DouyinCredentials externalCredentials(Clock clock) throws DouyinFailure {
            String result=validation(clock);
            if(!result.equals("LOCAL_VALID")) {
                DouyinFailure.Code code=switch(result) {
                    case "CREDENTIALS_EXPIRED" -> DouyinFailure.Code.CREDENTIALS_EXPIRED;
                    case "CREDENTIALS_MISSING" -> DouyinFailure.Code.CREDENTIALS_MISSING;
                    default -> DouyinFailure.Code.CREDENTIALS_INVALID;
                };
                throw new DouyinFailure(code);
            }
            return new DouyinCredentials(cookie,agent,visitor,expires);
        }
        private View view(Clock clock,String active,Long activeVersion) {
            return new View(mode,source,version,revision,updatedAt,!blank(cookie),expires,validation(clock),
                blank(visitor) ? "AUTO" : "MANUAL",active,activeVersion);
        }
        // Protect accidental direct/nested serialization and diagnostic formatting.
        @JsonValue @Override public String toString() { return "DouyinCredentialSnapshot[redacted]"; }
    }
    public final class Lease implements AutoCloseable {
        private final Snapshot snapshot;
        private final Use use;
        private boolean stopping;
        private Lease(Use use,Snapshot snapshot) { this.use=use; this.snapshot=snapshot; }
        public Snapshot snapshot() { return snapshot; }
        public String validation() { return snapshot.validation(clock); }
        public void stopping() { synchronized(DouyinCredentialSettings.this) { if(active==this) stopping=true; } }
        @Override public void close() { synchronized(DouyinCredentialSettings.this) { if(active==this) active=null; } }
        @JsonValue @Override public String toString() { return "DouyinCredentialLease[redacted]"; }
    }
    private final Clock clock;
    private Snapshot selected;
    private Lease active;
    public DouyinCredentialSettings(Function<String,String> environment,Clock clock) {
        this.clock=Objects.requireNonNull(clock); selected=environment(environment);
    }
    public synchronized View status() { return selected.view(clock,active==null ? "NONE" : active.use.name()+(active.stopping ? "_STOPPING" : ""),active==null ? null : active.snapshot.version()); }
    public synchronized Snapshot current() { return selected; }
    /** The original factory API remains usable; a live owner always gets its captured identity. */
    public synchronized Snapshot forCollector() { return active==null ? selected : active.snapshot; }
    public synchronized Lease acquire(Use use) { return acquire(use,null); }
    public synchronized Lease acquire(Use use,String expectedRevision) {
        idle(); if(expectedRevision!=null) revision(expectedRevision);
        active=new Lease(Objects.requireNonNull(use),selected); return active;
    }
    public synchronized View guest(String expectedRevision) { writable(expectedRevision); selectGuest(); return status(); }
    public synchronized View clear(String expectedRevision,boolean confirmed) {
        writable(expectedRevision); if(!confirmed) throw new Failure("CLEAR_CONFIRMATION_REQUIRED",400);
        selectGuest(); return status();
    }
    public synchronized View replace(String expectedRevision,boolean replace,String cookie,String agent,String manualVisitor,
                                     Duration lifetime,Instant expiresAt) {
        writable(expectedRevision);
        if(!replace) throw new Failure("REPLACEMENT_REQUIRED",400);
        if((lifetime==null)==(expiresAt==null)) throw new Failure("LOCAL_DEADLINE_INVALID",400);
        Instant at=clock.instant(),expires;
        try { expires=lifetime==null ? expiresAt : at.plus(lifetime); }
        catch(DateTimeException | ArithmeticException invalid) { throw new Failure("LOCAL_DEADLINE_INVALID",400); }
        Duration remaining=Duration.between(at,expires);
        if(remaining.compareTo(Duration.ofMinutes(1))<0 || remaining.compareTo(Duration.ofDays(7))>0)
            throw new Failure("LOCAL_DEADLINE_INVALID",400);
        String invalid=validate(cookie,agent,manualVisitor,expires);
        if(invalid!=null) throw new Failure(invalid,400);
        selected=new Snapshot(Mode.EXTERNAL,Source.RUNTIME,selected.version+1,at,cookie,agent,manualVisitor,expires,null);
        return status();
    }
    private void writable(String expected) { idle(); revision(expected); }
    private void idle() {
        if(active!=null) throw new Failure(active.use==Use.INTERACTION
            ? active.stopping ? "COLLECTOR_STOPPING" : "SESSION_ACTIVE"
            : active.stopping ? "CHECK_STOPPING" : "CHECK_ACTIVE",409);
    }
    private void revision(String expected) { if(!selected.revision.equals(expected)) throw new Failure("CREDENTIAL_CONFIG_CONFLICT",409); }
    private void selectGuest() { selected=new Snapshot(Mode.GUEST,Source.RUNTIME,selected.version+1,clock.instant(),null,null,null,null,null); }
    private Snapshot environment(Function<String,String> env) {
        Mode mode; String input;
        try { input=env.apply("BIGFISH_DOUYIN_CREDENTIAL_MODE"); }
        catch(RuntimeException invalid) { return invalidEnvironment("CREDENTIAL_MODE_INVALID"); }
        mode=input==null || input.equals("guest") ? Mode.GUEST : input.equals("external") ? Mode.EXTERNAL : Mode.INVALID;
        if(mode==Mode.INVALID) return invalidEnvironment("CREDENTIAL_MODE_INVALID");
        if(mode==Mode.GUEST) return new Snapshot(mode,input==null ? Source.DEFAULT : Source.ENVIRONMENT,0,clock.instant(),null,null,null,null,null);
        try {
            String expiry=env.apply("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT");
            Instant expires=expiry==null ? null : Instant.parse(expiry);
            String cookie=env.apply("BIGFISH_DOUYIN_COOKIE"),agent=env.apply("BIGFISH_DOUYIN_USER_AGENT"),visitor=env.apply("BIGFISH_DOUYIN_USER_UNIQUE_ID");
            return new Snapshot(mode,Source.ENVIRONMENT,0,clock.instant(),cookie,agent,visitor,expires,validate(cookie,agent,visitor,expires));
        } catch(RuntimeException invalid) { return new Snapshot(mode,Source.ENVIRONMENT,0,clock.instant(),null,null,null,null,"CREDENTIALS_INVALID"); }
    }
    private Snapshot invalidEnvironment(String code) { return new Snapshot(Mode.INVALID,Source.ENVIRONMENT,0,clock.instant(),null,null,null,null,code); }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static String validate(String cookie,String agent,String visitor,Instant expires) {
        try {
            // Collector owns the canonical header and optional identity validation.
            // Expiry is checked against the service clock by Snapshot.validation.
            new DouyinCredentials(cookie,agent,visitor,expires).validateForResolution(Instant.MIN);
            return null;
        } catch(DouyinFailure invalid) { return invalid.code().name(); }
    }
    @JsonValue @Override public String toString() { return "DouyinCredentialSettings[redacted]"; }
}
