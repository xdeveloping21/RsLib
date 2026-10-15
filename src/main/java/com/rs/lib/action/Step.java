package com.rs.lib.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * One action line, parsed once: its guards, its tag and its argument as written.
 *
 * <p>The line is trimmed; while it starts with {@code [word]} and the word is a guard, the
 * guard is kept and the rest is read. What is left is {@code [tag] argument}, or a whole line
 * without a leading tag, which is a {@code [message]}. Tags are lower case.</p>
 *
 * @param raw      the line as written, trimmed
 * @param guards   the guards, in order
 * @param tag      the action tag, lower case
 * @param arg      the argument as written, trimmed (empty when there is none)
 * @param constant whether the argument holds no placeholder at all ({@code %} or <code>{</code>)
 * @param source   where the line was read from (for warnings), or null
 */
record Step(String raw, Guard[] guards, String tag, String arg, boolean constant, String source) {

    static final String MESSAGE = "message";
    private static final Guard[] NO_GUARDS = new Guard[0];

    /** The step for {@code line}, or null for a blank line or one made only of guards. */
    static Step parse(String line, String source, Consumer<String> warn) {
        if (line == null) {
            return null;
        }
        String rest = line.trim();
        if (rest.isEmpty()) {
            return null;
        }
        String raw = rest;
        List<Guard> guards = null;
        while (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                break;
            }
            Guard guard = Guard.parse(rest.substring(1, close).trim().toLowerCase(Locale.ROOT), warn);
            if (guard == null) {
                break;
            }
            if (guards == null) {
                guards = new ArrayList<>(2);
            }
            guards.add(guard);
            rest = rest.substring(close + 1).trim();
        }
        if (rest.isEmpty()) {
            warn.accept("Action line '" + raw + "' has guards but no action; it does nothing.");
            return null;
        }
        String tag = MESSAGE;
        String arg = rest;
        int close = rest.startsWith("[") ? rest.indexOf(']') : -1;
        if (close > 0) {
            tag = rest.substring(1, close).trim().toLowerCase(Locale.ROOT);
            arg = rest.substring(close + 1).trim();
        }
        boolean constant = arg.indexOf('%') < 0 && arg.indexOf('{') < 0;
        return new Step(raw, guards == null ? NO_GUARDS : guards.toArray(Guard[]::new), tag, arg, constant, source);
    }

    /** {@code " (in <source>)"}, or nothing. */
    String where() {
        return source == null ? "" : " (in " + source + ")";
    }
}
