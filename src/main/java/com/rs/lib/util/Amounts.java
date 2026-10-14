package com.rs.lib.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Strict, fail-closed reading of amounts a player or an owner typed (pure, no Bukkit).
 *
 * <p>Where {@link NumberFormatter#parseFormatted(String)} is tolerant (it accepts a sign,
 * an exponent and every separator layout), this class answers "is this exactly an amount,
 * and which one?" and returns empty for anything else, so a price, a bet or a deposit can
 * never be read as something the owner did not mean. Rejected: {@code null}, blank,
 * {@code NaN}, {@code Infinity}, exponents ({@code 1e9}), hex, letters other than one
 * known suffix, a sign where none is allowed, a separator at either end, malformed
 * grouping ({@code 1,50,0}), more than {@value #MAX_LENGTH} characters, and the ambiguous
 * single separator followed by exactly three digits ({@code 1.500} / {@code 1,500}), which
 * reads as 1500 in one locale and 1.5 in another; {@code 1500}, {@code 1.5} and
 * {@code 1,500.25} are unambiguous and accepted, and so is {@code 0.500}.</p>
 *
 * <p>Suffixes, case-insensitive: {@code K, M, B, T, Qa, Qi} (powers of 1000, the same set
 * as {@link NumberFormatter}). Spaces, no-break spaces and underscores are ignored, so
 * {@code 3 M} and {@code 1_000} read as expected. Values are exact {@link BigDecimal}s;
 * the {@code double} and {@code long} views reject what does not fit (a non-finite double,
 * a fraction or an overflow for a whole number). Safe from any thread.</p>
 *
 * <p>Written for Raul's plugins (2026-10-05), carried over to RsLib unchanged.</p>
 */
public final class Amounts {

    /** Longest input accepted, after trimming; anything longer is rejected outright. */
    public static final int MAX_LENGTH = 40;

    private static final String[] SUFFIXES = {"K", "M", "B", "T", "QA", "QI"};
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);

    private Amounts() {
    }

    /** A non-negative amount ({@code 10M}, {@code 1,500.25}, {@code 0.5k}); empty when invalid or negative. */
    public static Optional<BigDecimal> parse(String input) {
        return read(input, false);
    }

    /** Same as {@link #parse} but a leading {@code -} is allowed ({@code -1} for "unlimited", an offset). */
    public static Optional<BigDecimal> parseSigned(String input) {
        return read(input, true);
    }

    /** {@link #parse} as a finite {@code double}; empty when invalid, negative or too large for a double. */
    public static OptionalDouble parseDouble(String input) {
        return toDouble(read(input, false));
    }

    /** {@link #parseSigned} as a finite {@code double}. */
    public static OptionalDouble parseSignedDouble(String input) {
        return toDouble(read(input, true));
    }

    /**
     * A non-negative whole amount ({@code 64}, {@code 2k}, {@code 1.5m}); empty when invalid,
     * negative, fractional after the suffix ({@code 1.2345k}) or beyond {@code Long.MAX_VALUE}.
     */
    public static OptionalLong parseWhole(String input) {
        return toLong(read(input, false));
    }

    /** {@link #parseWhole} with a leading {@code -} allowed. */
    public static OptionalLong parseSignedWhole(String input) {
        return toLong(read(input, true));
    }

    /**
     * Whole amount inside {@code [min, max]}; empty when invalid or out of range.
     *
     * @throws IllegalArgumentException when {@code min > max}
     */
    public static OptionalLong parseWhole(String input, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        OptionalLong value = min < 0 ? parseSignedWhole(input) : parseWhole(input);
        if (value.isEmpty() || value.getAsLong() < min || value.getAsLong() > max) {
            return OptionalLong.empty();
        }
        return value;
    }

    private static OptionalDouble toDouble(Optional<BigDecimal> value) {
        if (value.isEmpty()) {
            return OptionalDouble.empty();
        }
        double d = value.get().doubleValue();
        return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
    }

    private static OptionalLong toLong(Optional<BigDecimal> value) {
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(value.get().longValueExact());
        } catch (ArithmeticException fractionOrOverflow) {
            return OptionalLong.empty();
        }
    }

    private static Optional<BigDecimal> read(String input, boolean signed) {
        if (input == null) {
            return Optional.empty();
        }
        String text = strip(input);
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        boolean negative = false;
        char first = text.charAt(0);
        if (first == '-' || first == '+') {
            if (first == '-') {
                if (!signed) {
                    return Optional.empty();
                }
                negative = true;
            }
            text = text.substring(1);
        }
        int end = text.length();
        while (end > 0 && Character.isLetter(text.charAt(end - 1))) {
            end--;
        }
        int power = suffixPower(text.substring(end));
        if (power < 0) {
            return Optional.empty();
        }
        String number = normalize(text.substring(0, end));
        if (number == null) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(number);
        if (power > 0) {
            value = value.multiply(THOUSAND.pow(power));
        }
        if (negative) {
            value = value.negate();
        }
        return Optional.of(value.signum() == 0 ? BigDecimal.ZERO : value);
    }

    private static String strip(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != ' ' && c != '_' && c != ' ' && c != ' ' && c != '\t') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 0 for no suffix, 1..6 for K..Qi, -1 for an unknown one. */
    private static int suffixPower(String suffix) {
        if (suffix.isEmpty()) {
            return 0;
        }
        String upper = suffix.toUpperCase(Locale.ROOT);
        for (int i = 0; i < SUFFIXES.length; i++) {
            if (SUFFIXES[i].equals(upper)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Turns the digits-and-separators part into {@code digits[.digits]}, or null when it is
     * not a well-formed or unambiguous number.
     */
    private static String normalize(String number) {
        if (number.isEmpty() || !Character.isDigit(number.charAt(0))
                || !Character.isDigit(number.charAt(number.length() - 1))) {
            return null;
        }
        int dots = 0;
        int commas = 0;
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c == ',') {
                commas++;
            } else if (c < '0' || c > '9') {
                return null;
            }
        }
        if (dots == 0 && commas == 0) {
            return number;
        }
        if (dots > 0 && commas > 0) {
            // Both present: the last one is the decimal mark, appears once, and the other groups.
            char decimal = number.lastIndexOf('.') > number.lastIndexOf(',') ? '.' : ',';
            char grouping = decimal == '.' ? ',' : '.';
            if ((decimal == '.' ? dots : commas) != 1) {
                return null;
            }
            int mark = number.indexOf(decimal);
            String integer = number.substring(0, mark);
            return grouped(integer, grouping) ? integer.replace(String.valueOf(grouping), "")
                    + "." + number.substring(mark + 1) : null;
        }
        char sep = dots > 0 ? '.' : ',';
        int count = Math.max(dots, commas);
        if (count > 1) {
            // Several of the same mark can only be grouping.
            return grouped(number, sep) ? number.replace(String.valueOf(sep), "") : null;
        }
        int mark = number.indexOf(sep);
        String integer = number.substring(0, mark);
        String fraction = number.substring(mark + 1);
        boolean zeroInteger = integer.chars().allMatch(c -> c == '0');
        if (fraction.length() == 3 && integer.length() <= 3 && !zeroInteger) {
            // 1.500 / 1,500: thousands in one locale, a decimal in the other. Refused.
            return null;
        }
        return integer + "." + fraction;
    }

    /** True when {@code text} is 1-3 digits followed by groups of exactly 3 digits split by {@code sep}. */
    private static boolean grouped(String text, char sep) {
        String[] groups = text.split(java.util.regex.Pattern.quote(String.valueOf(sep)), -1);
        if (groups.length < 2 || groups[0].isEmpty() || groups[0].length() > 3) {
            return false;
        }
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3) {
                return false;
            }
        }
        return true;
    }
}
