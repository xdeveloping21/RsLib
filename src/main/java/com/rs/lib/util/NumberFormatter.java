package com.rs.lib.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Short and grouped number formats, and a tolerant reader for them. Pure and locale
 * independent, safe from any thread.
 *
 * <ul>
 *   <li>{@link #format(double)}: divides by 1000 while it can, with the suffixes
 *       {@code K, M, B, T, Qa, Qi}, rounds HALF_UP to two decimals and drops trailing zeros
 *       ({@code 1500 -> 1.5K}); when rounding reaches 1000 it moves to the next suffix
 *       ({@code 999999 -> 1M}).</li>
 *   <li>{@link #formatComma(double)}: thousands grouped with commas, a dot for decimals, up to
 *       two decimals HALF_UP ({@code 1234567.891 -> 1,234,567.89}).</li>
 *   <li>{@link #parseFormatted(String)}: reads both forms back, see its notes.</li>
 * </ul>
 *
 * <p>NaN and infinities are written the way Java writes them ({@code NaN}, {@code Infinity},
 * {@code -Infinity}).</p>
 */
public final class NumberFormatter {

    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Qa", "Qi"};
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);

    private NumberFormatter() {
    }

    /** Short form with a suffix: {@code 1500 -> 1.5K}, {@code 2000000 -> 2M}, {@code 12.345 -> 12.35}. */
    public static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        BigDecimal v = BigDecimal.valueOf(Math.abs(value));
        int index = 0;
        while (v.compareTo(THOUSAND) >= 0 && index < SUFFIXES.length - 1) {
            v = v.movePointLeft(3);
            index++;
        }
        BigDecimal rounded = v.setScale(2, RoundingMode.HALF_UP);
        if (rounded.compareTo(THOUSAND) >= 0 && index < SUFFIXES.length - 1) {
            rounded = rounded.movePointLeft(3).setScale(2, RoundingMode.HALF_UP);
            index++;
        }
        if (rounded.signum() == 0) {
            return "0";
        }
        String digits = rounded.stripTrailingZeros().toPlainString();
        return (value < 0 ? "-" : "") + digits + SUFFIXES[index];
    }

    /** Grouped form: {@code 1500 -> 1,500}, {@code 1234.5 -> 1,234.5}, {@code 0.125 -> 0.13}. */
    public static String formatComma(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        BigDecimal rounded = BigDecimal.valueOf(Math.abs(value)).setScale(2, RoundingMode.HALF_UP);
        if (rounded.signum() == 0) {
            return "0";
        }
        String plain = rounded.stripTrailingZeros().toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        StringBuilder out = new StringBuilder(plain.length() + whole.length() / 3 + 1);
        if (value < 0) {
            out.append('-');
        }
        int first = whole.length() % 3 == 0 ? 3 : whole.length() % 3;
        out.append(whole, 0, first);
        for (int i = first; i < whole.length(); i += 3) {
            out.append(',').append(whole, i, i + 3);
        }
        if (dot >= 0) {
            out.append(plain, dot, plain.length());
        }
        return out.toString();
    }

    /**
     * Reads a number written by people or by {@link #format}/{@link #formatComma}.
     *
     * <p>Tolerant: surrounding spaces are ignored; a suffix {@code K, M, B, T, Qa, Qi} in any
     * case multiplies by powers of 1000 ({@code 1.5M}); a sign and an exponent are accepted.
     * Separators: with both a comma and a dot, the last one is the decimal mark and the other
     * groups thousands ({@code 1,234.5}, {@code 1.234,5}); a single comma followed by exactly
     * three digits groups ({@code 1,500 -> 1500}), any other single comma is the decimal mark
     * ({@code 1,5 -> 1.5}); several commas group. {@code NaN} and {@code Infinity} read as
     * themselves.</p>
     *
     * @throws NumberFormatException when the text is null, empty, has an unknown suffix or is
     *                               not a number
     */
    public static double parseFormatted(String text) {
        if (text == null) {
            throw new NumberFormatException("null");
        }
        String t = text.trim();
        if (t.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        int end = t.length();
        while (end > 0 && Character.isLetter(t.charAt(end - 1))) {
            end--;
        }
        String number = t.substring(0, end).trim();
        String suffix = t.substring(end);
        if (number.isEmpty() || number.equals("-") || number.equals("+")) {
            // "NaN", "Infinity", "-Infinity" or a lone suffix.
            return Double.parseDouble(t);
        }
        int power = 0;
        if (!suffix.isEmpty()) {
            power = suffixPower(suffix);
            if (power < 0) {
                throw new NumberFormatException("unknown suffix '" + suffix + "' in '" + text + "'");
            }
        }
        double value = Double.parseDouble(separators(number));
        return power == 0 ? value : value * Math.pow(1000, power);
    }

    private static int suffixPower(String suffix) {
        String upper = suffix.toUpperCase(Locale.ROOT);
        for (int i = 1; i < SUFFIXES.length; i++) {
            if (SUFFIXES[i].toUpperCase(Locale.ROOT).equals(upper)) {
                return i;
            }
        }
        return -1;
    }

    private static String separators(String number) {
        int lastComma = number.lastIndexOf(',');
        if (lastComma < 0) {
            return number;
        }
        int lastDot = number.lastIndexOf('.');
        if (lastDot >= 0) {
            if (lastComma > lastDot) {
                return number.replace(".", "").replace(',', '.');
            }
            return number.replace(",", "");
        }
        if (number.indexOf(',') != lastComma) {
            return number.replace(",", "");
        }
        int after = number.length() - lastComma - 1;
        boolean threeDigits = after == 3;
        for (int i = lastComma + 1; threeDigits && i < number.length(); i++) {
            threeDigits = Character.isDigit(number.charAt(i));
        }
        return threeDigits ? number.replace(",", "") : number.replace(',', '.');
    }
}
