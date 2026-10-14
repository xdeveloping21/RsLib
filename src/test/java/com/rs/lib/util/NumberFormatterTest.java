package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NumberFormatterTest {

    @Test
    void shortFormat() {
        assertEquals("1.5K", NumberFormatter.format(1500));
        assertEquals("1K", NumberFormatter.format(1000));
        assertEquals("1M", NumberFormatter.format(999_999));
        assertEquals("1K", NumberFormatter.format(999.999));
        assertEquals("999.99", NumberFormatter.format(999.99));
        assertEquals("1B", NumberFormatter.format(999_995_000));
        assertEquals("1.23M", NumberFormatter.format(1_234_567));
        assertEquals("12.35", NumberFormatter.format(12.345));
        assertEquals("1.01", NumberFormatter.format(1.005));
        assertEquals("0", NumberFormatter.format(0));
        assertEquals("0", NumberFormatter.format(0.004));
        assertEquals("0", NumberFormatter.format(-0.001));
        assertEquals("-1.5K", NumberFormatter.format(-1500));
        assertEquals("2T", NumberFormatter.format(2e12));
        assertEquals("3Qa", NumberFormatter.format(3e15));
        assertEquals("1Qi", NumberFormatter.format(1e18));
        assertEquals("1000Qi", NumberFormatter.format(1e21));
        assertEquals("NaN", NumberFormatter.format(Double.NaN));
        assertEquals("Infinity", NumberFormatter.format(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", NumberFormatter.format(Double.NEGATIVE_INFINITY));
    }

    @Test
    void groupedFormat() {
        assertEquals("1,234,567.89", NumberFormatter.formatComma(1_234_567.891));
        assertEquals("1,500", NumberFormatter.formatComma(1500));
        assertEquals("999", NumberFormatter.formatComma(999));
        assertEquals("100,000", NumberFormatter.formatComma(100_000));
        assertEquals("0.13", NumberFormatter.formatComma(0.125));
        assertEquals("-1,234.5", NumberFormatter.formatComma(-1234.5));
        assertEquals("0", NumberFormatter.formatComma(-0.001));
        assertEquals("NaN", NumberFormatter.formatComma(Double.NaN));
        assertEquals("Infinity", NumberFormatter.formatComma(Double.POSITIVE_INFINITY));
    }

    @Test
    void parseIsTolerant() {
        assertEquals(1500, NumberFormatter.parseFormatted("1.5K"));
        assertEquals(1500, NumberFormatter.parseFormatted("1.5k"));
        assertEquals(1500, NumberFormatter.parseFormatted("1,500"));
        assertEquals(1.5, NumberFormatter.parseFormatted("1,5"));
        assertEquals(1.25, NumberFormatter.parseFormatted("1,25"));
        assertEquals(1_234_567, NumberFormatter.parseFormatted("1,234,567"));
        assertEquals(1234.5, NumberFormatter.parseFormatted("1,234.5"));
        assertEquals(1234.5, NumberFormatter.parseFormatted("1.234,5"));
        assertEquals(2e15, NumberFormatter.parseFormatted("2qa"));
        assertEquals(3e18, NumberFormatter.parseFormatted("3QI"));
        assertEquals(1e7, NumberFormatter.parseFormatted(" 10m "));
        assertEquals(-5000, NumberFormatter.parseFormatted("-5k"));
        assertEquals(1000, NumberFormatter.parseFormatted("1e3"));
        assertEquals(42, NumberFormatter.parseFormatted("42"));
        assertTrue(Double.isNaN(NumberFormatter.parseFormatted("NaN")));
        assertEquals(Double.POSITIVE_INFINITY, NumberFormatter.parseFormatted("Infinity"));
        assertEquals(1_230_000, NumberFormatter.parseFormatted(NumberFormatter.format(1_234_567)));
        assertEquals(1_234_567.89, NumberFormatter.parseFormatted(NumberFormatter.formatComma(1_234_567.891)));
    }

    @Test
    void parseRejectsGarbage() {
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted("5X"));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted(null));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted(""));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted("  "));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted("abc"));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted("1.2.3"));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted("K"));
    }
}
