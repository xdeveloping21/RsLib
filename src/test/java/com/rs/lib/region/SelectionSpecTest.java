package com.rs.lib.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlTestSupport;

/** Spec defaults, limits, particles and the config form. */
class SelectionSpecTest {

    @TempDir
    Path dir;

    @Test
    void defaultsAreTheDocumentedExample() {
        SelectionSpec spec = SelectionSpec.builder(" arena ").build();
        assertEquals("arena", spec.id());
        assertEquals("", spec.permission());
        assertEquals("DUST", spec.particleName());
        assertEquals(255, spec.dustRed());
        assertEquals(140, spec.dustGreen());
        assertEquals(0, spec.dustBlue());
        assertEquals(1.2f, spec.dustSize());
        assertEquals(0.5, spec.step());
        assertEquals(5, spec.refreshIntervalTicks());
        assertEquals(64, spec.renderDistance());
        assertEquals(SelectionSpec.Visibility.OWNER_ONLY, spec.visibility());
        assertEquals(2000, spec.particleBudget());
        assertEquals(250_000, spec.maxRenderVolume());
        assertEquals(0, spec.maxVolume());
        assertEquals(0, spec.timeoutTicks());
        assertFalse(spec.silent());
        assertTrue(spec.completeEnds());
        assertNull(spec.wandItem());
        assertEquals("default", SelectionSpec.builder(" ").build().id(), "a blank id falls back to default");
        assertEquals("default", SelectionSpec.builder(null).build().id());
    }

    @Test
    void limitsAreKept() {
        SelectionSpec spec = SelectionSpec.builder("a").step(0.01).refreshIntervalTicks(0).particleBudget(-5)
                .maxVolume(-1).timeoutTicks(-3).dustColor(300, -1, 7).dustSize(-2).renderDistance(-4).build();
        assertEquals(0.1, spec.step());
        assertEquals(1, spec.refreshIntervalTicks());
        assertEquals(1, spec.particleBudget());
        assertEquals(0, spec.maxVolume());
        assertEquals(0, spec.timeoutTicks());
        assertEquals(255, spec.dustRed());
        assertEquals(0, spec.dustGreen());
        assertEquals(7, spec.dustBlue());
        assertEquals(0.1f, spec.dustSize(), "dust size is kept between 0.1 and 4.0");
        assertEquals(4.0f, SelectionSpec.builder("a").dustSize(9).build().dustSize());
        assertEquals(1.2f, SelectionSpec.builder("a").dustSize(Float.NaN).build().dustSize());
        assertEquals(0, spec.renderDistance());
    }

    @Test
    void particlesByNameWithTheOldDustNameAndAFlameFallback() {
        List<String> warnings = new ArrayList<>();
        Consumer<String> warn = warnings::add;
        assertSame(Particle.DUST, SelectionSpec.resolveParticle("redstone", warn));
        assertSame(Particle.DUST, SelectionSpec.resolveParticle("minecraft:dust", warn));
        assertSame(Particle.FLAME, SelectionSpec.resolveParticle("flame", warn));
        assertTrue(warnings.isEmpty());
        assertSame(Particle.FLAME, SelectionSpec.resolveParticle("sparkles", warn));
        assertSame(Particle.FLAME, SelectionSpec.resolveParticle("block", warn), "needs block data");
        assertSame(Particle.FLAME, SelectionSpec.resolveParticle(null, warn));
        assertEquals(3, warnings.size());
        SelectionSpec flame = SelectionSpec.builder("a").particle(Particle.FLAME).build();
        assertNull(flame.particleData());
        assertTrue(SelectionSpec.builder("a").build().particleData() instanceof Particle.DustOptions);
    }

    @Test
    void coloursAsNumbersOrHex() {
        assertArrayEquals(new int[] {255, 140, 0}, SelectionSpec.parseColor("255, 140, 0"));
        assertArrayEquals(new int[] {255, 140, 0}, SelectionSpec.parseColor("#FF8C00"));
        assertArrayEquals(new int[] {0, 16, 32}, SelectionSpec.parseColor(" 001020 "));
        assertNull(SelectionSpec.parseColor("256, 0, 0"));
        assertNull(SelectionSpec.parseColor("1, 2"));
        assertNull(SelectionSpec.parseColor("1, 2, 3, 4"));
        assertNull(SelectionSpec.parseColor("#FFF"));
        assertNull(SelectionSpec.parseColor("orange"));
        SelectionSpec kept = SelectionSpec.builder("a").dustColor("orange").build();
        assertEquals(140, kept.dustGreen(), "a bad colour keeps the previous one");
    }

    @Test
    void toBuilderKeepsEverythingIncludingCallbacks() {
        List<String> calls = new ArrayList<>();
        SelectionSpec spec = SelectionSpec.builder("a").maxVolume(10).silent(true)
                .onSelect(box -> calls.add("select")).build();
        SelectionSpec copy = spec.toBuilder().timeoutTicks(40).build();
        assertEquals(10, copy.maxVolume());
        assertTrue(copy.silent());
        assertEquals(40, copy.timeoutTicks());
        assertEquals(0, spec.timeoutTicks(), "the original is unchanged");
        copy.onSelect().accept(null);
        assertEquals(List.of("select"), calls);
    }

    @Test
    void readsTheConfigForm() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                selection-wand:
                  item: {material: GOLDEN_AXE, display-name: "&6&lRegion Wand"}
                  permission: "arena.wand"
                  particle: {type: REDSTONE, color: "#00FF00", size: 2}
                  step: 0.05
                  interval-ticks: 10
                  render-distance: 32
                  visibility: world
                  particle-budget: 500
                  max-render-volume: 1000
                  max-volume: 64
                  timeout-ticks: 600
                  silent: true
                other:
                  particle: heart
                """);
        YmlTestSupport.FakeHost host = new YmlTestSupport.FakeHost();
        RsYml yml = YmlTestSupport.open(file.toFile(), host);
        SelectionSpec spec = SelectionSpec.fromConfig(null, yml, "selection-wand", "arena");
        assertEquals("arena", spec.id());
        assertEquals("arena.wand", spec.permission());
        assertEquals("DUST", spec.particleName());
        assertEquals(0, spec.dustRed());
        assertEquals(255, spec.dustGreen());
        assertEquals(2f, spec.dustSize());
        assertEquals(0.1, spec.step(), "at least 0.1");
        assertEquals(10, spec.refreshIntervalTicks());
        assertEquals(32, spec.renderDistance());
        assertEquals(SelectionSpec.Visibility.WORLD, spec.visibility());
        assertEquals(500, spec.particleBudget());
        assertEquals(1000, spec.maxRenderVolume());
        assertEquals(64, spec.maxVolume());
        assertEquals(600, spec.timeoutTicks());
        assertTrue(spec.silent());

        assertEquals("HEART", SelectionSpec.fromConfig(null, yml, "other", "b").particleName());
        SelectionSpec missing = SelectionSpec.fromConfig(null, yml, "nowhere", "c");
        assertEquals("DUST", missing.particleName());
        assertEquals(0.5, missing.step());
    }
}
