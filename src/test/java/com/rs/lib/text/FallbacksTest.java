package com.rs.lib.text;

import static com.rs.lib.text.Renders.assertSameRender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

/** {@code %name|text%} and {@code {name|text}}: local values, the next stage, the render and the cache. */
class FallbacksTest {

    private static String plain(String s) {
        return RsText.plain(s);
    }

    // ---------------------------------------------------------------- the token

    @Test
    void theKeyIsBeforeTheFirstBarAndHasNoSpaces() {
        assertEquals(4, Fallbacks.split("clan|Sin clan"));
        assertEquals(12, Fallbacks.split("amount:short|-"));
        assertEquals(1, Fallbacks.split("x|a|b"));
        assertEquals(1, Fallbacks.split("x|"));
        assertEquals(-1, Fallbacks.split("|x"));
        assertEquals(-1, Fallbacks.split("no bar"));
        assertEquals(-1, Fallbacks.split(" off | 20"));
        assertEquals(-1, Fallbacks.split("a b|c"));
    }

    // ---------------------------------------------------------------- local placeholders

    @Test
    void aValueWinsAndAnEmptyValueGivesTheFallback() {
        assertEquals("Clan: Lions", RsText.applyLocals("Clan: {clan|Sin clan}", Ph.of("clan", "Lions")));
        assertEquals("Clan: Sin clan", RsText.applyLocals("Clan: {clan|Sin clan}", Ph.of("clan", "")));
        assertEquals("Clan: Sin clan", RsText.applyLocals("Clan: %clan|Sin clan%", Ph.of("clan", "")));
        assertEquals("Clan: ", RsText.applyLocals("Clan: {clan|}", Ph.of("clan", "")));
        // A blank value is a value.
        assertEquals("Clan:  ", RsText.applyLocals("Clan: {clan|-}", Ph.of("clan", " ")));
    }

    @Test
    void aMissingValueLeavesTheTokenForTheNextStage() {
        assertEquals("Clan: %clan_name|Sin clan%",
                RsText.applyLocals("Clan: %clan_name|Sin clan%", Ph.of("other", "x")));
        assertEquals("{a|x} b", RsText.applyLocals("{a|x} {b}", Ph.of("b", "b")));
        // A second pass (RsYml applies its own placeholders, then the call's) still finds it.
        String first = RsText.applyLocals("{a|x} {b|y}", Ph.of("b", "B"));
        assertEquals("A B", RsText.applyLocals(first, Ph.of("a", "A")));
    }

    @Test
    void formatsComeBeforeTheFallback() {
        assertEquals("1.5K", RsText.applyLocals("{amount:short|-}", Ph.of("amount", "1500")));
        assertEquals("1,500", RsText.applyLocals("%amount:grouped|0%", Ph.of("amount", "1500")));
        assertEquals("-", RsText.applyLocals("{amount:short|-}", Ph.of("amount", "")));
        assertEquals("abc", RsText.applyLocals("{amount:short|-}", Ph.of("amount", "abc")));
        assertEquals("{amount:short|-}", RsText.applyLocals("{amount:short|-}", Ph.of("x", "1")));
    }

    @Test
    void aKeyThatReallyHoldsABarResolvesAsAWhole() {
        Map<String, String> values = new HashMap<>();
        values.put("a|b", "whole");
        values.put("a", "part");
        assertEquals("whole", RsText.applyLocals("{a|b}", values::get));
    }

    @Test
    void literalValuesStayLiteralAndTheFallbackIsText() {
        assertEquals("%x|y%", RsText.plain(RsText.applyLocals("{name|-}", Ph.literal("name", "%x|y%"))));
        assertEquals("Nobody", plain(RsText.applyLocals("{name|&cNobody}", Ph.literal("name", ""))));
        // The fallback is not scanned again.
        assertEquals("{other}", RsText.applyLocals("{name|{other}}", Ph.of("name", ""), Ph.of("other", "O")));
    }

    @Test
    void barsElsewhereStayLiteral() {
        Ph[] phs = {Ph.of("a", "A"), Ph.of("b", "B")};
        for (String text : List.of("a | b", "x || y", "50% | 60%", "{a} | {b}", "%a% | %b%", "50% off | 20% more",
                "{ a|b }", "|", "%|%", "{|}", "|{a}|")) {
            String locals = RsText.applyLocals(text, phs);
            assertEquals(locals, RsText.applyFallbacks(locals), text);
        }
        assertEquals("A | B", plain(RsText.applyLocals("{a} | {b}", phs)));
        assertEquals("50% off | 20% more", plain("50% off | 20% more"));
    }

    // ---------------------------------------------------------------- the end of the pipeline

    @Test
    void theRenderWritesTheFallbacksNothingResolved() {
        assertEquals("Clan: Sin clan", plain("Clan: %clan_name|Sin clan%"));
        assertEquals("Rank: none", plain("Rank: {rank|none}"));
        assertEquals("a  b", plain("a {x|} b"));
        assertEquals("Red", plain("{x|&cRed}"));
        assertEquals("Clan: Sin clan", RsText.plain(RsText.applyFallbacks("Clan: %clan_name|Sin clan%")));
        assertEquals("§cRed", RsText.colorLegacy("{x|&cRed}"));
        assertEquals("Sin clan", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(RsText.colorUncached("%clan_name|Sin clan%")));
    }

    @Test
    void applyFallbacksKeepsTheSameInstanceWithoutTokens() {
        String text = "nothing | here";
        assertSame(text, RsText.applyFallbacks(text));
        assertNull(RsText.applyFallbacks(null));
        assertEquals("b and d", RsText.applyFallbacks("%a|b% and {c|d}"));
    }

    @Test
    void theCacheKeepsTheLineAsWrittenAndItsRender() {
        RsText.clearCache();
        String line = "&aClan: {clan|none}";
        assertEquals("Clan: none", plain(line));
        assertTrue(RenderCache.contains(line));
        assertEquals("Clan: none", plain(line));
        // The filled line is another line, rendered on its own.
        String filled = RsText.applyLocals(line, Ph.of("clan", "Lions"));
        assertEquals("Clan: Lions", plain(filled));
        assertEquals("Clan: none", plain(line));
    }

    @Test
    void resolveFallbacksAsksForEachKeyAndPassesTheRestOn() {
        List<String> asked = new ArrayList<>();
        Function<String, String> value = key -> {
            asked.add(key);
            return switch (key) {
                case "clan_name" -> "Lions";
                case "clan_tag" -> "";
                default -> null;
            };
        };
        String out = RsText.resolveFallbacks("%clan_name|-% [%clan_tag|?%] %x|none% {y|z} %player%",
                part -> part.toUpperCase(), value);
        assertEquals("Lions [?] none {Y|Z} %PLAYER%", out);
        assertEquals(List.of("clan_name", "clan_tag", "x"), asked);
        assertNull(RsText.resolveFallbacks("%player% | x", null, value));
        assertNull(RsText.resolveFallbacks("no tokens", null, value));
    }

    // ---------------------------------------------------------------- templates

    @Test
    void templatesEqualTheSlowPathWithFallbacks() {
        String[] lines = {"&aClan: {clan|Sin clan}", "{a|x} and {b|y}", "&7%clan_name|none% &f{name}",
            "{amount:short|-} coins", "<red>{name|nobody}</red>", "{x{name}|z}", "%x{name}|y%", "{a}|{b}",
            "{name|&cNobody} &7{a|}", "%c{name}|d% {empty|e}"};
        Ph[][] sets = {
            {Ph.of("clan", "Lions"), Ph.of("a", "A"), Ph.of("name", "Steve"), Ph.of("amount", "1500")},
            {Ph.of("clan", ""), Ph.of("a", ""), Ph.of("b", "B"), Ph.of("name", "a b"), Ph.of("amount", "")},
            {Ph.of("name", ""), Ph.of("empty", "")},
            {Ph.literal("name", "x|y"), Ph.of("a", "%q|r%")},
            {},
        };
        for (String line : lines) {
            RsText.Template template = RsText.compile(line);
            for (Ph[] phs : sets) {
                assertSameRender(RsText.color(RsText.applyLocals(line, phs)), template.render(phs),
                        () -> line + " with " + List.of(phs));
            }
        }
    }

    @Test
    void aValueInTheKeyOfAFallbackTokenKeepsTheFastPathAway() {
        String sentinel = String.valueOf(Markup.SENTINEL_FIRST);
        TemplateEngine engine = new TemplateEngine("%x{v}|y%");
        assertNull(engine.fast("%x" + sentinel + "|y%", List.of("ab")));
        assertNull(engine.fast("%x" + sentinel + "|y%", List.of("a b")));
        assertEquals("%xa b|y%", RsText.plain(RsText.applyLocals("%x{v}|y%", Ph.of("v", "a b"))));
        assertEquals("y", RsText.plain(RsText.applyLocals("%x{v}|y%", Ph.of("v", "ab"))));
        // A value in the fallback keeps it.
        RsText.Template template = RsText.compile("%x|{v}%");
        assertSameRender(RsText.color("b"), template.render(Ph.of("v", "b")));
        assertSameRender(RsText.color(RsText.applyLocals("%x|{v}%", Ph.of("v", "b"))), template.render(Ph.of("v", "b")));
    }
}
