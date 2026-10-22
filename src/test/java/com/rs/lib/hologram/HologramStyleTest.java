package com.rs.lib.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

class HologramStyleTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void defaultIsTheLookHologramsAlwaysHad() {
        HologramStyle d = HologramStyle.DEFAULT;
        assertEquals(Display.Billboard.CENTER, d.billboard());
        assertNull(d.background());
        assertEquals(TextDisplay.TextAlignment.CENTER, d.alignment());
        assertEquals(200, d.lineWidth());
        assertEquals(255, d.textOpacity());
        assertEquals(1f, d.scale());
        assertEquals(0f, d.viewRange());
    }

    @Test
    void readsEveryKey() throws Exception {
        YamlConfiguration yaml = yaml("""
                holo:
                  billboard: fixed
                  shadow: true
                  see-through: true
                  background: "#80FF0000"
                  alignment: left
                  line-width: 120
                  text-opacity: 128
                  scale: 1.5
                  view-range: 2
                """);
        List<String> warnings = new ArrayList<>();
        HologramStyle s = HologramStyle.read(yaml.getConfigurationSection("holo"), HologramStyle.DEFAULT, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(Display.Billboard.FIXED, s.billboard());
        assertTrue(s.shadow());
        assertTrue(s.seeThrough());
        assertEquals(0x80FF0000, s.background());
        assertEquals(TextDisplay.TextAlignment.LEFT, s.alignment());
        assertEquals(120, s.lineWidth());
        assertEquals(128, s.textOpacity());
        assertEquals(1.5f, s.scale());
        assertEquals(2f, s.viewRange());
    }

    @Test
    void missingKeysKeepTheFallbackAndANullSectionIsTheFallback() throws Exception {
        HologramStyle fallback = HologramStyle.builder().shadow(true).scale(2f).build();
        assertSame(fallback, HologramStyle.read(null, fallback, null));
        HologramStyle s = HologramStyle.read(yaml("holo:\n  alignment: RIGHT\n").getConfigurationSection("holo"), fallback, null);
        assertTrue(s.shadow());
        assertEquals(2f, s.scale());
        assertEquals(TextDisplay.TextAlignment.RIGHT, s.alignment());
    }

    @Test
    void anInvalidValueKeepsTheFallbackWithOneWarningNamingTheKey() throws Exception {
        YamlConfiguration yaml = yaml("""
                holo:
                  billboard: sideways
                  shadow: maybe
                  background: "#12"
                  line-width: -3
                  text-opacity: 300
                  scale: 0
                  view-range: lots
                """);
        HologramStyle fallback = HologramStyle.builder().billboard(Display.Billboard.VERTICAL).build();
        List<String> warnings = new ArrayList<>();
        HologramStyle s = HologramStyle.read(yaml.getConfigurationSection("holo"), fallback, warnings::add);
        assertEquals(fallback, s);
        assertEquals(7, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("'holo.billboard'"), warnings.get(0));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'holo.view-range'")));
    }

    @Test
    void backgroundForms() {
        assertEquals(0xFF112233, HologramStyle.parseBackground("#112233"));
        assertEquals(0x40112233, HologramStyle.parseBackground("#40112233"));
        assertEquals(0xFF0A141E, HologramStyle.parseBackground("10, 20, 30"));
        assertEquals(0x800A141E, HologramStyle.parseBackground("10,20,30,128"));
        assertEquals(0, HologramStyle.parseBackground("none"));
        assertNull(HologramStyle.parseBackground("default"));
        assertNull(HologramStyle.parseBackground("10, 20"));
        assertNull(HologramStyle.parseBackground("10, 20, 300"));
        assertNull(HologramStyle.parseBackground("#GG0000"));
    }

    @Test
    void defaultAndVanillaKeepTheEntitysBackground() throws Exception {
        HologramStyle withColor = HologramStyle.builder().background(0xFF000000).build();
        HologramStyle s = HologramStyle.read(yaml("holo:\n  background: default\n").getConfigurationSection("holo"), withColor, null);
        assertNull(s.background());
    }
}
