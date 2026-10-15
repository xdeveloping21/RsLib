package com.rs.lib.util;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fair rounding and Roman numerals. Pure, safe from any thread.
 *
 * <p>Fair rounding keeps the fraction as a chance: {@code 2.3} becomes 3 with a 30 % chance
 * and 2 otherwise, so the average over many draws is the original value (drops, rewards,
 * multipliers). Negative values work the same way ({@code -2.3} is -2 with a 70 % chance).
 * Values beyond the range of the result saturate at its limit.</p>
 */
public final class MathUtil {

    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private MathUtil() {
    }

    /** {@code value} rounded up with a chance equal to its fraction, using {@link ThreadLocalRandom}. */
    public static int fairIntFromDouble(double value) {
        return fairIntFromDouble(value, ThreadLocalRandom.current());
    }

    /**
     * {@code value} rounded up with a chance equal to its fraction.
     *
     * @throws IllegalArgumentException when {@code value} is NaN
     */
    public static int fairIntFromDouble(double value, Random random) {
        long rounded = fairLongFromDouble(value, random);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded));
    }

    /** {@code value} rounded up with a chance equal to its fraction, using {@link ThreadLocalRandom}. */
    public static long fairLongFromDouble(double value) {
        return fairLongFromDouble(value, ThreadLocalRandom.current());
    }

    /**
     * {@code value} rounded up with a chance equal to its fraction.
     *
     * @throws IllegalArgumentException when {@code value} is NaN
     */
    public static long fairLongFromDouble(double value, Random random) {
        Objects.requireNonNull(random, "random");
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("NaN cannot be rounded");
        }
        double floor = Math.floor(value);
        if (floor >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (floor <= Long.MIN_VALUE) {
            return Long.MIN_VALUE;
        }
        double fraction = value - floor;
        long whole = (long) floor;
        return fraction > 0 && random.nextDouble() < fraction ? whole + 1 : whole;
    }

    /**
     * {@code number} in Roman numerals ({@code 4 -> IV}, {@code 1994 -> MCMXCIV}) from 1 to
     * 3999; any other number is written in decimal ({@code 0 -> 0}, {@code 5000 -> 5000}).
     */
    public static String convertToRoman(int number) {
        if (number < 1 || number > 3999) {
            return Integer.toString(number);
        }
        StringBuilder out = new StringBuilder(15);
        int left = number;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (left >= ROMAN_VALUES[i]) {
                out.append(ROMAN_SYMBOLS[i]);
                left -= ROMAN_VALUES[i];
            }
        }
        return out.toString();
    }
}
