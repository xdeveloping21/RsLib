package com.rs.lib.util;

/**
 * Durations written in configs and commands: {@code 30s}, {@code 5m}, {@code 1d 2h 30m},
 * {@code 1 day, 2 hours}, {@code 1.5h}, {@code 20t} (ticks), {@code 500ms}.
 *
 * <p>Each number takes the unit that follows it, read by its first letter: {@code d} days,
 * {@code h} hours, {@code m} minutes, {@code s} seconds, {@code t} ticks; a unit starting with
 * {@code ms} is milliseconds and a number without a unit is seconds. Other words are ignored.
 * {@code m} is always minutes (also {@code mo}/{@code month}): existing configs rely on it.
 * Null or text without numbers reads as 0; the result is never negative. Pure, any thread.</p>
 *
 * <p>{@link #humanize(long)} and {@link #humanizeShort(long)} write a duration back:
 * {@code 1 day 2 hours 30 minutes 15 seconds} and {@code 1d 2h 30m 15s}. Milliseconds are
 * dropped, zero parts are left out and nothing at all reads {@code 0 seconds} / {@code 0s}.</p>
 */
public final class TimeUtil {

    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long TICK = 50L;

    private TimeUtil() {
    }

    /** Milliseconds in {@code text}; 0 when there is nothing to read. */
    public static long parseMillis(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        double total = 0;
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            boolean startsNumber = isDigit(c) || c == '.' && i + 1 < length && isDigit(text.charAt(i + 1));
            if (!startsNumber) {
                i++;
                continue;
            }
            int start = i;
            boolean dot = false;
            while (i < length && (isDigit(text.charAt(i)) || text.charAt(i) == '.' && !dot)) {
                dot |= text.charAt(i) == '.';
                i++;
            }
            double amount;
            try {
                amount = Double.parseDouble(text.substring(start, i));
            } catch (NumberFormatException unreadable) {
                continue;
            }
            while (i < length && text.charAt(i) == ' ') {
                i++;
            }
            int unitStart = i;
            while (i < length && Character.isLetter(text.charAt(i))) {
                i++;
            }
            total += amount * unitMillis(text.substring(unitStart, i));
        }
        if (!(total > 0)) {
            return 0L;
        }
        return total >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) total;
    }

    /** The parts a duration is written with, largest first. */
    public enum Unit {
        DAY(TimeUtil.DAY), HOUR(TimeUtil.HOUR), MINUTE(TimeUtil.MINUTE), SECOND(TimeUtil.SECOND);

        private static final Unit[] ALL = values();

        private final long millis;

        Unit(long millis) {
            this.millis = millis;
        }
    }

    /** Words for each {@link Unit}; plugins pass their own to write durations in another language. */
    public interface Labels {

        /** The full word: {@code day} or, with {@code plural}, {@code days}. */
        String longLabel(Unit unit, boolean plural);

        /** The letter written right after the number: {@code d}. */
        String shortLabel(Unit unit);
    }

    /** English labels: {@code day/days}, {@code d}. */
    public static final Labels ENGLISH = new Labels() {
        @Override
        public String longLabel(Unit unit, boolean plural) {
            String word = switch (unit) {
                case DAY -> "day";
                case HOUR -> "hour";
                case MINUTE -> "minute";
                case SECOND -> "second";
            };
            return plural ? word + "s" : word;
        }

        @Override
        public String shortLabel(Unit unit) {
            return switch (unit) {
                case DAY -> "d";
                case HOUR -> "h";
                case MINUTE -> "m";
                case SECOND -> "s";
            };
        }
    };

    /** {@code 1 day 2 hours 30 minutes 15 seconds}; {@code 0 seconds} below one second. */
    public static String humanize(long millis) {
        return humanize(millis, ENGLISH);
    }

    /** Same as {@link #humanize(long)} with the given words. */
    public static String humanize(long millis, Labels labels) {
        return write(millis, labels, false);
    }

    /** {@code 1d 2h 30m 15s}; {@code 0s} below one second. */
    public static String humanizeShort(long millis) {
        return humanizeShort(millis, ENGLISH);
    }

    /** Same as {@link #humanizeShort(long)} with the given letters. */
    public static String humanizeShort(long millis, Labels labels) {
        return write(millis, labels, true);
    }

    private static String write(long millis, Labels labels, boolean compact) {
        StringBuilder out = new StringBuilder(24);
        long left = Math.max(0L, millis);
        for (Unit unit : Unit.ALL) {
            long amount = left / unit.millis;
            if (amount == 0) {
                continue;
            }
            left -= amount * unit.millis;
            append(out, amount, unit, labels, compact);
        }
        if (out.isEmpty()) {
            append(out, 0, Unit.SECOND, labels, compact);
        }
        return out.toString();
    }

    private static void append(StringBuilder out, long amount, Unit unit, Labels labels, boolean compact) {
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(amount);
        if (compact) {
            out.append(labels.shortLabel(unit));
        } else {
            out.append(' ').append(labels.longLabel(unit, amount != 1));
        }
    }

    /** Ticks in {@code text} (milliseconds / 50, rounded down). */
    public static long parseTicks(String text) {
        return parseMillis(text) / TICK;
    }

    private static long unitMillis(String unit) {
        if (unit.isEmpty()) {
            return SECOND;
        }
        String lower = unit.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("ms")) {
            return 1L;
        }
        return switch (lower.charAt(0)) {
            case 'd' -> DAY;
            case 'h' -> HOUR;
            case 'm' -> MINUTE;
            case 's' -> SECOND;
            case 't' -> TICK;
            default -> 0L;
        };
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
