package com.rs.lib.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.lang.RsLang.Entry;
import com.rs.lib.lang.RsLang.Line;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.YmlTestSupport.Captured;

class RsLangStaticTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void prefixGoesAfterLeadingTags() {
        assertEquals("[center][small]P text", RsText.insertPrefix("[center][small]text", "P "));
        assertEquals("P text", RsText.insertPrefix("text", "P "));
        assertEquals("[CENTER]P a [center]", RsText.insertPrefix("[CENTER]a [center]", "P "));
        assertEquals("[center][noprefix]x", RsText.insertPrefix("[center][noprefix]x", "P "));
        assertEquals("P [x]y", RsText.insertPrefix("[x]y", "P "));
    }

    /** RsLib's own /rslib answers (rslib.admin) are not copied into the plugins' message files. */
    @Test
    void theSharedLinesLeaveRsLibsOwnBlockOut() throws Exception {
        for (String code : List.of("en", "es")) {
            List<String> lines = LangLoader.sharedLines(code, LOG);
            org.bukkit.configuration.file.YamlConfiguration shared = new org.bukkit.configuration.file.YamlConfiguration();
            shared.loadFromString(String.join("\n", lines));
            org.bukkit.configuration.file.YamlConfiguration full = new org.bukkit.configuration.file.YamlConfiguration();
            try (InputStream in = RsLang.class.getResourceAsStream("/rslib-messages_" + code + ".yml")) {
                full.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            assertTrue(full.isConfigurationSection("rslib.admin"), code);
            assertFalse(shared.contains("rslib.admin"), code);
            assertFalse(String.join("\n", lines).contains("/rslib settings, logs"), code + ": its comment too");
            java.util.Set<String> expected = new java.util.HashSet<>(full.getKeys(true));
            expected.removeIf(key -> key.equals("rslib.admin") || key.startsWith("rslib.admin."));
            assertEquals(expected, shared.getKeys(true), code);
        }
    }

    @Test
    void legacyBlockIsRenamedOnceKeepingTexts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages_en.yml");
        Files.writeString(file, "prefix: \"&7\"\nsnlib:\n  no-permission: \"&cNope\"\nother:\n  snlib: x\n",
                StandardCharsets.UTF_8);
        LangLoader.renameLegacyBlock(file.toFile(), LOG);
        assertEquals("prefix: \"&7\"\nrslib:\n  no-permission: \"&cNope\"\nother:\n  snlib: x\n",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void existingSharedBlockWins(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages_en.yml");
        String text = "rslib:\n  a: b\nsnlib:\n  a: c\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        LangLoader.renameLegacyBlock(file.toFile(), LOG);
        assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
        LangLoader.renameLegacyBlock(new File(dir.toFile(), "absent.yml"), LOG);
    }

    @Test
    void parseErrorFlagsOnlyBrokenFiles(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.yml");
        Path bad = dir.resolve("bad.yml");
        Files.writeString(good, "prefix: \"&7\"\nhello: \"Hi\"\n", StandardCharsets.UTF_8);
        Files.writeString(bad, "hello: \"Hi\n  broken: [\n", StandardCharsets.UTF_8);
        assertNull(LangLoader.parseError(good.toFile()));
        assertNull(LangLoader.parseError(dir.resolve("missing.yml").toFile()));
        String error = LangLoader.parseError(bad.toFile());
        assertNotNull(error);
        assertEquals(-1, error.indexOf('\n'));
    }

    @Test
    void heldOnlyForAPositiveDuration() {
        assertFalse(RsLang.held(null));
        assertFalse(RsLang.held(Duration.ZERO));
        assertFalse(RsLang.held(Duration.ofSeconds(-1)));
        assertTrue(RsLang.held(Duration.ofMillis(1)));
    }

    private static Map<String, Entry> live(String... pairs) {
        Map<String, Entry> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], new Entry(false, List.of(Line.of(pairs[i + 1], null))));
        }
        return out;
    }

    private static Function<String, InputStream> jar(Map<String, String> files) {
        return path -> files.containsKey(path)
                ? new ByteArrayInputStream(files.get(path).getBytes(StandardCharsets.UTF_8)) : null;
    }

    @Test
    void lostClickAndHoverAreWarnedOnceNamingEveryKey() {
        String en = """
                plain: "Hi"
                shop:
                  buy: "<click:run_command:/buy>[Buy]</click>"
                  info: "<hover:show_text:'x'>[?]</hover>"
                  both: "<click:run_command:/a><hover:show_text:'y'>[A]</hover></click>"
                  kept: "<click:run_command:/k>[K]</click>"
                body:
                  - "line"
                  - "<click:open_url:'https://x'>[Site]</click>"
                gone: "<click:run_command:/g>[G]</click>"
                """;
        Captured log = new Captured();
        Map<String, Entry> active = live("plain", "Hi", "shop.buy", "[Buy]", "shop.info", "[?]",
                "shop.both", "<click:run_command:/a>[A]</click>", "shop.kept", "<CLICK:run_command:/k>[K]</click>");
        active.put("body", new Entry(true, List.of(Line.of("line", null), Line.of("[Site]", null))));
        LangLoader.warnLostTags(log.logger, jar(Map.of("lang/messages_en.yml", en)), "en", active, Map.of());
        assertEquals(List.of("4 message key(s) in lang/messages_en.yml lost the <click>/<hover> tag their jar"
                + " default carries (shop.buy, shop.info, shop.both, body); the button still renders but clicking it"
                + " does nothing - restore the tags in those values"), log.warnings());
    }

    private static String lostLine(int count, String file, String keys) {
        return count + " message key(s) in " + file + " lost the <click>/<hover> tag their jar default carries ("
                + keys + "); the button still renders but clicking it does nothing - restore the tags in those values";
    }

    @Test
    void prefixTokenIsWarnedOncePerFileWithItsCount() {
        Captured log = new Captured();
        Map<String, Entry> active = live("a", "{prefix} hi", "b", "&7{prefix}x", "c", "plain", "extra.d", "{prefix}");
        LangLoader.warnPrefixToken(log.logger, "lang/messages_en.yml", active,
                Map.of("extra.d", "addons/extra/messages_en.yml"));
        String tail = " embed the literal {prefix} token; RsLib prepends the configured prefix automatically, so the"
                + " token renders literally - remove it from those values";
        assertEquals(List.of("2 message key(s) in lang/messages_en.yml" + tail,
                "1 message key(s) in addons/extra/messages_en.yml" + tail), log.warnings());

        log.clear();
        LangLoader.warnPrefixToken(log.logger, "lang/messages_en.yml", live("a", "hi"), Map.of());
        assertTrue(log.warnings().isEmpty(), log.warnings().toString());
    }

    @Test
    void lostTagsUseTheJarCopyOfTheLanguageOrElseEnglish() {
        Captured log = new Captured();
        Map<String, String> files = Map.of("lang/messages_en.yml", "a: \"<click:run_command:/a>A</click>\"\n",
                "lang/messages_es.yml", "b: \"<hover:show_text:'b'>B</hover>\"\n");
        LangLoader.warnLostTags(log.logger, jar(files), "es", live("a", "A", "b", "B"), Map.of());
        assertEquals(List.of(lostLine(1, "lang/messages_es.yml", "b")), log.warnings());

        log.clear();
        LangLoader.warnLostTags(log.logger, jar(files), "fr", Map.of(), live("a", "A"));
        assertEquals(List.of(lostLine(1, "lang/messages_en.yml", "a")), log.warnings());

        // One line per file: the translation's own value and an English fallback value.
        log.clear();
        Map<String, String> both = Map.of("lang/messages_es.yml", "a: \"<click:run_command:/a>A</click>\"\n"
                + "b: \"<hover:show_text:'b'>B</hover>\"\n");
        LangLoader.warnLostTags(log.logger, jar(both), "es", live("b", "B"), live("a", "A"));
        assertEquals(List.of(lostLine(1, "lang/messages_en.yml", "a"), lostLine(1, "lang/messages_es.yml", "b")),
                log.warnings());

        log.clear();
        LangLoader.warnLostTags(log.logger, jar(files), "en", live("a", "<click:run_command:/a>A</click>"), Map.of());
        LangLoader.warnLostTags(log.logger, jar(Map.of()), "en", live("a", "A"), Map.of());
        LangLoader.warnLostTags(log.logger, jar(Map.of("lang/messages_en.yml", "a: [broken")), "en",
                live("a", "A"), Map.of());
        assertTrue(log.warnings().isEmpty(), log.warnings().toString());
    }
}
