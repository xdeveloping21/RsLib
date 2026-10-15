package com.rs.lib.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Inventory slots written in a config: {@code 4}, {@code "4"}, {@code "0-8"}, {@code "8-0"},
 * {@code "0,2,4-6"} or a list of any of those ({@code [0, "2-4", "9,10"]}).
 *
 * <p>The result keeps each slot once, in the order it first appears. Negative numbers,
 * unreadable tokens and ranges of more than {@value #MAX_RANGE} slots are dropped and reported
 * to the {@code warn} callback, one message each; the rest is still read. {@code null} reads as
 * no slots. Whole floating numbers ({@code 4.0}) are accepted, fractions are not. Pure, safe
 * from any thread.</p>
 */
public final class SlotParser {

    /** Largest number of slots one range may cover. */
    public static final int MAX_RANGE = 10_000;

    private static final Consumer<String> IGNORE = message -> {
    };

    private SlotParser() {
    }

    /** The slots in {@code raw}; problems are dropped silently. */
    public static int[] parse(Object raw) {
        return parse(raw, IGNORE);
    }

    /** The slots in {@code raw}; each problem is passed to {@code warn}. */
    public static int[] parse(Object raw, Consumer<String> warn) {
        Objects.requireNonNull(warn, "warn");
        Slots out = new Slots();
        read(raw, out, warn);
        return out.toArray();
    }

    private static void read(Object raw, Slots out, Consumer<String> warn) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Iterable<?> list) {
            for (Object element : list) {
                read(element, out, warn);
            }
        } else if (raw instanceof Object[] array) {
            for (Object element : array) {
                read(element, out, warn);
            }
        } else if (raw instanceof int[] array) {
            for (int slot : array) {
                number(slot, String.valueOf(slot), out, warn);
            }
        } else if (raw instanceof Number number) {
            readNumber(number, out, warn);
        } else {
            for (String token : raw.toString().split(",", -1)) {
                readToken(token.trim(), out, warn);
            }
        }
    }

    private static void readNumber(Number number, Slots out, Consumer<String> warn) {
        double value = number.doubleValue();
        if (value != Math.rint(value) || value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            warn.accept("'" + number + "' is not a slot number");
            return;
        }
        number((int) value, number.toString(), out, warn);
    }

    private static void readToken(String token, Slots out, Consumer<String> warn) {
        if (token.isEmpty()) {
            return;
        }
        int dash = token.indexOf('-', 1);
        if (dash < 0) {
            Integer value = integer(token);
            if (value == null) {
                warn.accept("'" + token + "' is not a slot number");
            } else {
                number(value, token, out, warn);
            }
            return;
        }
        Integer from = integer(token.substring(0, dash).trim());
        Integer to = integer(token.substring(dash + 1).trim());
        if (from == null || to == null) {
            warn.accept("'" + token + "' is not a slot range");
            return;
        }
        if (from < 0 || to < 0) {
            warn.accept("'" + token + "' has a negative slot");
            return;
        }
        int low = Math.min(from, to);
        int high = Math.max(from, to);
        if ((long) high - low + 1 > MAX_RANGE) {
            warn.accept("'" + token + "' covers more than " + MAX_RANGE + " slots");
            return;
        }
        for (int slot = low; slot <= high; slot++) {
            out.add(slot);
        }
    }

    private static void number(int value, String written, Slots out, Consumer<String> warn) {
        if (value < 0) {
            warn.accept("'" + written + "' is a negative slot");
        } else {
            out.add(value);
        }
    }

    /** {@code text} as an int (an optional sign then digits), or null. */
    private static Integer integer(String text) {
        if (text.isEmpty()) {
            return null;
        }
        int start = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        if (start == text.length()) {
            return null;
        }
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    /** Distinct non-negative ints in insertion order; inventory-sized slots are tracked in a bit set. */
    private static final class Slots {

        private static final int SMALL = 4096;

        private final long[] small = new long[SMALL / 64];
        private Set<Integer> large;
        private int[] values = new int[16];
        private int size;

        void add(int slot) {
            if (slot < SMALL) {
                long bit = 1L << slot;
                if ((small[slot >>> 6] & bit) != 0) {
                    return;
                }
                small[slot >>> 6] |= bit;
            } else {
                if (large == null) {
                    large = new HashSet<>();
                }
                if (!large.add(slot)) {
                    return;
                }
            }
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = slot;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
