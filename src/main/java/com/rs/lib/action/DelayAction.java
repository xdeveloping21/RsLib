package com.rs.lib.action;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.rs.lib.util.TimeUtil;

/**
 * {@code [delay] <time>}: the lines after it, in the same list, run that much later, on the
 * thread that owns the player (the main thread on Paper). The engine does the waiting; this
 * class only reads the time.
 *
 * <p>A whole number is ticks ({@code [delay] 20}); anything else is a duration read like
 * {@link TimeUtil} reads one ({@code 1s}, {@code 1m30s}, {@code 0.5s}, {@code 10t}), rounded up
 * to whole ticks. Zero runs the rest at once. A time that cannot be read (none, {@code soon},
 * {@code 5x}) is reported once and the rest runs at once: a delay is never a reason to drop
 * actions. Pending lines are dropped when the player leaves or the plugin is disabled.</p>
 */
final class DelayAction implements ActionHandler {

    private static final long MILLIS_PER_TICK = 50L;

    /** Never called by the engine, which treats this tag apart; a direct call does nothing. */
    @Override
    public void run(Player player, String arg, ActionContext context) {
        // The engine schedules the rest of the list itself.
    }

    /** The ticks {@code arg} stands for; 0 (reported to {@code warn}) when it cannot be read. */
    static long ticks(String arg, Consumer<String> warn) {
        String text = arg == null ? "" : arg.trim();
        if (text.isEmpty()) {
            warn.accept("An action [delay] has no time; the next lines run at once.");
            return 0L;
        }
        if (isWhole(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException tooBig) {
                return Long.MAX_VALUE;
            }
        }
        long millis = TimeUtil.parseMillis(text);
        if (millis <= 0) {
            if (hasNonZeroDigit(text) || !onlyZeroTime(text)) {
                warn.accept("[delay] " + text + " is not a time (ticks, or a duration such as 1s or 1m30s);"
                        + " the next lines run at once.");
            }
            return 0L;
        }
        return millis / MILLIS_PER_TICK + (millis % MILLIS_PER_TICK == 0 ? 0 : 1);
    }

    private static boolean isWhole(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNonZeroDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '1' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    /** {@code 0s}, {@code 0.0 t}: zeros with a known unit, a valid zero wait. */
    private static boolean onlyZeroTime(String text) {
        return text.matches("(?i)0+(\\.0*)?\\s*(t|ticks?|s|sec|secs|seconds?|ms|m|min|mins|minutes?|h|hours?|d|days?)?");
    }
}
