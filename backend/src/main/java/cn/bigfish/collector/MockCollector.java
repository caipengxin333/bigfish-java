package cn.bigfish.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

import static cn.bigfish.collector.CollectorDiagnostics.Counter.MALFORMED;

/** Synthetic JSONL replay. Use a fresh InputStream per start; not a live Douyin connection. */
public final class MockCollector extends AbstractCollector {
    private final Supplier<InputStream> fixture;
    private final ObjectMapper mapper = new ObjectMapper();

    public MockCollector(Supplier<InputStream> fixture) {
        this(fixture, CollectorOptions.defaults(), Clock.systemUTC());
    }

    public MockCollector(Supplier<InputStream> fixture, CollectorOptions options, Clock clock) {
        super(options, clock); this.fixture = Objects.requireNonNull(fixture);
    }

    @Override public String mode() { return "MOCK"; }

    @Override protected void collect(Run context) throws Exception {
        try (InputStream stream = Objects.requireNonNull(fixture.get(), "Missing fixture")) {
            context.attach(stream);
            context.transition(State.CONNECTED);
            // Bound lines before JSON parsing; readLine() would allow an unbounded fixture allocation.
            var line = new java.io.ByteArrayOutputStream();
            boolean oversized = false;
            for (int value; context.active.get() && (value = stream.read()) != -1;) {
                if (value == '\n') {
                    replay(context, line.toByteArray(), oversized);
                    line.reset(); oversized = false;
                } else if (line.size() < options.maxFrameBytes()) line.write(value);
                else oversized = true;
            }
            if (context.active.get() && (line.size() > 0 || oversized)) replay(context, line.toByteArray(), oversized);
        }
    }

    private void replay(Run context, byte[] line, boolean oversized) {
        if (oversized) { diagnostics().error(MALFORMED, 0); return; }
        if (new String(line, StandardCharsets.UTF_8).isBlank()) return;
        try { context.accept(mapper.readValue(line, IncomingMessage.class), clock.instant()); }
        catch (Exception exception) { diagnostics().error(MALFORMED, 0); }
    }
}
