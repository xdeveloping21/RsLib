package com.rs.lib.text;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.rs.lib.Ph;
import com.rs.lib.util.NumberFormatter;

/**
 * The local placeholder scanner behind {@code RsText.applyLocals}.
 *
 * <p>One pass over {@code %key%} and {@code {key}} tokens: a token runs from the opening
 * delimiter to the next closing one of the same kind ({@code %} or {@code }}); an empty token
 * does not count. When the resolver returns a value the token is replaced and scanning goes
 * on after it, so values are never scanned again. When it returns null the opening delimiter
 * is kept as text and scanning goes on from the next character, so the token stays intact
 * (PlaceholderAPI tokens survive for the next stage) and a stray {@code %} or {@code {} never
 * hides a later token.</p>
 *
 * <p>Format suggestions: when the whole token does not resolve and ends with {@code :raw},
 * {@code :short} or {@code :grouped} (any case), the key without the suffix is resolved and,
 * when its value is a number (an abbreviated {@code 1.5M} too), formatted again. A value that
 * is not a number, or whose number is not finite, is returned unchanged.</p>
 */
final class Locals {

    /** Receives the pieces of a scanned text, in order. */
    interface Sink {
        /** Text copied from the source, {@code s[from, to)}. */
        void text(String s, int from, int to);

        /** A resolved value. */
        void value(String value);
    }

    private static final String[] SUGGESTIONS = {":raw", ":short", ":grouped"};

    private Locals() {
    }

    /** Whether {@code s} may hold a token at all. */
    static boolean mayHaveTokens(String s) {
        return s.indexOf('%') >= 0 || s.indexOf('{') >= 0;
    }

    /** Scans {@code s}, sending text pieces and resolved values to {@code sink}. */
    static void scan(String s, Function<String, String> resolver, Sink sink) {
        int n = s.length();
        int textStart = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '%' || c == '{') {
                int close = s.indexOf(c == '%' ? '%' : '}', i + 1);
                if (close > i + 1) {
                    String value = resolve(s.substring(i + 1, close), resolver);
                    if (value != null) {
                        if (textStart < i) {
                            sink.text(s, textStart, i);
                        }
                        sink.value(value);
                        i = close + 1;
                        textStart = i;
                        continue;
                    }
                }
            }
            i++;
        }
        if (textStart < n) {
            sink.text(s, textStart, n);
        }
    }

    /** {@link #scan} into a string. */
    static String apply(String s, Function<String, String> resolver) {
        if (s == null || resolver == null || !mayHaveTokens(s)) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        scan(s, resolver, new Sink() {
            @Override
            public void text(String src, int from, int to) {
                out.append(src, from, to);
            }

            @Override
            public void value(String value) {
                out.append(value);
            }
        });
        return out.toString();
    }

    /** A resolver over pairs: the last pair with a key wins; literal values are encoded. */
    static Function<String, String> resolverOf(Ph... phs) {
        if (phs == null || phs.length == 0) {
            return key -> null;
        }
        if (phs.length == 1 && phs[0] != null) {
            Ph only = phs[0];
            String value = only.literal() ? RsText.literal(only.value()) : only.value();
            String key = only.key();
            return k -> key.equals(k) ? value : null;
        }
        Map<String, String> map = new HashMap<>(phs.length * 2);
        for (Ph ph : phs) {
            if (ph != null) {
                map.put(ph.key(), ph.literal() ? RsText.literal(ph.value()) : ph.value());
            }
        }
        return map::get;
    }

    private static String resolve(String key, Function<String, String> resolver) {
        String value = resolver.apply(key);
        if (value != null) {
            return value;
        }
        for (String suffix : SUGGESTIONS) {
            int base = key.length() - suffix.length();
            if (base > 0 && key.regionMatches(true, base, suffix, 0, suffix.length())) {
                String raw = resolver.apply(key.substring(0, base));
                return raw == null ? null : suggest(raw, suffix);
            }
        }
        return null;
    }

    private static String suggest(String value, String suffix) {
        double number;
        try {
            number = NumberFormatter.parseFormatted(value);
        } catch (NumberFormatException notNumber) {
            return value;
        }
        if (Double.isNaN(number) || Double.isInfinite(number)) {
            return value;
        }
        return switch (suffix.toLowerCase(Locale.ROOT)) {
            case ":raw" -> BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
            case ":short" -> NumberFormatter.format(number);
            default -> NumberFormatter.formatComma(number);
        };
    }
}
