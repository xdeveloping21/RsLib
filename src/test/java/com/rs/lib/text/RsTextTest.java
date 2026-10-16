package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** The text pipeline: formats users write (study 7.4) and the traps of 7.6. */
class RsTextTest {

    private static final TextColor FIRST = TextColor.color(0xF300F3);
    private static final TextColor LAST = TextColor.color(0xFF5327);

    // ------------------------------------------------------------------ 7.4 formats

    @Test
    void legacyColorTurnsOffLegacyBold() {
        Component c = RsText.color("&a&lVerde negrita &cya no negrita");
        assertTrue(Styled.bold(c, "Verde negrita"));
        assertEquals(NamedTextColor.GREEN, Styled.color(c, "Verde negrita"));
        assertFalse(Styled.bold(c, "ya no negrita"));
        assertEquals(NamedTextColor.RED, Styled.color(c, "ya no negrita"));
    }

    @Test
    void miniMessageAndLegacyTogether() {
        Component c = RsText.color("<gradient:#ff0000:#0000ff>MiniMessage</gradient> &7y legacy juntos");
        assertEquals("MiniMessage y legacy juntos", RsText.plain("<gradient:#ff0000:#0000ff>MiniMessage</gradient> &7y legacy juntos"));
        assertEquals(TextColor.color(0xFF0000), Styled.color(c, "M"));
        assertEquals(NamedTextColor.GRAY, Styled.color(c, "y legacy juntos"));
    }

    @Test
    void centeredGradientTitle() {
        String plain = RsText.plain("[center][rgb]Titulo");
        assertTrue(plain.startsWith(" "), plain);
        assertEquals("Titulo", plain.trim());
        Component c = RsText.color("[center][rgb]Titulo");
        assertEquals(FIRST, Styled.color(c, "T"));
        assertEquals(LAST, Styled.color(c, "o"));
    }

    @Test
    void spansMidLine() {
        String line = "Normal [small]small caps[/small] y [rgb]arcoiris[/rgb] normal otra vez";
        assertEquals("Normal ꜱᴍᴀʟʟ ᴄᴀᴘꜱ y arcoiris normal otra vez",
                RsText.plain(line));
        Component c = RsText.color(line);
        assertEquals(FIRST, Styled.colorExact(c, "a"));
        assertEquals(LAST, Styled.colorExact(c, "s"));
        assertNull(Styled.color(c, " normal otra vez"));
    }

    @Test
    void numberSuggestions() {
        String line = "&7Saldo: &e{balance:short} &8({balance:grouped})";
        assertEquals("&7Saldo: &e1.23M &8(1,234,567)", RsText.applyLocals(line, Ph.of("balance", 1234567)));
        assertEquals("Saldo: 1.5K (1,500)", RsText.plain(RsText.applyLocals(line, Ph.of("balance", "1500"))));
    }

    // ------------------------------------------------------------------ 7.6 traps

    @Test
    void smallCapsRunsBeforeTheGradient() {
        String legacy = RsText.colorLegacy("[rgb][small]abc");
        assertEquals("§x§f§3§0§0§f§3ᴀ"
                + "§x§5§5§f§f§5§5ʙ"
                + "§x§f§f§5§3§2§7ᴄ", legacy);
    }

    @Test
    void centerIsTheLastLegacyStepAndIgnoresCodes() {
        String centered = RsText.applyPrefixTags("[center][rgb]abc");
        String plainCentered = RsText.applyPrefixTags("[center]abc");
        assertEquals(leadingSpaces(plainCentered), leadingSpaces(centered));
        assertTrue(leadingSpaces(plainCentered) > 0);
        assertEquals(leadingSpaces(RsText.applyPrefixTags("[center]x")),
                leadingSpaces(RsText.applyPrefixTags("[center]&a&#FF0000\u00A7cx")));
        assertEquals(leadingSpaces(RsText.applyPrefixTags("[center]x")),
                leadingSpaces(RsText.applyPrefixTags("[center]<green>x</green>")));
    }

    @Test
    void legacyColorResetDoesNotApplyToAuthorTags() {
        Component c = RsText.color("<bold>Bold <red>still bold");
        assertTrue(Styled.bold(c, "still bold"));
        Component legacy = RsText.color("&lBold &cnot bold");
        assertFalse(Styled.bold(legacy, "not bold"));
        Component hex = RsText.color("&lBold &#00FF00not bold");
        assertFalse(Styled.bold(hex, "not bold"));
        assertEquals(TextColor.color(0x00FF00), Styled.color(hex, "not bold"));
        Component again = RsText.color("&a&lA &c&lB");
        assertTrue(Styled.bold(again, "B"));
    }

    @Test
    void rgbUsesTheSevenFixedAnchors() {
        String legacy = RsText.applyPrefixTags("[rgb]abcdefg");
        assertEquals("&#F300F3a&#5555FFb&#55FFFFc&#55FF55d&#FCFF21e&#FF9B00f&#FF5327g", legacy);
        assertEquals("&#F300F3x", RsText.applyPrefixTags("[rgb]x"));
        assertEquals("&#F300F3a &#55FF55b&#FF5327c", RsText.applyPrefixTags("[rgb]a bc"));
    }

    @Test
    void rgbFormatsAccumulateAndColorsClearThem() {
        assertEquals("&#F300F3&lA&#FF5327B", RsText.applyPrefixTags("[rgb]&lA&cB"));
        assertEquals("&#F300F3&l&oA&#FF5327&l&oB", RsText.applyPrefixTags("[rgb]&l&oA&#123456&l&oB"));
        assertEquals("&#F300F3&lA&#FF5327B", RsText.applyPrefixTags("[rgb]&lA&rB"));
    }

    @Test
    void rgbCloseRestoresTheOuterStyle() {
        assertEquals("&a&lX &#F300F3Y&a&l Z", RsText.applyPrefixTags("&a&lX [rgb]Y[/rgb] Z"));
        assertEquals("X &#F300F3Y&r Z", RsText.applyPrefixTags("X [rgb]Y[/rgb] Z"));
        assertEquals("ab", RsText.plain("a[/rgb]b"));
        assertEquals("&lX &#F300F3Y&r&l Z", RsText.applyPrefixTags("&lX [rgb]Y[/rgb] Z"));
    }

    @Test
    void escapeHeuristicForLessThan() {
        assertEquals("a < b", RsText.plain("a < b"));
        assertEquals("<3 you", RsText.plain("<3 you"));
        assertEquals("1<2", RsText.plain("1<2"));
        assertEquals("x <", RsText.plain("x <"));
        assertEquals("<red> escaped", RsText.plain("\\<red> escaped"));
        assertEquals(NamedTextColor.RED, Styled.color(RsText.color("<red>tag"), "tag"));
    }

    @Test
    void applyLocalsDoesNotRescanValues() {
        assertEquals("{b} and X", RsText.applyLocals("{a} and {b}", Ph.of("a", "{b}"), Ph.of("b", "X")));
        assertEquals("%b%", RsText.applyLocals("%a%", Ph.of("a", "%b%"), Ph.of("b", "X")));
    }

    @Test
    void plainUsesTheCacheButCosmeticDoesNot() {
        RsText.clearCache();
        RsText.plain("&aplain-cache-probe");
        assertTrue(RenderCache.contains("&aplain-cache-probe"));
        RsText.cosmetic("&acosmetic-cache-probe");
        assertFalse(RenderCache.contains("&acosmetic-cache-probe"));
    }

    @Test
    void markersSurviveEveryStage() {
        String typed = RsText.literal("<b>&a %x% [rgb] \\ {y}");
        assertEquals("Hi <b>&a %x% [rgb] \\ {y}", RsText.plain("Hi " + typed));
        assertEquals("<b>&a %x% [rgb] \\ {y}", RsText.plain("[rgb]" + typed).trim());
        assertEquals("<b>&a %x% [rgb] \\ {y}", RsText.plain("[center]" + typed).trim());
        assertEquals(RsText.smallCaps("<b>&a %x% [rgb] \\ {y}").length(), RsText.plain("[small]" + typed).length());
        assertTrue(RsText.plain("[small]" + typed).contains("<"));
        assertEquals(typed, RsText.normalizePapiOutput(typed));
        assertEquals(typed, RsText.applyLocals(typed, Ph.of("x", "NO"), Ph.of("y", "NO")));
        assertEquals("<b>&a %x% [rgb] \\ {y}", PlainTextComponentSerializer.plainText().serialize(RsText.cosmetic(typed)));
        Component hover = RsText.color("<hover:show_text:'" + typed + "'>hi");
        HoverEvent<?> event = Styled.styleOf(hover, "hi").hoverEvent();
        assertEquals("<b>&a %x% [rgb] \\ {y}", PlainTextComponentSerializer.plainText().serialize((Component) event.value()));
        Component click = RsText.color("<click:suggest_command:'/msg " + RsText.literal("a&b") + "'>go");
        ClickEvent clickEvent = Styled.styleOf(click, "go").clickEvent();
        assertEquals("/msg a&b", clickValue(clickEvent));
        assertEquals("x <b>", RsText.colorLegacy("x " + RsText.literal("<b>")));
    }

    @Test
    void cacheIsLruNotClearAll() {
        RsText.clearCache();
        for (int i = 0; i < RenderCache.MAX_ENTRIES; i++) {
            RsText.color("line " + i);
        }
        assertEquals(RenderCache.MAX_ENTRIES, RenderCache.size());
        RsText.color("line 0");
        RsText.color("one more line");
        assertEquals(RenderCache.MAX_ENTRIES, RenderCache.size());
        assertTrue(RenderCache.contains("line 0"));
        assertFalse(RenderCache.contains("line 1"));
        assertTrue(RenderCache.contains("one more line"));
        String longLine = "x".repeat(RenderCache.MAX_LINE + 1);
        RsText.color(longLine);
        assertFalse(RenderCache.contains(longLine));
        RsText.clearCache();
        assertEquals(0, RenderCache.size());
    }

    // ------------------------------------------------------------------ literals

    @Test
    void literalMapping() {
        assertEquals("a﷐b﷑c﷒d﷓e﷔f﷕gh",
                RsText.literal("a<b&c%d[e\\f{g§h﷐"));
        assertEquals("a<b&c%d[e\\f{gh", RsText.unwrapLiterals(RsText.literal("a<b&c%d[e\\f{g§h")));
        assertSame("Steve", RsText.literal("Steve"));
        assertEquals("", RsText.literal(null));
        assertNull(RsText.unwrapLiterals(null));
        assertEquals("forged", RsText.plain(RsText.literal("forg﷓﷑ed")));
    }

    // ------------------------------------------------------------------ applyLocals

    @Test
    void applyLocalsTokens() {
        assertEquals("A A", RsText.applyLocals("%a% {a}", Ph.of("a", "A")));
        assertEquals("{x} %y% {} %%", RsText.applyLocals("{x} %y% {} %%", Ph.of("a", "A")));
        assertEquals("2", RsText.applyLocals("{a}", Ph.of("a", "1"), Ph.of("a", "2")));
        assertEquals("100% of Steve", RsText.applyLocals("100% of %player%", Ph.of("player", "Steve")));
        assertEquals("{a Steve", RsText.applyLocals("{a {p}", Ph.of("p", "Steve")));
        assertEquals("﷐b﷐", RsText.applyLocals("{p}", Ph.literal("p", "<b<")));
        assertEquals("null", RsText.applyLocals("{p}", Ph.of("p", null)));
        assertEquals("x", RsText.applyLocals("x", (Ph[]) null));
        assertNull(RsText.applyLocals(null, Ph.of("a", "b")));
        assertEquals("v-k", RsText.applyLocals("{k}", key -> "v-" + key));
        assertEquals("{k}", RsText.applyLocals("{k}", key -> null));
    }

    @Test
    void applyLocalsSuggestions() {
        assertEquals("1.5K", RsText.applyLocals("{n:short}", Ph.of("n", 1500)));
        assertEquals("1.5M", RsText.applyLocals("{n:short}", Ph.of("n", "1.5M")));
        assertEquals("1500000", RsText.applyLocals("{n:raw}", Ph.of("n", "1.5M")));
        assertEquals("1500000", RsText.applyLocals("{n:raw}", Ph.of("n", 1.5E6)));
        assertEquals("1,234.5", RsText.applyLocals("%n:grouped%", Ph.of("n", 1234.5)));
        assertEquals("1.5K", RsText.applyLocals("{n:SHORT}", Ph.of("n", 1500)));
        assertEquals("abc", RsText.applyLocals("{n:short}", Ph.of("n", "abc")));
        assertEquals("{n:bogus}", RsText.applyLocals("{n:bogus}", Ph.of("n", 1500)));
        assertEquals("{m:short}", RsText.applyLocals("{m:short}", Ph.of("n", 1500)));
        assertEquals("full", RsText.applyLocals("{n:short}", Ph.of("n", 1500), Ph.of("n:short", "full")));
        assertEquals("1e308K", RsText.applyLocals("{n:short}", Ph.of("n", "1e308K")));
    }

    // ------------------------------------------------------------------ variants

    @Test
    void cosmeticKeepsOnlyStyleTags() {
        Component c = RsText.cosmetic("<click:run_command:'/op me'>x <red>red &lbold <hover:show_text:'h'>y");
        assertEquals("<click:run_command:'/op me'>x red bold <hover:show_text:'h'>y",
                PlainTextComponentSerializer.plainText().serialize(c));
        assertEquals(NamedTextColor.RED, Styled.color(c, "red"));
        assertTrue(Styled.bold(c, "bold"));
        for (Styled s : Styled.flatten(c)) {
            assertNull(s.style().clickEvent());
            assertNull(s.style().hoverEvent());
        }
        assertEquals(TextColor.color(0xF300F3), Styled.color(RsText.cosmetic("<rainbow>ab</rainbow>[rgb]cd"), "c"));
        assertEquals(Component.empty(), RsText.cosmetic(null));
    }

    @Test
    void miniIsOnlyMiniMessage() {
        assertEquals("&cX [rgb]y", PlainTextComponentSerializer.plainText().serialize(RsText.mini("&cX [rgb]y")));
        assertEquals(NamedTextColor.RED, Styled.color(RsText.mini("<red>X"), "X"));
        assertEquals(Component.empty(), RsText.mini(null));
    }

    @Test
    void sectionAndColorLegacy() {
        assertEquals("§cHi", RsText.section("&cHi"));
        assertEquals("§x§f§f§0§0§0§0Hi", RsText.section("&#FF0000Hi"));
        assertNull(RsText.section(null));
        assertEquals("", RsText.section(""));
        assertEquals("§x§f§f§0§0§a§ax<red>y", RsText.colorLegacy("&#FF00AAx<red>y"));
        assertEquals("§x§f§f§0§0§0§0z", RsText.colorLegacy("&x&F&F&0&0&0&0z"));
        assertEquals("§a§lA & B", RsText.colorLegacy("&A&LA & B"));
        assertEquals("", RsText.colorLegacy(null));
    }

    @Test
    void sectionSignsAreNormalized() {
        assertEquals(RsText.color("&cRed"), RsText.color("§cRed"));
        assertEquals(RsText.color("&#FF0000x"), RsText.color("§x§f§f§0§0§0§0x"));
        assertEquals("&aHi &#FF0000x", RsText.normalizePapiOutput("§aHi §x§f§f§0§0§0§0x"));
        assertEquals("a § b", RsText.plain("a § b"));
        assertNull(RsText.normalizePapiOutput(null));
        assertSame("plain", RsText.normalizePapiOutput("plain"));
    }

    @Test
    void plainVisibleLengthAndLists() {
        assertEquals(3, RsText.visibleLength("&aHé😀"));
        assertNull(RsText.plain(null));
        assertEquals("", RsText.plain(""));
        assertEquals(0, RsText.visibleLength(null));
        assertEquals(0, RsText.visibleLength(""));
        List<Component> empty = RsText.colorList(null);
        empty.add(Component.empty());
        assertEquals(1, empty.size());
        List<String> lines = new ArrayList<>();
        lines.add("&aA");
        lines.add(null);
        List<Component> out = RsText.colorList(lines);
        assertEquals(2, out.size());
        assertEquals(Component.empty(), out.get(1));
        assertEquals(Component.empty(), RsText.color(null));
    }

    @Test
    void prefixTags() {
        assertEquals("&aHi", RsText.applyPrefixTags("[noprefix]&aHi"));
        assertEquals("&aHi", RsText.applyPrefixTags("[NOPREFIX]&aHi"));
        assertEquals("a [noprefix] b", RsText.plain("a [noprefix] b"));
        assertEquals("a [center] b", RsText.plain("a [center] b"));
        assertEquals("&a[center]x", RsText.applyPrefixTags("&a[center]x"));
        assertEquals("ʜɪ", RsText.plain("[small][noprefix]hi"));
        assertEquals("ʜɪ x", RsText.plain("[small]hi[/small] x"));
        assertNull(RsText.applyPrefixTags(null));
    }

    @Test
    void hoverTextGetsLegacyCodes() {
        Component c = RsText.color("<hover:show_text:'&7Info &lbold'>x");
        Component shown = (Component) Styled.styleOf(c, "x").hoverEvent().value();
        assertEquals(NamedTextColor.GRAY, Styled.color(shown, "Info"));
        assertTrue(Styled.bold(shown, "bold"));
        Component url = RsText.color("<click:open_url:'https://x.com/?a=1&b=2'>link");
        assertEquals("https://x.com/?a=1&b=2",
                clickValue(Styled.styleOf(url, "link").clickEvent()));
    }

    @Test
    void smallCapsHelper() {
        assertEquals("ʜᴇʟʟᴏ &aᴡᴏʀʟᴅ <red>x</red> Ñᴀɴᴅᴜ",
                RsText.smallCaps("Hello &aWorld <red>x</red> Ñandú"));
        String same = "123 &a <bold>";
        assertSame(same, RsText.smallCaps(same));
        assertEquals("ǫx", RsText.smallCaps("qx"));
        assertEquals("<hover:show_text:'Keep'>ᴋ", RsText.smallCaps("<hover:show_text:'Keep'>k"));
        assertEquals("§x§f§f§0§0§0§0ᴀ", RsText.smallCaps("§x§f§f§0§0§0§0a"));
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** The click text through the form every supported Adventure version has (4.16 to 4.26). */
    @SuppressWarnings("deprecation")
    private static String clickValue(ClickEvent event) {
        return event.value();
    }
}
