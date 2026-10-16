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
        assertEquals(250L, TimeUtil.parseMillis("250 milliseconds"));
        assertEquals(120_000L + 3_000L, TimeUtil.parseMillis("2 minutes 3 seconds"));
        assertEquals(3_600_000L, TimeUtil.parseMillis("1h 2w"));
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

    @Test
    void humanizeLeavesOutZeroParts() {
        long ms = 86_400_000L + 7_200_000L + 1_800_000L + 15_000L + 999L;
        assertEquals("1d 2h 30m 15s", TimeUtil.humanizeShort(ms));
        assertEquals("1 day 2 hours 30 minutes 15 seconds", TimeUtil.humanize(ms));
        assertEquals("2d 5s", TimeUtil.humanizeShort(2 * 86_400_000L + 5_000L));
        assertEquals("1 minute 1 second", TimeUtil.humanize(61_000L));
    }

    @Test
    void humanizeBelowOneSecondIsZero() {
        assertEquals("0s", TimeUtil.humanizeShort(999L));
        assertEquals("0 seconds", TimeUtil.humanize(0L));
        assertEquals("0 seconds", TimeUtil.humanize(-5_000L));
    }

    @Test
    void humanizeUsesTheGivenLabels() {
        TimeUtil.Labels spanish = new TimeUtil.Labels() {
            @Override
            public String longLabel(TimeUtil.Unit unit, boolean plural) {
                return switch (unit) {
                    case DAY -> plural ? "días" : "día";
                    case HOUR -> plural ? "horas" : "hora";
                    case MINUTE -> plural ? "minutos" : "minuto";
                    case SECOND -> plural ? "segundos" : "segundo";
                };
            }

            @Override
            public String shortLabel(TimeUtil.Unit unit) {
                return unit == TimeUtil.Unit.SECOND ? "seg" : longLabel(unit, false).substring(0, 1);
            }
        };
        assertEquals("1 día 3 horas", TimeUtil.humanize(97_200_000L, spanish));
        assertEquals("3h 4seg", TimeUtil.humanizeShort(10_804_000L, spanish));
        assertEquals("1 hour 30 minutes", TimeUtil.humanize(TimeUtil.parseMillis(TimeUtil.humanize(5_400_000L))));
    }
}
