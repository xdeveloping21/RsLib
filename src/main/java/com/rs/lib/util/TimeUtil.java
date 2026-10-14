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
