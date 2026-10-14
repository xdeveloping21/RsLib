package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** CenterUtil, RgbGradientUtil and SmallCapsUtil on their own. */
class TextUtilsTest {

    @Test
    void centerWidths() {
        assertEquals(18, CenterUtil.width("abc"));
        assertEquals(5, CenterUtil.width("il"));
        assertEquals(14, CenterUtil.width("&lab"));
        assertEquals(4 + 7, CenterUtil.width("&l a"));
        assertEquals(6, CenterUtil.width("&l&ca"));
        assertEquals(6, CenterUtil.width("&l&#FF0000a"));
        assertEquals(7, CenterUtil.width("§la"));
        assertEquals(6, CenterUtil.width("<bold>a</bold>"));
        assertEquals(5, CenterUtil.width("\\<"));
        assertEquals(6, CenterUtil.charWidth('@'));
        assertEquals(3, CenterUtil.charWidth('ɪ'));
        assertEquals(5, CenterUtil.charWidth('ᴀ'));
        assertEquals(4, CenterUtil.charWidth('é'));
        assertEquals(4, CenterUtil.charWidth('﷐'));
        assertEquals(3, CenterUtil.charWidth('t'));
        assertEquals(2, CenterUtil.charWidth('`'));
        assertEquals(1, CenterUtil.charWidth('|'));
        assertEquals(4, CenterUtil.charWidth('}'));
    }

    @Test
    void centerPadsToHalfOf154() {
        assertEquals(" ".repeat(37) + "abc", CenterUtil.center("abc"));
        String wide = "W".repeat(60);
        assertSame(wide, CenterUtil.center(wide));
        assertEquals("", CenterUtil.center(""));
        assertNull(CenterUtil.center(null));
    }

    @Test
    void gradientAnchorsAndSteps() {
        assertEquals(0xF300F3, RgbGradientUtil.colorAt(0, 1));
        assertEquals(0xF300F3, RgbGradientUtil.colorAt(0, 13));
        assertEquals(0xFF5327, RgbGradientUtil.colorAt(12, 13));
        assertEquals(0x55FF55, RgbGradientUtil.colorAt(6, 13));
        // Halfway between #F300F3 and #5555FF.
        assertEquals(0xA42BF9, RgbGradientUtil.colorAt(1, 13));
    }

    @Test
    void gradientSkipsSpacesTagsAndKeepsEscapes() {
        assertEquals("&#F300F3a <red>&#FF5327b", RgbGradientUtil.gradient("a <red>b"));
        assertEquals("&#F300F3\\<&#FF5327b", RgbGradientUtil.gradient("\\<b"));
        assertEquals("&#F300F3😀&#FF5327b", RgbGradientUtil.gradient("😀b"));
        assertEquals("  ", RgbGradientUtil.gradient("&a  &l"));
        assertEquals("x&#F300F3y", RgbGradientUtil.applyRgbTag("x[rgb]y"));
        assertEquals("x&#F300F3yz", RgbGradientUtil.applyRgbTag("x[rgb]y[rgb]z").replace("&#FF5327", ""));
        assertSame("no tags", RgbGradientUtil.applyRgbTag("no tags"));
    }

    @Test
    void smallCapsSpans() {
        assertEquals("a ʙ c", SmallCapsUtil.applySmallTag("a [small]b[/small] c"));
        assertEquals("a ʙ ᴄ", SmallCapsUtil.applySmallTag("a [SMALL]b [small]c"));
        assertEquals("ab", SmallCapsUtil.applySmallTag("a[/small]b"));
        assertEquals("[rgb]ᴀ", SmallCapsUtil.applySmallTag("[small][rgb]a"));
        assertEquals('x', SmallCapsUtil.toSmall('x'));
        assertEquals('ᴇ', SmallCapsUtil.toSmall('É'));
        assertEquals('ᴜ', SmallCapsUtil.toSmall('ü'));
        assertEquals('ñ', SmallCapsUtil.toSmall('Ñ'));
        assertEquals('ñ', SmallCapsUtil.toSmall('ñ'));
        String text = "Long &#FF00AA&lText <hover:show_text:'Hover'>here";
        assertEquals(text.length(), SmallCapsUtil.smallCaps(text).length());
    }
}
