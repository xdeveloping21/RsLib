package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeUtilTest {

    @Test
    void readsUnitsByFirstLetter() {
        assertEquals(86_400_000L + 7_200_000L + 1_800_000L, TimeUtil.parseMillis("1d 2h 30m"));
        assertEquals(86_400_000L + 7_200_000L, TimeUtil.parseMillis("1 day, 2 hours"));
        assertEquals(90_000L, TimeUtil.parseMillis("90"));
        assertEquals(500L, TimeUtil.parseMillis("500ms"));
        assertEquals(1_000L, TimeUtil.parseMillis("20t"));
        assertEquals(5_400_000L, TimeUtil.parseMillis("1.5h"));
        assertEquals(120_000L, TimeUtil.parseMillis("2mo"));
        assertEquals(20L, TimeUtil.parseTicks("1s"));
    }

    @Test
    void garbageIsZeroAndNeverNegative() {
        assertEquals(0L, TimeUtil.parseMillis(null));
        assertEquals(0L, TimeUtil.parseMillis(""));
        assertEquals(0L, TimeUtil.parseMillis("soon"));
        assertEquals(5_000L, TimeUtil.parseMillis("-5s"));
        assertEquals(0L, TimeUtil.parseMillis("5x"));
        assertEquals(Long.MAX_VALUE, TimeUtil.parseMillis("99999999999999999999d"));
    }
}
