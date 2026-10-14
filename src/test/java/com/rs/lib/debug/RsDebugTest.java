package com.rs.lib.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.debug.RsDebug.Level;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlTestSupport;
import com.rs.lib.yml.YmlTestSupport.FakeHost;

class RsDebugTest {

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();

    private RsDebug memory() {
        return new RsDebug("Rs", null, lines::add);
    }

    @Test
    void defaultsAreOffAtDebugAndNothingPrints() {
        RsDebug debug = memory();
        assertFalse(debug.enabled());
        assertEquals(Level.DEBUG, debug.level());
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> counted = () -> "x" + calls.incrementAndGet();
        debug.info(counted);
        debug.log(counted);
        debug.log("cat", counted);
        debug.trace(counted);
        assertEquals(0, calls.get());
        assertTrue(lines.isEmpty());
    }

    @Test
    void levelsGateEachChannelWithPrefixes() {
        RsDebug debug = memory();
        assertTrue(debug.toggle());
        debug.info("i");
        debug.log("d");
        debug.trace(() -> "t");
        assertEquals(List.of("[Rs][INFO] i", "[Rs][DEBUG] d"), lines);
        lines.clear();
        debug.setLevel(Level.TRACE);
        assertTrue(debug.tracing());
        debug.trace("Net", () -> "t");
        debug.log("Net", () -> "d");
        assertEquals(List.of("[Rs][TRACE] [net] t", "[Rs][DEBUG] [net] d"), lines);
        lines.clear();
        debug.setLevel(Level.INFO);
        assertFalse(debug.enabled());
        debug.info(() -> "i");
        debug.log("d");
        assertEquals(List.of("[Rs][INFO] i"), lines);
        lines.clear();
        debug.setLevel(Level.OFF);
        debug.info("i");
        assertTrue(lines.isEmpty());
    }

    @Test
    void categoryFilter() {
        RsDebug debug = memory();
        debug.toggle();
        assertTrue(debug.enabled("anything"));
        assertTrue(debug.toggle(" Shop "));
        assertTrue(debug.enabled("shop"));
        assertFalse(debug.enabled("db"));
        AtomicInteger calls = new AtomicInteger();
        debug.log("db", () -> "no" + calls.incrementAndGet());
        debug.log("SHOP", () -> "yes");
        debug.log("plain ignores the filter");
        assertEquals(0, calls.get());
        assertEquals(List.of("[Rs][DEBUG] [shop] yes", "[Rs][DEBUG] plain ignores the filter"), lines);
        assertFalse(debug.toggle("shop"));
        assertTrue(debug.enabled("db"));
    }

    @Test
    void readsAndPersistsTheConfig() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "other: 1\ndebug:\n  enabled: true\n  level: off\n  categories:\n  - Zeta\n  - alpha\n");
        FakeHost host = new FakeHost();
        RsYml config = YmlTestSupport.open(file.toFile(), host);
        RsDebug debug = new RsDebug("Rs", config, lines::add);
        assertEquals(Level.OFF, debug.level());
        assertEquals(Set.of("zeta", "alpha"), debug.categories());
        debug.setLevel(Level.TRACE);
        debug.toggle("beta");
        assertTrue(debug.enabled("beta"));
        host.runTasks();
        assertEquals("other: 1\ndebug:\n  enabled: true\n  level: TRACE\n  categories:\n  - alpha\n  - beta\n  - zeta\n",
                Files.readString(file));
        debug.toggle();
        debug.setLevel(Level.OFF);
        host.runTasks();
        RsDebug reread = new RsDebug("Rs", YmlTestSupport.open(file.toFile(), new FakeHost()), lines::add);
        assertFalse(reread.enabled());
        assertEquals(Level.OFF, reread.level());
        assertTrue(Files.readString(file).contains("level: 'OFF'"), Files.readString(file));
    }

    @Test
    void failingSupplierDoesNotThrow() {
        RsDebug debug = memory();
        debug.toggle();
        debug.log(() -> {
            throw new IllegalStateException("bad");
        });
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("[Rs][DEBUG] (debug message failed"));
    }
}
