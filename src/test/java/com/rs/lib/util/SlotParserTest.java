package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SlotParserTest {

    @Test
    void readsEveryForm() {
        assertArrayEquals(new int[] {4}, SlotParser.parse(4));
        assertArrayEquals(new int[] {4}, SlotParser.parse("4"));
        assertArrayEquals(new int[] {4}, SlotParser.parse(4.0));
        assertArrayEquals(new int[] {4}, SlotParser.parse(4L));
        assertArrayEquals(new int[] {0, 1, 2, 3}, SlotParser.parse("0-3"));
        assertArrayEquals(new int[] {0, 1, 2, 3}, SlotParser.parse("3-0"));
        assertArrayEquals(new int[] {0, 2, 4, 5, 6}, SlotParser.parse("0,2,4-6"));
        assertArrayEquals(new int[] {0, 2, 3, 4, 9, 10}, SlotParser.parse(List.of(0, "2-4", "9, 10")));
        assertArrayEquals(new int[] {1, 2, 3}, SlotParser.parse(" 1 , 2 - 3 "));
        assertArrayEquals(new int[0], SlotParser.parse(null));
        assertArrayEquals(new int[0], SlotParser.parse(""));
    }

    @Test
    void slotsAreUniqueInOrderOfAppearance() {
        assertArrayEquals(new int[] {5, 3, 4, 0}, SlotParser.parse("5,3-5,0,4"));
        assertArrayEquals(new int[] {9000, 1}, SlotParser.parse(List.of(9000, 1, 9000, "1")));
    }

    @Test
    void badTokensAreDroppedWithAWarning() {
        List<String> warnings = new ArrayList<>();
        assertArrayEquals(new int[] {1, 3}, SlotParser.parse("1,-2,abc,3,4.5,2-x,-1-3", warnings::add));
        assertEquals(5, warnings.size(), warnings.toString());
        warnings.clear();
        assertArrayEquals(new int[] {2}, SlotParser.parse(List.of(-5, 2.5, 2), warnings::add));
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void hugeRangesAreRejected() {
        List<String> warnings = new ArrayList<>();
        assertEquals(SlotParser.MAX_RANGE, SlotParser.parse("0-9999", warnings::add).length);
        assertTrue(warnings.isEmpty());
        assertArrayEquals(new int[] {7}, SlotParser.parse("0-10000,7", warnings::add));
        assertEquals(1, warnings.size());
        assertArrayEquals(new int[0], SlotParser.parse("0-2147483647", warnings::add));
        assertArrayEquals(new int[0], SlotParser.parse("99999999999", warnings::add));
    }
}
