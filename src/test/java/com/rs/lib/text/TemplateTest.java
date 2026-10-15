package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.rs.lib.text.Renders.assertSameRender;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

import net.kyori.adventure.text.Component;

/** {@link RsText#compile}: the fast path must always equal {@code color(applyLocals(...))}. */
class TemplateTest {

    /** Template building blocks: codes, tags, tokens and the tricky shapes around them. */
    private static final String[] FRAGMENTS = {
        "Hello ", "x", " ", "  ", "Saldo: ", "a < b", "<3", ">", ":", "'", "\"", "#", "]", "[", "}", "%",
        "{", "\\", "\\<", "\\\\", "&", "&a", "&l", "&o", "&n", "&m", "&k", "&r", "&c&l", "&#FF00AA", "&#12",
        "&x&F&F&0&0&0&0", "&x", "\u00A7c", "\u00A7", "\u00A7x\u00A7f\u00A7f\u00A70\u00A70\u00A70\u00A70",
        "<red>", "</red>", "<bold>", "<!bold>", "<b>", "<reset>", "<#00FF00>", "<color:#123456>",
        "<hover:show_text:'&7Info {name}'>", "<hover:show_text:'hi'>", "</hover>",
        "<click:run_command:'/msg {name}'>", "<click:suggest_command:'/pay '>", "<insert:'{name}'>",
        "<gradient:red:blue>", "</gradient>", "<rainbow>", "[rgb]", "[/rgb]", "[small]", "[/small]",
        "[center]", "[noprefix]", "[NoPrefix]", "[rg", "b]", "<re", "d>", "<lang:block.minecraft.stone>",
        "<newline>", "<key:key.jump>", "<font:uniform>", "<u>", "<st>", "<obf>", "<pre>", "</pre>",
        "{name}", "%name%", "{amount}", "{amount:short}", "{amount:grouped}", "{amount:raw}", "{amount:bogus}",
        "{unknown}", "%player_name%", "{empty}", "{space}", "{lit}", "{weird}", "{fmt}", "{name}{name}",
        "%name%%amount%", "{}", "%%", "{name:short}", "\uFDD0", "\uE001", "\u00E9", "\uD83D\uDE00",
        "<bo", "<color:", "<c:", "<hover:show_text:'", "'>", "<click:run_command:\"/x ", "\">", "<!", "</",
    };

    private static final String[][] VALUE_SETS = {
        {"Steve", "1500", "", " ", "1.5M"},
        {"Alex_99", "999999", "x", "  ", "12.345"},
        {"N\u00F1and\u00FA", "0", "a>b", "x'y", "abc"},
        {"a&b", "<red>x", "[rgb]", "50%", "{x}"},
        {"\\", "\u00A7c", "&#FF0000", "<", "]"},
        {"Notch", "-1234.5", "hello world", "#", ":"},
        {"\uE000\uE001", "1,500", "q", "''", "\"q\""},
        {"d", "red", "ld", "bold>", "#FF0000"},
        {"red>", "gold", "d>x", "ld>y", "x'>"},
    };

    @Test
    void fastPathEqualsSlowPathOnRandomTemplates() {
        Random random = new Random(20261014L);
        int fastHits = 0;
        int runs = 6000;
        for (int run = 0; run < runs; run++) {
            StringBuilder raw = new StringBuilder();
            int parts = 1 + random.nextInt(9);
            for (int k = 0; k < parts; k++) {
                raw.append(FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
            }
            String line = raw.toString();
            String[] set = VALUE_SETS[random.nextInt(VALUE_SETS.length)];
            Ph[] phs = {
                Ph.of("name", set[0]),
                Ph.of("amount", set[1]),
                Ph.of("empty", set[2]),
                Ph.of("space", set[3]),
                Ph.literal("lit", set[4]),
                Ph.of("weird", VALUE_SETS[random.nextInt(VALUE_SETS.length)][random.nextInt(5)]),
                Ph.of("fmt", set[4]),
            };
            RsText.Template template = RsText.compile(line);
            Component expected = RsText.color(RsText.applyLocals(line, phs));
            assertSameRender(expected, template.render(phs), () -> "template: " + line);
            if (fastUsed(line, Locals.resolverOf(phs))) {
                fastHits++;
            }
        }
        assertTrue(fastHits > runs / 10, "the fast path should cover common shapes, hits=" + fastHits);
    }

    @Test
    void fastPathEqualsSlowPathWithResolver() {
        Random random = new Random(7L);
        for (int run = 0; run < 3000; run++) {
            StringBuilder raw = new StringBuilder();
            int parts = 1 + random.nextInt(7);
            for (int k = 0; k < parts; k++) {
                raw.append(FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
            }
            String line = raw.toString();
            String[] set = VALUE_SETS[random.nextInt(VALUE_SETS.length)];
            Map<String, String> values = new HashMap<>();
            values.put("name", set[0]);
            values.put("amount", set[1]);
            values.put("empty", set[2]);
            values.put("player_name", set[3]);
            Function<String, String> resolver = values::get;
            RsText.Template template = RsText.compile(line);
            assertSameRender(RsText.color(RsText.applyLocals(line, resolver)), template.render(resolver),
                    () -> "template: " + line);
            // A second render with other values hits the skeleton cache.
            values.put("name", set[4]);
            assertSameRender(RsText.color(RsText.applyLocals(line, resolver)), template.render(resolver),
                    () -> "second render: " + line);
        }
    }

    @Test
    void commonLangShapesUseTheFastPath() {
        String[] lines = {
            "&7Saldo: &e{balance:short} &8({balance:grouped})",
            "[noprefix]&aCompraste &f{item} &apor &e{price:short}",
            "&a&lVerde {player} &cya no negrita",
            "<gray>Player <yellow>{player}</yellow> joined with <#55FF55>{amount}",
            "<hover:show_text:'&7Click'><click:run_command:'/spawn'>&e{player}</click></hover> &7went home",
            "&#FF00AA{player} &x&F&F&0&0&0&0%amount%",
        };
        Ph[] phs = {Ph.of("balance", "1234567"), Ph.of("item", "Diamond"), Ph.of("price", "1500"),
            Ph.of("player", "Steve"), Ph.of("amount", "42")};
        for (String line : lines) {
            assertTrue(fastUsed(line, Locals.resolverOf(phs)), line);
            assertEquals(RsText.color(RsText.applyLocals(line, phs)), RsText.compile(line).render(phs), line);
        }
    }

    @Test
    void unsafeShapesFallBackToTheSlowPath() {
        Ph[] phs = {Ph.of("c", "red"), Ph.of("p", "Steve"), Ph.of("h", "3456")};
        String[] lines = {
            "<{c}>text",                          // the value would form a tag
            "<gradient:{c}:blue>text</gradient>", // inside tag arguments
            "<hover:show_text:'{p}'>x",           // inside a hover
            "&{c}x",                              // after a code sign
            "&#12{h}x",                           // finishes a hex code
            "[rg{p}]x",                           // inside a bracket tag
            "[rgb]{p}",                           // gradient depends on the value
            "[center]{p}",                        // width depends on the value
            "<rainbow>{p}",                       // per-character colors
        };
        for (String line : lines) {
            assertFalse(fastUsed(line, Locals.resolverOf(phs)), line);
            assertSameRender(RsText.color(RsText.applyLocals(line, phs)), RsText.compile(line).render(phs), line);
        }
    }

    @Test
    void valuesThatAreNotPlainUseTheSlowPath() {
        String line = "&7Hi {p}!";
        for (String value : new String[] {"", "&cRed", "<red>x", "[rgb]x", "50%", "{x}", "a\\b", "\u00A7c"}) {
            assertFalse(TemplateEngine.isPlainValue(value), value);
            assertSameRender(RsText.color(RsText.applyLocals(line, Ph.of("p", value))),
                    RsText.compile(line).render(Ph.of("p", value)), value);
        }
        assertTrue(TemplateEngine.isPlainValue("Steve 123 >:'#"));
    }

    @Test
    void blankResultFallsBack() {
        String line = "<red>{p}</red>x";
        assertNull(engineFast(line, k -> " "));
        assertEquals(RsText.color(RsText.applyLocals(line, Ph.of("p", " "))), RsText.compile(line).render(Ph.of("p", " ")));
        assertNotNull(engineFast(line, k -> "a"));
    }

    @Test
    void literalsAndUnknownTokensMatch() {
        String line = "&e{p} &7says: &f{msg} %papi_token% {missing}";
        Ph[] phs = {Ph.of("p", "Steve"), Ph.literal("msg", "<red>&cno [rgb] {x} 100% \\o/")};
        assertEquals(RsText.color(RsText.applyLocals(line, phs)), RsText.compile(line).render(phs));
        assertEquals("Steve says: <red>&cno [rgb] {x} 100% \\o/ %papi_token% {missing}",
                RsText.plain(RsText.applyLocals(line, phs)));
    }

    @Test
    void nullAndTokenlessTemplates() {
        assertEquals(Component.empty(), RsText.compile(null).render());
        assertNull(RsText.compile(null).raw());
        RsText.Template plain = RsText.compile("&aNo tokens");
        assertEquals("&aNo tokens", plain.raw());
        assertEquals(RsText.color("&aNo tokens"), plain.render(Ph.of("x", "y")));
        assertEquals(RsText.color("{x}"), RsText.compile("{x}").render((Function<String, String>) null));
    }

    // ------------------------------------------------------------------ helpers

    /** Whether the engine took the fast path for this line and resolver. */
    private static boolean fastUsed(String line, Function<String, String> resolver) {
        return engineFast(line, resolver) != null;
    }

    private static Component engineFast(String line, Function<String, String> resolver) {
        TemplateEngine engine = new TemplateEngine(line);
        if (!engine.fastCapable()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        StringBuilder skeleton = new StringBuilder();
        Locals.scan(line, resolver, new Locals.Sink() {
            @Override
            public void text(String s, int from, int to) {
                skeleton.append(s, from, to);
            }

            @Override
            public void value(String value) {
                skeleton.append((char) (Markup.SENTINEL_FIRST + values.size()));
                values.add(value);
            }
        });
        if (values.isEmpty() || values.size() > 26) {
            return null;
        }
        for (String v : values) {
            if (!TemplateEngine.isPlainValue(v)) {
                return null;
            }
        }
        return engine.fast(skeleton.toString(), values);
    }
}
