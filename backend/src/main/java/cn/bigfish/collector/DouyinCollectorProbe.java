package cn.bigfish.collector;

import cn.bigfish.contract.CollectorPort;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/** Explicit read-only CLI. No Spring context, event bodies, room/anchor IDs, URLs or credentials are printed. */
public final class DouyinCollectorProbe {
    private DouyinCollectorProbe() { }

    public static void main(String[] args) {
        int result;
        try {
            if (args.length != 0) throw new DouyinFailure(PROBE_CONFIGURATION_INVALID);
            result = run(System::getenv);
        }
        catch (DouyinFailure failure) { System.out.println("mode=DOUYIN state=FAILED code=" + failure.code()); result = 2; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); System.out.println("mode=DOUYIN state=STOPPED"); result = 2; }
        catch (Exception | java.util.ServiceConfigurationError exception) {
            System.out.println("mode=DOUYIN state=FAILED code=PROBE_CONFIGURATION_INVALID"); result = 2;
        }
        if (result != 0) System.exit(result);
    }

    static int run(Function<String, String> env) throws Exception {
        long started = System.nanoTime();
        Config config = configuration(env);
        var resolver = new DouyinRoomResolver();
        if (config.action.equals("resolve")) {
            try {
                var room = resolver.resolve(config.room, credentials(env));
                System.out.println("mode=DOUYIN action=resolve state=" + (room.live() ? "LIVE" : "ENDED")
                        + " roomResolved=true comments=0 gifts=0 receiveAttempted=false");
                return 0;
            } finally { System.out.println("httpRequests=" + resolver.requestsStarted() + " httpInFlight=" + resolver.inFlightRequests()
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000); }
        }
        if (!"true".equals(env.apply("BIGFISH_DOUYIN_SIGNER_REVIEWED"))) throw new DouyinFailure(SIGNER_UNAVAILABLE);
        // Explicit opt-in is checked before loading user-provided executable extensions.
        var signer = DouyinSigners.load();
        var provider = new WebDouyinConnectionProvider(() -> credentials(env), signer, resolver, java.time.Clock.systemUTC());
        // Validate with precise errors before starting; the supplier is then re-evaluated on each attempt.
        credentials(env);
        var attempts = new AtomicInteger(); var comments = new LongAdder(); var gifts = new LongAdder(); var giftUnits = new LongAdder();
        var follows = new LongAdder(); var entrances = new LongAdder(); var likes = new LongAdder();
        var collector = new DouyinCollector(new DouyinConnectionProvider() {
            @Override public DouyinConnection resolve(String room) throws Exception { return resolve(room, null); }
            @Override public DouyinConnection resolve(String room, String expectedRoom) throws Exception {
                if (attempts.incrementAndGet() > 3) throw new DouyinFailure(ATTEMPT_LIMIT);
                return provider.resolve(room, expectedRoom);
            }
        });
        Thread shutdown = new Thread(collector::stop, "bigfish-douyin-probe-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        CollectorPort.State finalState = CollectorPort.State.STOPPED;
        try {
            collector.start(config.room, "probe-" + java.util.UUID.randomUUID(), event -> {
                if (event.type().name().equals("COMMENT")) comments.increment();
                if (event.type().name().equals("GIFT")) {
                    gifts.increment();
                    if (event.payload().get("quantity") instanceof Number quantity) giftUnits.add(quantity.longValue());
                }
                if (event.type().name().equals("FOLLOW")) follows.increment();
                if (event.type().name().equals("ENTRANCE")) entrances.increment();
                if (event.type().name().equals("LIKE")) likes.increment();
            });
            long deadline = System.nanoTime() + config.seconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                var state = collector.state();
                if (state == CollectorPort.State.ENDED || state == CollectorPort.State.FAILED) break;
                Thread.sleep(100);
            }
            finalState = collector.state();
        } finally {
            collector.stop();
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while ((!collector.resources().released() || provider.signingWorkers() != 0 || provider.inFlightRequests() != 0)
                    && System.nanoTime() < deadline) {
                try { Thread.sleep(10); }
                catch (InterruptedException exception) { interrupted = true; }
            }
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM shutdown already started. */ }
            var d = collector.diagnostics();
            System.out.println("mode=DOUYIN action=receive terminal=" + finalState + " stopped=" + collector.state()
                    + " connections=" + d.count(CollectorDiagnostics.Counter.CONNECTED)
                    + " comments=" + comments.sum() + " gifts=" + gifts.sum() + " giftUnits=" + giftUnits.sum()
                    + " follows=" + follows.sum() + " entrances=" + entrances.sum() + " likes=" + likes.sum()
                    + " httpRequests=" + resolver.requestsStarted() + " signingAttempts=" + provider.signingAttempts()
                    + " handshakeAttempts=" + d.count(CollectorDiagnostics.Counter.HANDSHAKE_ATTEMPT)
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000
                    + " ack=" + d.count(CollectorDiagnostics.Counter.ACK) + " heartbeat=" + d.count(CollectorDiagnostics.Counter.HEARTBEAT)
                    + " duplicate=" + d.count(CollectorDiagnostics.Counter.DUPLICATE)
                    + " malformed=" + d.count(CollectorDiagnostics.Counter.MALFORMED)
                    + " reconnect=" + d.count(CollectorDiagnostics.Counter.RECONNECT) + " code=" + d.lastError());
            System.out.println(collector.resources() + " httpInFlight=" + provider.inFlightRequests() + " signingWorkers=" + provider.signingWorkers());
            if (interrupted) Thread.currentThread().interrupt();
        }
        // A successful handshake without an actual business message is not a successful receive verification.
        return comments.sum() > 0 && finalState != CollectorPort.State.FAILED && collector.resources().released()
                && provider.inFlightRequests() == 0 && provider.signingWorkers() == 0 ? 0 : 2;
    }

    static Config configuration(Function<String, String> env) throws DouyinFailure {
        if (!"true".equals(env.apply("BIGFISH_DOUYIN_LIVE_PROBE"))) throw new DouyinFailure(PROBE_DISABLED);
        String room = env.apply("BIGFISH_DOUYIN_ROOM");
        if (room == null || room.isBlank()) throw new DouyinFailure(ROOM_REQUIRED);
        DouyinRoomInput.parse(room);
        String action = env.apply("BIGFISH_DOUYIN_PROBE_ACTION");
        if (!"resolve".equals(action) && !"receive".equals(action)) throw new DouyinFailure(PROBE_CONFIGURATION_INVALID);
        int seconds = 60;
        try {
            String value = env.apply("BIGFISH_DOUYIN_PROBE_SECONDS");
            if (value != null) seconds = Integer.parseInt(value);
            if (seconds < 1 || seconds > 120) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) { throw new DouyinFailure(PROBE_CONFIGURATION_INVALID); }
        return new Config(room, action, seconds);
    }
    static DouyinCredentials credentials(Function<String, String> env) throws DouyinFailure {
        String expiry = env.apply("BIGFISH_DOUYIN_CREDENTIALS_EXPIRES_AT");
        Instant expiresAt;
        try { expiresAt = expiry == null ? null : Instant.parse(expiry); }
        catch (java.time.format.DateTimeParseException exception) { throw new DouyinFailure(CREDENTIALS_INVALID); }
        var credentials = new DouyinCredentials(env.apply("BIGFISH_DOUYIN_COOKIE"), env.apply("BIGFISH_DOUYIN_USER_AGENT"),
                env.apply("BIGFISH_DOUYIN_USER_UNIQUE_ID"), expiresAt);
        credentials.validate(Instant.now());
        return credentials;
    }
    record Config(String room, String action, int seconds) {
        @Override public String toString() { return "ProbeConfig[redacted]"; }
    }
}
