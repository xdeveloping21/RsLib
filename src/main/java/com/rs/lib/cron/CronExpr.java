package com.rs.lib.cron;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Locale;
import java.util.Objects;

/**
 * A calendar schedule written in a config: five cron fields or a shortcut.
 *
 * <p>Fields: {@code minute hour day-of-month month day-of-week}, ranges 0-59, 0-23, 1-31, 1-12
 * and 0-7 (0 and 7 are Sunday). Each field is a comma list of {@code *}, {@code n},
 * {@code a-b}, each optionally followed by {@code /step}; {@code a/step} runs from {@code a}
 * to the end of the range. There are no names ({@code MON}, {@code JAN}), seconds,
 * {@code @macros} or {@code L}/{@code W}/{@code #}. Shortcuts: {@code daily} (00:00),
 * {@code daily HH:mm}, {@code hourly} (minute 0), {@code hourly :mm} and {@code hourly mm}.
 * Anything else throws {@link IllegalArgumentException} naming the problem.</p>
 *
 * <p>Day of month and day of week: when both fields are written as something other than a
 * plain {@code *} (even {@code *}{@code /1}), a day matches if either matches; when only one is,
 * that one decides.</p>
 *
 * <p>{@link #nextRun} works in the zone of the time it is given. A time that daylight saving
 * skips does not run that day. In the hour that repeats when clocks go back, an expression
 * that names its hours runs once, at the first pass; one whose hour field is every hour also
 * runs in the second pass, as real time goes on. Immutable; any thread.</p>
 */
public final class CronExpr {

    /** Upper bound on the steps of one {@link #nextRun}; only an impossible schedule reaches it. */
    private static final int MAX_ADJUSTMENTS = 5000;
    private static final long ALL_HOURS = (1L << 24) - 1;

    private final String text;
    private final long minutes;
    private final long hours;
    private final long days;
    private final long months;
    private final long weekdays;
    /** The day-of-month field is a plain {@code *}. */
    private final boolean anyDay;
    /** The day-of-week field is a plain {@code *}. */
    private final boolean anyWeekday;
    /** No date can ever match (such as the 31st of February). */
    private final boolean impossible;

    private CronExpr(String text, long minutes, long hours, long days, long months, long weekdays,
            boolean anyDay, boolean anyWeekday) {
        this.text = text;
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.months = months;
        this.weekdays = weekdays;
        this.anyDay = anyDay;
        this.anyWeekday = anyWeekday;
        this.impossible = !anyDay && anyWeekday && !anyMonthHasADay(days, months);
    }

    /** Reads {@code expr}; throws {@link IllegalArgumentException} when it is not valid. */
    public static CronExpr parse(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Cron expression is empty");
        }
        String[] parts = expr.trim().split("\\s+");
        String head = parts[0].toLowerCase(Locale.ROOT);
        if (head.equals("daily")) {
            return daily(expr, parts);
        }
        if (head.equals("hourly")) {
            return hourly(expr, parts);
        }
        if (parts.length != 5) {
            throw invalid(expr, "expected 5 fields (minute hour day month day-of-week), got " + parts.length);
        }
        long weekdays = field(expr, parts[4], "day-of-week", 0, 7);
        if ((weekdays & (1L << 7)) != 0) {
            weekdays = (weekdays | 1L) & ~(1L << 7);
        }
        return new CronExpr(expr,
                field(expr, parts[0], "minute", 0, 59),
                field(expr, parts[1], "hour", 0, 23),
                field(expr, parts[2], "day-of-month", 1, 31),
                field(expr, parts[3], "month", 1, 12),
                weekdays,
                parts[2].equals("*"),
                parts[4].equals("*"));
    }

    /**
     * The first time strictly after {@code from}, to the minute, in {@code from}'s zone; calling
     * it with "now" never returns the current minute. Throws {@link IllegalStateException} when
     * the expression can never match (such as {@code 0 0 31 2 *}).
     */
    public ZonedDateTime nextRun(ZonedDateTime from) {
        Objects.requireNonNull(from, "from");
        if (impossible) {
            throw neverRuns();
        }
        ZoneId zone = from.getZone();
        ZoneRules rules = zone.getRules();
        ZonedDateTime at = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        for (int step = 0; step < MAX_ADJUSTMENTS; step++) {
            LocalDateTime local = at.toLocalDateTime();
            LocalDateTime match = nextLocal(local);
            if (!match.equals(local)) {
                // The offset holds until the next transition, so local minutes and real minutes
                // go together until then; stop at the transition and look again from there.
                Instant target = at.toInstant().plusSeconds(60 * ChronoUnit.MINUTES.between(local, match));
                ZoneOffsetTransition change = rules.nextTransition(at.toInstant());
                if (change != null && !change.getInstant().isAfter(target)) {
                    at = ceilToMinute(ZonedDateTime.ofInstant(change.getInstant(), zone));
                    continue;
                }
                at = ZonedDateTime.ofInstant(target, zone);
            }
            ZoneOffsetTransition overlap = rules.getTransition(match);
            if (hours != ALL_HOURS && overlap != null && overlap.isOverlap()
                    && at.getOffset().equals(overlap.getOffsetAfter())) {
                // Second pass of the hour that repeats: this time already ran in the first one.
                at = ZonedDateTime.ofInstant(overlap.getDateTimeBefore().toInstant(overlap.getOffsetAfter()), zone);
                continue;
            }
            return at;
        }
        throw neverRuns();
    }

    /** The text it was parsed from. */
    @Override
    public String toString() {
        return text;
    }

    /** The first local time at or after {@code from} (whole minutes) whose fields all match. */
    private LocalDateTime nextLocal(LocalDateTime from) {
        int year = from.getYear();
        int month = from.getMonthValue();
        int day = from.getDayOfMonth();
        int hour = from.getHour();
        int minute = from.getMinute();
        for (int step = 0; step < MAX_ADJUSTMENTS; step++) {
            if (!has(months, month)) {
                int next = nextBit(months, month + 1);
                if (next < 0) {
                    year++;
                    next = nextBit(months, 1);
                }
                month = next;
                day = 1;
                hour = 0;
                minute = 0;
                continue;
            }
            int length = Month.of(month).length(Year.isLeap(year));
            if (day > length) {
                if (++month > 12) {
                    month = 1;
                    year++;
                }
                day = 1;
                hour = 0;
                minute = 0;
                continue;
            }
            if (!dayMatches(year, month, day)) {
                if (anyWeekday) {
                    int next = nextBit(days, day + 1);
                    day = next < 0 ? length + 1 : next;
                } else {
                    day++;
                }
                hour = 0;
                minute = 0;
                continue;
            }
            if (!has(hours, hour)) {
                int next = nextBit(hours, hour + 1);
                if (next < 0) {
                    day++;
                    hour = 0;
                } else {
                    hour = next;
                }
                minute = 0;
                continue;
            }
            if (!has(minutes, minute)) {
                int next = nextBit(minutes, minute + 1);
                if (next < 0) {
                    minute = 0;
                    if (++hour == 24) {
                        hour = 0;
                        day++;
                    }
                    continue;
                }
                minute = next;
            }
            return LocalDateTime.of(year, month, day, hour, minute);
        }
        throw neverRuns();
    }

    private boolean dayMatches(int year, int month, int day) {
        if (anyDay && anyWeekday) {
            return true;
        }
        boolean byDay = has(days, day);
        if (anyWeekday) {
            return byDay;
        }
        boolean byWeekday = has(weekdays, LocalDate.of(year, month, day).getDayOfWeek().getValue() % 7);
        return anyDay ? byWeekday : byDay || byWeekday;
    }

    private IllegalStateException neverRuns() {
        return new IllegalStateException("Cron expression '" + text + "': never matches a date");
    }

    private static boolean anyMonthHasADay(long days, long months) {
        int firstDay = Long.numberOfTrailingZeros(days);
        for (int month = nextBit(months, 1); month > 0; month = nextBit(months, month + 1)) {
            if (firstDay <= Month.of(month).maxLength()) {
                return true;
            }
        }
        return false;
    }

    /** A transition can fall on a second that is not whole; the schedule works in whole minutes. */
    private static ZonedDateTime ceilToMinute(ZonedDateTime at) {
        ZonedDateTime floor = at.truncatedTo(ChronoUnit.MINUTES);
        return floor.equals(at) ? at : floor.plusMinutes(1);
    }

    private static boolean has(long mask, int value) {
        return (mask & (1L << value)) != 0;
    }

    /** The lowest bit set at or above {@code from}, or -1. */
    private static int nextBit(long mask, int from) {
        if (from > 63) {
            return -1;
        }
        long rest = mask & (-1L << from);
        return rest == 0 ? -1 : Long.numberOfTrailingZeros(rest);
    }

    // ------------------------------------------------------------------ parsing

    private static CronExpr daily(String expr, String[] parts) {
        int hour = 0;
        int minute = 0;
        if (parts.length == 2) {
            String time = parts[1];
            int colon = time.indexOf(':');
            if (colon < 0) {
                throw invalid(expr, "'daily' takes a time as HH:mm, not '" + time + "'");
            }
            hour = shortNumber(expr, time.substring(0, colon), "hour", 23);
            minute = shortNumber(expr, time.substring(colon + 1), "minute", 59);
        } else if (parts.length > 2) {
            throw invalid(expr, "'daily' takes at most one time (HH:mm)");
        }
        return shortcut(expr, 1L << minute, 1L << hour);
    }

    private static CronExpr hourly(String expr, String[] parts) {
        int minute = 0;
        if (parts.length == 2) {
            String value = parts[1];
            minute = shortNumber(expr, value.startsWith(":") ? value.substring(1) : value, "minute", 59);
        } else if (parts.length > 2) {
            throw invalid(expr, "'hourly' takes at most one minute (:mm)");
        }
        return shortcut(expr, 1L << minute, ALL_HOURS);
    }

    private static CronExpr shortcut(String expr, long minutes, long hours) {
        return new CronExpr(expr, minutes, hours, range(1, 31, 1), range(1, 12, 1), range(0, 6, 1), true, true);
    }

    /** One or two digits up to {@code max}, for the shortcuts. */
    private static int shortNumber(String expr, String digits, String what, int max) {
        if (digits.isEmpty() || digits.length() > 2) {
            throw invalid(expr, "bad " + what + " '" + digits + "'");
        }
        int value = number(expr, digits, what);
        if (value > max) {
            throw invalid(expr, what + " " + value + " is out of range 0-" + max);
        }
        return value;
    }

    /** A comma list of {@code *}, {@code n}, {@code a-b}, each with an optional {@code /step}. */
    private static long field(String expr, String field, String name, int min, int max) {
        long mask = 0;
        int start = 0;
        while (start <= field.length()) {
            int comma = field.indexOf(',', start);
            int end = comma < 0 ? field.length() : comma;
            if (end == start) {
                throw invalid(expr, "empty value in the " + name + " field '" + field + "'");
            }
            mask |= atom(expr, field.substring(start, end), name, min, max);
            start = end + 1;
        }
        return mask;
    }

    private static long atom(String expr, String atom, String name, int min, int max) {
        int slash = atom.indexOf('/');
        String span = slash < 0 ? atom : atom.substring(0, slash);
        int step = 1;
        if (slash >= 0) {
            step = number(expr, atom.substring(slash + 1), name + " step");
            if (step < 1) {
                throw invalid(expr, "the " + name + " step must be at least 1 in '" + atom + "'");
            }
        }
        int low;
        int high;
        if (span.equals("*")) {
            low = min;
            high = max;
        } else {
            int dash = span.indexOf('-');
            if (dash < 0) {
                low = number(expr, span, name);
                high = slash < 0 ? low : max;
            } else {
                low = number(expr, span.substring(0, dash), name);
                high = number(expr, span.substring(dash + 1), name);
            }
            if (low < min || high > max || low > max) {
                throw invalid(expr, name + " '" + span + "' is out of range " + min + "-" + max);
            }
            if (low > high) {
                throw invalid(expr, name + " range '" + span + "' goes backwards");
            }
        }
        return range(low, high, step);
    }

    private static long range(int low, int high, int step) {
        long mask = 0;
        for (int value = low; value <= high; value += step) {
            mask |= 1L << value;
        }
        return mask;
    }

    /** Plain digits; anything else is an error naming {@code what}. */
    private static int number(String expr, String digits, String what) {
        if (digits.isEmpty()) {
            throw invalid(expr, "missing number for " + what);
        }
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw invalid(expr, "'" + digits + "' is not a number (" + what + ")");
            }
            value = Math.min(value * 10 + (c - '0'), 100_000);
        }
        return value;
    }

    private static IllegalArgumentException invalid(String expr, String problem) {
        return new IllegalArgumentException("Cron expression '" + expr + "': " + problem);
    }
}
