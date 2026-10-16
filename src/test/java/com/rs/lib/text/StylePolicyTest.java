package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import com.rs.lib.text.StylePolicy.Capability;
import com.rs.lib.text.StylePolicy.OnDisallowed;

class StylePolicyTest {

    private static final String YAML = """
            chat-format:
              enabled: true
              allow-legacy-colors: true
              allow-hex: false
              allow-bold: true
              allow-italic: false
              allow-underline: false
              allow-strikethrough: false
              allow-obfuscated: false
              allow-minimessage: false
              allow-gradient: false
              on-disallowed: strip
            """;

    private static StylePolicy fromYaml(String yaml, String path) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return StylePolicy.fromConfig(config, path);
    }

    @Test
    void readsTheConfigKeys() throws InvalidConfigurationException {
        StylePolicy policy = fromYaml(YAML, "chat-format");
        assertTrue(policy.isEnabled());
        assertFalse(policy.isPlainOnly());
        assertEquals(Set.of(Capability.LEGACY_COLOR, Capability.BOLD), policy.allowed());
        assertEquals(OnDisallowed.STRIP, policy.onDisallowed());
        assertTrue(policy.isAllowed(Capability.BOLD));
        assertFalse(policy.isAllowed(Capability.HEX));
    }

    @Test
    void missingSectionAndDefaults() throws InvalidConfigurationException {
        StylePolicy missing = fromYaml("other: 1\n", "chat-format");
        assertTrue(missing.isEnabled());
        assertTrue(missing.isPlainOnly());
        assertEquals(OnDisallowed.REJECT, missing.onDisallowed());
        StylePolicy disabled = fromYaml("s:\n  enabled: false\n  allow-hex: true\n", "s");
        assertFalse(disabled.isEnabled());
        assertTrue(disabled.isPlainOnly());
        assertFalse(disabled.isAllowed(Capability.HEX));
        assertEquals(List.of(Capability.HEX), disabled.violations("&#FF0000x"));
        StylePolicy direct = fromYaml("allow-gradient: true\non-disallowed: REJECT\n", "");
        assertEquals(Set.of(Capability.GRADIENT), direct.allowed());
        assertTrue(StylePolicy.fromConfig(null, "x").isPlainOnly());
    }

    @Test
    void violationsInEnumOrder() throws InvalidConfigurationException {
        StylePolicy policy = fromYaml(YAML, "chat-format");
        String text = "[rgb]z &cHi &lB &#FF0000x <red>x <click:run_command:'/x'>y &o";
        assertEquals(List.of(Capability.HEX, Capability.ITALIC, Capability.MINIMESSAGE, Capability.GRADIENT),
                policy.violations(text));
        assertEquals(List.of(), policy.violations("&cHi &lthere &r<reset>"));
        assertEquals(List.of(Capability.LEGACY_COLOR), StylePolicy.disabled().violations("\u00A7cx"));
        assertEquals(List.of(), StylePolicy.disabled().violations("a < b \\<red> [center][small]"));
        assertTrue(policy.accepts("&a&lok"));
        assertFalse(policy.accepts("<gradient:red:blue>no"));
    }

    @Test
    void nonCosmeticTagsAlwaysViolate() {
        StylePolicy all = StylePolicy.builder().enabled(true).allowAll().build();
        assertEquals(List.of(), all.violations("<red>&a&l<#00FF00><bold>x</bold><!italic><color:gold><gradient:a:b>[rgb]"));
        assertEquals(List.of(Capability.MINIMESSAGE), all.violations("<VIP> name"));
        assertEquals(List.of(Capability.MINIMESSAGE), all.violations("<hover:show_text:'x'>y"));
        assertEquals(List.of(Capability.MINIMESSAGE), all.violations("<click:run_command:'/op'>y"));
        assertEquals(List.of(), all.violations("a < b <3"));
    }

    @Test
    void stripRemovesOnlyWhatIsNotAllowed() throws InvalidConfigurationException {
        StylePolicy policy = fromYaml(YAML, "chat-format");
        assertEquals("&c&lHi x &r[center][small]\\<y z<reset> w",
                policy.strip("&c&l&oHi <red>x</red> &r[center][small]\\<y [rgb]z<reset> <click:run_command:'/op'>w"));
        assertEquals("&c&lHi x", policy.apply("&c&lHi &#FF0000x"));
        assertEquals("&aok", policy.apply("&aok"));
        assertEquals("&aHi", policy.strip("\u00A7aHi"));
    }

    @Test
    void rejectGivesPlainText() {
        StylePolicy reject = StylePolicy.builder().allow(Capability.LEGACY_COLOR).build();
        assertEquals(OnDisallowed.REJECT, reject.onDisallowed());
        assertEquals("Hi there", reject.apply("&c&lHi <red>there"));
        assertEquals("&cok", reject.apply("&cok"));
        assertNull(reject.apply(null));
        assertNull(reject.strip(null));
        assertEquals("", reject.apply(""));
    }

    @Test
    void builderDisallowAndPlainOnly() {
        StylePolicy policy = StylePolicy.builder().allowAll().disallow(Capability.OBFUSCATED, Capability.HEX)
                .onDisallowed(OnDisallowed.STRIP).build();
        assertFalse(policy.isAllowed(Capability.OBFUSCATED));
        assertEquals(List.of(Capability.OBFUSCATED), policy.violations("&kmagic"));
        assertEquals("magic", policy.apply("&kmagic"));
        assertTrue(StylePolicy.builder().build().isPlainOnly());
        assertTrue(StylePolicy.disabled().isPlainOnly());
        assertFalse(StylePolicy.disabled().isEnabled());
    }
}
