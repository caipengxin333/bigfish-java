package cn.bigfish.collector;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static cn.bigfish.collector.DouyinFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class DouyinSignersTest {
    private static final String SPI = "META-INF/services/cn.bigfish.collector.DouyinSigner";
    private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

    @Test void packagedServiceLoadsRealNativeSignerWithItsDistributionLicense() throws Exception {
        assertInstanceOf(NativeDouyinSigner.class, DouyinSigners.load());
        try (var input = DouyinSigner.class.getClassLoader().getResourceAsStream(SPI)) {
            assertNotNull(input);
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("MIT License")); assertTrue(text.contains("Copyright (c) 2025 今晚打打"));
            assertTrue(text.contains("Permission is hereby granted")); assertTrue(text.contains(NativeDouyinSigner.SOURCE_REVISION));
        }
    }

    @Test void missingMultipleMalformedAndFailingServicesHaveSafeDiagnostics() throws Exception {
        assertEquals(SIGNER_UNAVAILABLE, assertThrows(DouyinFailure.class, () -> DouyinSigners.load(loader(""))).code());
        CONSTRUCTED.set(0);
        assertEquals(SIGNER_CONFIGURATION_INVALID, assertThrows(DouyinFailure.class,
                () -> DouyinSigners.load(loader(NativeDouyinSigner.class.getName() + "\n" + ThrowingSigner.class.getName()))).code());
        assertEquals(0, CONSTRUCTED.get());
        for (String declaration : List.of("synthetic.missing.Signer", "bad name", ThrowingSigner.class.getName())) {
            var failure = assertThrows(DouyinFailure.class, () -> DouyinSigners.load(loader(declaration)));
            assertEquals(SIGNER_CONFIGURATION_INVALID, failure.code());
            assertEquals("SIGNER_CONFIGURATION_INVALID", failure.getMessage()); assertNull(failure.getCause());
        }
        assertEquals(1, CONSTRUCTED.get());
    }

    // Memory-only service descriptions; never alter the production classpath or write an extension jar.
    private static ClassLoader loader(String contents) throws Exception {
        URL memory = URL.of(URI.create("https://example.invalid/synthetic-service"), new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override public void connect() { }
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)); }
                };
            }
        });
        return new ClassLoader(DouyinSigner.class.getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws java.io.IOException {
                return name.equals(SPI) ? Collections.enumeration(List.of(memory)) : super.getResources(name);
            }
        };
    }
    public static final class ThrowingSigner implements DouyinSigner {
        public ThrowingSigner() { CONSTRUCTED.incrementAndGet(); throw new IllegalStateException("synthetic private input"); }
        @Override public Provenance provenance() { return null; }
        @Override public SignedTarget sign(Request request) { throw new AssertionError(); }
    }
}
