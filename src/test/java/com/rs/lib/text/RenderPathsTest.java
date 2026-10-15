package com.rs.lib.text;

import static com.rs.lib.text.Renders.assertSameRender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

import net.kyori.adventure.text.Component;

/** The cheaper render paths give the same result as the full pipeline. */
class RenderPathsTest {

    @Test
    void filledLinesStayOutOfTheSharedCache() {
        RsText.clearCache();
        String fast = "&7Saldo: &e{amount}";
        String slow = "[center]&7Hola {p}";
        Ph[] phs = {Ph.of("amount", "1500"), Ph.of("p", "<red>Steve")};
        assertSameRender(RsText.render(RsText.applyLocals(fast, phs)), RsText.compile(fast).render(phs));
        assertSameRender(RsText.render(RsText.applyLocals(slow, phs)), RsText.compile(slow).render(phs));
        assertSameRender(RsText.render("&7x <red>Steve"), RsText.compile("&7x {p}").render(phs));
        assertEquals(0, RenderCache.size());

        // Nothing put in: the line as written is cached as before.
        RsText.compile("&7Hola {nobody}").render(phs);
        RsText.compile("[center]&7Hola {nobody}").render(phs);
        assertTrue(RenderCache.contains("&7Hola {nobody}"));
        assertTrue(RenderCache.contains("[center]&7Hola {nobody}"));
        assertEquals(2, RenderCache.size());
    }

    @Test
    void uncachedRenderEqualsCachedRender() {
        RsText.clearCache();
        for (String s : new String[] {"&a&lHi &cthere", "<gradient:red:blue>abc</gradient> x", "[center][rgb]Title",
            "<hover:show_text:'&7" + RsText.literal("<b>") + "'>go", "", "&#FF00AAx"}) {
            assertSameRender(RsText.color(s), RsText.colorUncached(s), s);
        }
        assertEquals(Component.empty(), RsText.colorUncached(null));
    }

    @Test
    void rejectedPlayerTextIsNotCached() {
        RsText.clearCache();
        StylePolicy policy = StylePolicy.builder().build();
        assertEquals("Hi there", policy.apply("&cHi <bold>there"));
        assertEquals(0, RenderCache.size());
    }

    @Test
    void bracketsThatAreNotTagsSkipTheSpanPasses() {
        for (String s : new String[] {"[VIP] &aSteve", "[x]y", "a [b] c [", "[rgbx]", "[/smallx] z"}) {
            assertEquals(s, RsText.prefixPhase(s), s);
        }
        assertEquals("[VIP] ᴀʙ", RsText.applyPrefixTags("[VIP] [small]ab[/small]"));
        assertEquals("[VIP] x", RsText.applyPrefixTags("[VIP] [/rgb]x"));
        assertEquals("[VIP] x", RsText.applyPrefixTags("[VIP] [/SMALL]x"));
        assertEquals(RsText.applyPrefixTags("[rgb]ab"), RsText.applyPrefixTags("[RGB]ab"));
    }

    @Test
    void gradientHexMatchesFormat() {
        String text = "Gradient over many letters, 0123456789 &lbold";
        StringBuilder expected = new StringBuilder();
        int total = (int) text.replace("&l", "").chars().filter(c -> !Character.isWhitespace(c)).count();
        int step = 0;
        boolean bold = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&') {
                bold = true;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                expected.append(c);
                continue;
            }
            expected.append("&#").append(String.format(Locale.ROOT, "%06X", RgbGradientUtil.colorAt(step++, total)));
            expected.append(bold ? "&l" : "").append(c);
        }
        assertEquals(expected.toString(), RgbGradientUtil.gradient(text));
        assertTrue(RgbGradientUtil.gradient("ab").startsWith("&#F300F3a&#FF5327b"));
    }

    @Test
    void sharedRecognizers() {
        assertTrue(Markup.hasMarker("a" + RsText.literal("<")));
        assertFalse(Markup.hasMarker("a<"));
        assertTrue(Markup.hasSentinel("x" + Markup.SENTINEL_FIRST));
        assertFalse(Markup.hasSentinel("x" + Markup.MARKER_LAST));
        assertTrue(Markup.escapeAt("a\\<", 1));
        assertTrue(Markup.escapeAt("\\\\", 0));
        assertFalse(Markup.escapeAt("\\", 0));
        assertFalse(Markup.escapeAt("\\a", 0));
        assertEquals(Markup.CENTER, Markup.leadingTagAt("x[CENTER]", 1));
        assertEquals(null, Markup.leadingTagAt("[/rgb]", 0));
        assertTrue(RsText.compile("50%").hasPlaceholders());
        assertTrue(RsText.compile("{x}").hasPlaceholders());
        assertFalse(RsText.compile("&a[rgb]plain").hasPlaceholders());
        assertFalse(RsText.compile(null).hasPlaceholders());
    }

    @Test
    void insertPrefixKeepsTheLeadingTagsFirst() {
        assertEquals("[rgb][Small]P x", RsText.insertPrefix("[rgb][Small]x", "P "));
        assertEquals("[small][NOPREFIX]x", RsText.insertPrefix("[small][NOPREFIX]x", "P "));
        assertEquals("P [/rgb]x", RsText.insertPrefix("[/rgb]x", "P "));
        assertEquals("P ", RsText.insertPrefix("", "P "));
        String same = "[noprefix]";
        assertSame(same, RsText.insertPrefix(same, "P "));
    }
}
