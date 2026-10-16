package com.rs.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;

import com.rs.lib.yml.YmlTestSupport.Captured;

/** The lines {@link RsPlugin} logs when it refuses to enable or a hook throws. */
class RsPluginTest {

    @Test
    void handshakeLineNamesBothLevelsAndWhereToUpdate() {
        assertEquals("Requires RsLib API level 3 (installed: 2). Update RsLib.jar (restart required):"
                + " https://rsdevelopment.dev/plugins/rslib", RsPlugin.handshakeMessage(3, 2));
    }

    @Test
    void throwAfterDisablingItselfIsOneLineWithoutTrace() {
        Captured log = new Captured();
        AtomicInteger disabled = new AtomicInteger();
        RuntimeException refusal = new IllegalStateException("license refused");
        RsPlugin.hookFailed(log.logger, "onInnerEnable", refusal, false, disabled::incrementAndGet);
        assertEquals(List.of("Enable aborted: license refused"), log.messages(Level.SEVERE));
        assertEquals(0, disabled.get());
        // The trace is kept at FINE only.
        assertSame(refusal, log.records.stream().filter(r -> r.getLevel() == Level.FINE).findFirst()
                .orElseThrow().getThrown());
    }

    @Test
    void throwWithoutDisablingIsSevereWithTraceAndDisables() {
        for (String hook : List.of("onInnerEnable", "onPreEnable")) {
            Captured log = new Captured();
            AtomicInteger disabled = new AtomicInteger();
            RuntimeException boom = new IllegalArgumentException("boom");
            RsPlugin.hookFailed(log.logger, hook, boom, true, disabled::incrementAndGet);
            assertEquals(List.of(hook + " failed"), log.messages(Level.SEVERE));
            assertSame(boom, log.records.get(0).getThrown());
            assertEquals(1, disabled.get());
        }
    }

    @Test
    void reasonFallsBackToTheExceptionName() {
        Captured log = new Captured();
        RsPlugin.hookFailed(log.logger, "onPreEnable", new IllegalStateException(), false, () -> { });
        assertEquals(List.of("Enable aborted: IllegalStateException"), log.messages(Level.SEVERE));
    }
}
