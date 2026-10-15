package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Strict amounts: grouping, suffixes and the characters that are ignored. */
class AmountsTest {

    @Test
    void grouping() {
        assertEquals(Optional.of(new BigDecimal("1500.25")), Amounts.parse("1,500.25"));
        assertEquals(Optional.of(new BigDecimal("1500.25")), Amounts.parse("1.500,25"));
        assertEquals(Optional.of(new BigDecimal("1000000")), Amounts.parse("1,000,000"));
        assertEquals(Optional.of(new BigDecimal("1000000")), Amounts.parse("1.000.000"));
        assertEquals(Optional.of(new BigDecimal("0.500")), Amounts.parse("0.500"));
        for (String bad : new String[] {"1,50,0", "1.500", "1,500", "1234,567,8", "1,234,56", "1,2345.6", ",500",
            "1,,000", "1234,567.5", "1.2.3"}) {
            assertTrue(Amounts.parse(bad).isEmpty(), bad);
        }
    }

    @Test
    void suffixesAndSpaces() {
        assertEquals(Optional.of(new BigDecimal("1500.0")), Amounts.parse("1.5k"));
        assertEquals(Optional.of(new BigDecimal("3000000")), Amounts.parse("3 M"));
        assertEquals(Optional.of(new BigDecimal("1000")), Amounts.parse("1 000"));
        assertEquals(Optional.of(new BigDecimal("1000")), Amounts.parse("1 000"));
        assertEquals(Optional.of(new BigDecimal("1000")), Amounts.parse("1_000"));
        assertEquals(new BigDecimal("2").multiply(BigDecimal.TEN.pow(18)), Amounts.parse("2qi").orElseThrow());
        assertTrue(Amounts.parse("5K").isEmpty(), "Kelvin sign is not K");
        assertTrue(Amounts.parse("2kk").isEmpty());
    }

    @Test
    void sharedSuffixTable() {
        assertEquals(0, NumberFormatter.suffixPower(""));
        assertEquals(1, NumberFormatter.suffixPower("k"));
        assertEquals(5, NumberFormatter.suffixPower("QA"));
        assertEquals(6, NumberFormatter.suffixPower("qI"));
        assertEquals(-1, NumberFormatter.suffixPower("Q"));
        assertEquals(1.5e15, NumberFormatter.parseFormatted("1.5qa"));
    }
}
