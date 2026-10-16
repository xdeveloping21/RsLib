package com.rs.lib.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CronExprTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private static ZonedDateTime at(String local) {
        return at(local, UTC);
    }

    private static ZonedDateTime at(String local, ZoneId zone) {
        return LocalDateTime.parse(local).atZone(zone);
    }

    private static ZonedDateTime next(String expr, String from) {
        return CronExpr.parse(expr).nextRun(at(from));
    }

    // ------------------------------------------------------------------ basics

    @Test
    void nextRunIsStrictlyAfterAndToTheMinute() {
        assertEquals(at("2025-06-10T10:01"), next("* * * * *", "2025-06-10T10:00"));
        assertEquals(at("2025-06-10T10:01"), next("* * * * *", "2025-06-10T10:00:59.999"));
        assertEquals(at("2025-06-11T10:00"), next("0 10 * * *", "2025-06-10T10:00"));
        assertEquals(at("2025-06-10T10:00"), next("0 10 * * *", "2025-06-10T09:59:59"));
    }

    @Test
    void keepsTheZoneOfFrom() {
        ZonedDateTime from = at("2025-06-10T10:00", MADRID);
        ZonedDateTime run = CronExpr.parse("30 12 * * *").nextRun(from);
        assertEquals(at("2025-06-10T12:30", MADRID), run);
        assertEquals(MADRID, run.getZone());
    }

    @Test
    void toStringIsTheOriginalText() {
        assertEquals("daily 04:30", CronExpr.parse("daily 04:30").toString());
        assertEquals("*/5  * * * *", CronExpr.parse("*/5  * * * *").toString());
    }

    @Test
    void shortcuts() {
        assertEquals(at("2025-06-11T00:00"), next("daily", "2025-06-10T00:00"));
        assertEquals(at("2025-06-10T04:30"), next("daily 04:30", "2025-06-10T03:00"));
        assertEquals(at("2025-06-11T04:30"), next("daily 4:30", "2025-06-10T04:30"));
        assertEquals(at("2025-06-10T11:00"), next("hourly", "2025-06-10T10:00"));
        assertEquals(at("2025-06-10T10:15"), next("hourly :15", "2025-06-10T10:00"));
        assertEquals(at("2025-06-10T11:15"), next("hourly 15", "2025-06-10T10:15"));
        assertEquals(at("2025-06-10T00:05"), next("HOURLY :05", "2025-06-09T23:59"));
        assertEquals(at("2025-06-10T23:59"), next("  Daily   23:59 ", "2025-06-10T12:00"));
    }

    @Test
    void shortcutsEqualTheirFields() {
        ZonedDateTime from = at("2024-02-28T23:30");
        for (int i = 0; i < 50; i++) {
            assertEquals(CronExpr.parse("0 0 * * *").nextRun(from), CronExpr.parse("daily").nextRun(from));
            assertEquals(CronExpr.parse("7 * * * *").nextRun(from), CronExpr.parse("hourly :07").nextRun(from));
            from = from.plusMinutes(37);
        }
    }

    // ------------------------------------------------------------------ lists, ranges, steps

    @Test
    void listsRangesAndSteps() {
        assertEquals(at("2025-06-10T10:15"), next("*/15 * * * *", "2025-06-10T10:00"));
        assertEquals(at("2025-06-10T11:00"), next("*/15 * * * *", "2025-06-10T10:45"));
        assertEquals(at("2025-06-10T10:20"), next("10-30/10 * * * *", "2025-06-10T10:10"));
        assertEquals(at("2025-06-10T11:10"), next("10-30/10 * * * *", "2025-06-10T10:30"));
        assertEquals(at("2025-06-10T10:07"), next("1,7,9 * * * *", "2025-06-10T10:01"));
        assertEquals(at("2025-06-10T12:00"), next("0 9-12,18 * * *", "2025-06-10T11:00"));
        assertEquals(at("2025-06-10T18:00"), next("0 9-12,18 * * *", "2025-06-10T12:00"));
    }

    @Test
    void stepOnASingleValueRunsToTheEndOfTheRange() {
        CronExpr expr = CronExpr.parse("5/15 * * * *");
        ZonedDateTime t = at("2025-06-10T10:00");
        int[] expected = {5, 20, 35, 50, 5};
        for (int minute : expected) {
            t = expr.nextRun(t);
            assertEquals(minute, t.getMinute());
        }
        assertEquals(11, t.getHour());
        assertEquals(at("2025-06-10T22:00"), next("0 10/12 * * *", "2025-06-10T10:00"));
    }

    @Test
    void stepLargerThanTheRangeKeepsTheStart() {
        assertEquals(at("2025-06-10T11:07"), next("7/90 * * * *", "2025-06-10T10:08"));
    }

    @Test
    void monthsAndYearRollOver() {
        assertEquals(at("2026-01-01T00:00"), next("0 0 1 1 *", "2025-12-31T23:59"));
        assertEquals(at("2025-09-01T06:00"), next("0 6 1 3,6,9,12 *", "2025-06-01T06:00"));
        assertEquals(at("2026-03-01T06:00"), next("0 6 1 3,6,9,12 *", "2025-12-01T06:00"));
    }

    // ------------------------------------------------------------------ day of month vs day of week

    @Test
    void sevenAndZeroAreSunday() {
        // 2025-06-10 is a Tuesday; the next Sunday is the 15th.
        assertEquals(at("2025-06-15T08:00"), next("0 8 * * 0", "2025-06-10T00:00"));
        assertEquals(at("2025-06-15T08:00"), next("0 8 * * 7", "2025-06-10T00:00"));
        assertEquals(at("2025-06-13T08:00"), next("0 8 * * 5-7", "2025-06-10T00:00"));
        assertEquals(at("2025-06-15T08:00"), next("0 8 * * 5-7", "2025-06-14T08:00"));
        assertEquals(at("2025-06-16T08:00"), next("0 8 * * 1-5", "2025-06-13T08:00"));
    }

    @Test
    void weekdayStepCanReachSeven() {
        // 1/2 = 1,3,5,7: Monday, Wednesday, Friday and Sunday.
        CronExpr expr = CronExpr.parse("0 0 * * 1/2");
        ZonedDateTime t = at("2025-06-09T00:00");
        DayOfWeek[] expected = {DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY};
        for (DayOfWeek day : expected) {
            t = expr.nextRun(t);
            assertEquals(day, t.getDayOfWeek());
        }
    }

    @Test
    void bothDayFieldsRestrictedMeansEither() {
        // Friday or the 13th: from Tuesday 2025-06-10 the 13th is also a Friday; from the 14th,
        // the next Friday (20th) comes before the next 13th.
        assertEquals(at("2025-06-13T00:00"), next("0 0 13 * 5", "2025-06-10T00:00"));
        assertEquals(at("2025-06-20T00:00"), next("0 0 13 * 5", "2025-06-14T00:00"));
        // The 11th (a Wednesday) is not a Friday but is the day of month.
        assertEquals(at("2025-06-11T00:00"), next("0 0 11 * 5", "2025-06-10T00:00"));
    }

    @Test
    void onlyOneRestrictedDayFieldDecides() {
        assertEquals(at("2025-07-13T00:00"), next("0 0 13 * *", "2025-06-13T00:00"));
        assertEquals(at("2025-06-20T00:00"), next("0 0 * * 5", "2025-06-13T00:00"));
    }

    @Test
    void onlyAPlainStarCountsAsUnrestricted() {
        // "*/1" names every day, so with a weekday the OR rule makes every day match.
        assertEquals(at("2025-06-11T00:00"), next("0 0 */1 * 1", "2025-06-10T00:00"));
        assertEquals(at("2025-06-16T00:00"), next("0 0 * * 1", "2025-06-10T00:00"));
        assertEquals(at("2025-06-11T00:00"), next("0 0 * * */1", "2025-06-10T00:00"));
    }

    @Test
    void shortMonthsWaitForTheDay() {
        assertEquals(at("2025-03-31T00:00"), next("0 0 31 * *", "2025-01-31T00:00"));
        assertEquals(at("2025-05-31T00:00"), next("0 0 31 * *", "2025-03-31T00:00"));
        assertEquals(at("2028-02-29T00:00"), next("0 0 29 2 *", "2025-03-01T00:00"));
        assertEquals(at("2104-02-29T00:00"), next("0 0 29 2 *", "2096-03-01T00:00"));
    }

    @Test
    void impossibleDatesThrowOnNextRun() {
        for (String expr : new String[] {"0 0 31 2 *", "0 0 30 2 *", "0 0 31 4,6,9,11 *", "0 0 30,31 2 *"}) {
            CronExpr parsed = CronExpr.parse(expr);
            assertThrows(IllegalStateException.class, () -> parsed.nextRun(at("2025-01-01T00:00")), expr);
        }
        // With a weekday as well the OR rule makes it possible: any Monday of February.
        assertEquals(at("2026-02-02T00:00"), next("0 0 31 2 1", "2025-03-01T00:00"));
    }

    // ------------------------------------------------------------------ invalid input

    @ParameterizedTest
    @ValueSource(strings = {
        "", "   ", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * 32 * *",
        "* * * 0 *", "* * * 13 *", "* * * * 8", "5-1 * * * *", "*/0 * * * *", "1,,2 * * * *", "1, * * * *",
        ",1 * * * *", "a * * * *", "-1 * * * *", "1-2-3 * * * *", "*/ * * * *", "1- * * * *", "*-5 * * * *",
        "0 0 * JAN *", "0 0 * * MON", "@daily", "0 0 L * *", "? * * * *", "+5 * * * *", "99999999999 * * * *",
        "daily 25:00", "daily 04:60", "daily 4", "daily 04:30 extra", "daily :", "daily 123:00", "hourly 60",
        "hourly x", "hourly :", "hourly 1 2", "weekly"})
    void rejectsWhatIsNotTheGrammar(String expr) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CronExpr.parse(expr));
        assertTrue(e.getMessage().startsWith("Cron expression '") || e.getMessage().contains("empty"),
                e.getMessage());
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse(null));
        assertThrows(NullPointerException.class, () -> CronExpr.parse("* * * * *").nextRun(null));
    }

    @Test
    void messagesNameTheProblem() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("0 25 * * *"));
        assertTrue(e.getMessage().contains("hour"), e.getMessage());
        assertTrue(e.getMessage().contains("0-23"), e.getMessage());
    }

    @Test
    void leadingZerosAreFine() {
        assertEquals(at("2025-06-10T05:05"), next("05 05 * * *", "2025-06-10T00:00"));
        assertEquals(at("2025-06-10T00:00"), next("00 00 * * 02", "2025-06-09T12:00"));
    }

    // ------------------------------------------------------------------ daylight saving

    @Test
    void springForwardSkipsTheMissingTime() {
        // Madrid, 2025-03-30: 02:00 jumps to 03:00.
        CronExpr expr = CronExpr.parse("30 2 * * *");
        ZonedDateTime run = expr.nextRun(at("2025-03-30T00:00", MADRID));
        assertEquals(at("2025-03-31T02:30", MADRID), run);
        assertEquals(ZoneOffset.ofHours(2), run.getOffset());
    }

    @Test
    void springForwardKeepsRunningAroundTheGap() {
        ZonedDateTime from = at("2025-03-30T01:45", MADRID);
        assertEquals(at("2025-03-30T03:00", MADRID), CronExpr.parse("*/15 * * * *").nextRun(from));
        assertEquals(at("2025-03-30T03:00", MADRID), CronExpr.parse("hourly").nextRun(from));
        assertEquals(at("2025-03-30T03:00", MADRID), CronExpr.parse("0 3 * * *").nextRun(from));
        // From the last minute before the jump, the next minute is 03:00.
        assertEquals(at("2025-03-30T03:00", MADRID),
                CronExpr.parse("* * * * *").nextRun(at("2025-03-30T01:59", MADRID)));
    }

    @Test
    void fallBackRunsAFixedTimeOnce() {
        // Madrid, 2025-10-26: 03:00 (+02) goes back to 02:00 (+01); 02:00-02:59 happens twice.
        CronExpr expr = CronExpr.parse("30 2 * * *");
        ZonedDateTime first = expr.nextRun(at("2025-10-26T00:00", MADRID));
        assertEquals(ZonedDateTime.of(2025, 10, 26, 2, 30, 0, 0, MADRID).withEarlierOffsetAtOverlap(), first);
        assertEquals(ZoneOffset.ofHours(2), first.getOffset());
        assertEquals(at("2025-10-27T02:30", MADRID), expr.nextRun(first));
        // Asked from inside the second pass, it waits for the next day as well.
        ZonedDateTime secondPass = first.withLaterOffsetAtOverlap().minusMinutes(20);
        assertEquals(at("2025-10-27T02:30", MADRID), expr.nextRun(secondPass));
    }

    @Test
    void fallBackRunsEveryHourJobsInBothPasses() {
        ZonedDateTime firstPass = ZonedDateTime.of(2025, 10, 26, 2, 30, 0, 0, MADRID).withEarlierOffsetAtOverlap();
        ZonedDateTime run = CronExpr.parse("*/30 * * * *").nextRun(firstPass);
        assertEquals(ZonedDateTime.of(2025, 10, 26, 2, 0, 0, 0, MADRID).withLaterOffsetAtOverlap(), run);
        assertEquals(ZoneOffset.ofHours(1), run.getOffset());
        run = CronExpr.parse("*/30 * * * *").nextRun(run);
        assertEquals(ZonedDateTime.of(2025, 10, 26, 2, 30, 0, 0, MADRID).withLaterOffsetAtOverlap(), run);
        assertEquals(at("2025-10-26T03:00", MADRID), CronExpr.parse("hourly").nextRun(run));
        // Real minutes keep going: from 02:59 (+02) the next minute is 02:00 (+01).
        ZonedDateTime lastFirst = ZonedDateTime.of(2025, 10, 26, 2, 59, 0, 0, MADRID).withEarlierOffsetAtOverlap();
        assertEquals(lastFirst.plusMinutes(1), CronExpr.parse("* * * * *").nextRun(lastFirst));
    }

    @Test
    void fallBackKeepsFixedTimesAfterTheRepeatedHour() {
        ZonedDateTime firstPass = ZonedDateTime.of(2025, 10, 26, 2, 45, 0, 0, MADRID).withEarlierOffsetAtOverlap();
        assertEquals(at("2025-10-26T03:00", MADRID), CronExpr.parse("0 3 * * *").nextRun(firstPass));
        assertEquals(at("2025-10-26T03:00", MADRID), CronExpr.parse("*/10 2-3 * * *").nextRun(firstPass.plusMinutes(10)));
    }

    @Test
    void midnightThatDoesNotExistIsSkipped() {
        // Sao Paulo, 2018-11-04: midnight jumped to 01:00.
        ZoneId saoPaulo = ZoneId.of("America/Sao_Paulo");
        assertEquals(at("2018-11-05T00:00", saoPaulo), CronExpr.parse("daily").nextRun(at("2018-11-03T12:00", saoPaulo)));
        assertEquals(at("2018-11-04T01:00", saoPaulo), CronExpr.parse("0 * * * *").nextRun(at("2018-11-03T23:30", saoPaulo)));
    }

    @Test
    void halfHourShifts() {
        // Lord Howe, 2025-04-06: 02:00 (+11) goes back to 01:30 (+10:30).
        ZoneId lordHowe = ZoneId.of("Australia/Lord_Howe");
        ZonedDateTime from = ZonedDateTime.of(2025, 4, 6, 1, 50, 0, 0, lordHowe).withEarlierOffsetAtOverlap();
        ZonedDateTime run = CronExpr.parse("*/10 * * * *").nextRun(from);
        assertEquals(ZonedDateTime.of(2025, 4, 6, 1, 30, 0, 0, lordHowe).withLaterOffsetAtOverlap(), run);
        assertEquals(ZoneOffset.ofHoursMinutes(10, 30), run.getOffset());
        assertEquals(at("2025-04-07T01:45", lordHowe), CronExpr.parse("45 1 * * *").nextRun(from));
    }

    @Test
    void walksAYearOfMinutesInOrder() {
        // Every result is strictly after the previous one, across both Madrid changes.
        CronExpr expr = CronExpr.parse("*/7 1-3 * * *");
        ZonedDateTime t = expr.nextRun(at("2025-01-01T00:00", MADRID));
        int runs = 0;
        while (t.getYear() == 2025) {
            assertTrue(t.getHour() >= 1 && t.getHour() <= 3 && t.getMinute() % 7 == 0, t::toString);
            runs++;
            ZonedDateTime next = expr.nextRun(t);
            ZonedDateTime previous = t;
            assertTrue(next.isAfter(previous), () -> "not after " + previous);
            t = next;
        }
        // 9 runs an hour, 3 hours a day; on 2025-03-30 hour 2 is missing.
        assertEquals(365 * 27 - 9, runs);
    }
}
