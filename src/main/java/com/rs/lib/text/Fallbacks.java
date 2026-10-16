package com.rs.lib.text;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Placeholders with a fallback text: {@code %name|text%} and {@code {name|text}}.
 *
 * <p>Tokens are found the way {@link Locals} finds them: from an opening {@code %} or
 * <code>{</code> to the next closing delimiter of the same kind. A token is a fallback token
 * when its content holds a {@code |} and the part before the first one (the key, with its
 * format: {@code amount:short}) is not empty and has no whitespace. The fallback is everything
 * after that {@code |} up to the closing delimiter, and may be empty. Any other {@code |} is
 * plain text. When a token is not a fallback token, scanning goes on from the next character,
 * as in {@link Locals}.</p>
 *
 * <p>The fallback is text: placeholders inside it are not resolved, colors in it are.</p>
 */
final class Fallbacks {

    private Fallbacks() {
    }

    /** Where {@code content} (a token without its delimiters) splits key and fallback, or -1. */
    static int split(String content) {
        int bar = content.indexOf('|');
        if (bar <= 0) {
            return -1;
        }
        for (int i = 0; i < bar; i++) {
            if (Character.isWhitespace(content.charAt(i))) {
                return -1;
            }
        }
        return bar;
    }

    /** Whether {@code s} may hold a fallback token at all. */
    static boolean mayHave(String s) {
        return s != null && s.indexOf('|') >= 0 && (s.indexOf('%') >= 0 || s.indexOf('{') >= 0);
    }

    /**
     * Every fallback token of {@code s} replaced by its fallback: nothing resolved it, so its
     * value is missing. Returns the same instance when there is none.
     */
    static String sweep(String s) {
        if (!mayHave(s)) {
            return s;
        }
        String out = resolve(s, true, null, key -> null);
        return out == null ? s : out;
    }

    /**
     * Resolves the fallback tokens of {@code s}: each key goes through {@code value} (null or
     * empty means missing, and the fallback is used) and the text between tokens through
     * {@code between} (null keeps it). Only {@code %} tokens unless {@code braces}. Returns null
     * when {@code s} has no fallback token, so the caller can take its usual path.
     */
    static String resolve(String s, boolean braces, UnaryOperator<String> between, Function<String, String> value) {
        if (!mayHave(s)) {
            return null;
        }
        StringBuilder out = null;
        int n = s.length();
        int textStart = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '%' || (braces && c == '{')) {
                int close = s.indexOf(c == '%' ? '%' : '}', i + 1);
                if (close > i + 1) {
                    String content = s.substring(i + 1, close);
                    int bar = split(content);
                    if (bar > 0) {
                        if (out == null) {
                            out = new StringBuilder(n);
                        }
                        appendBetween(out, s.substring(textStart, i), between);
                        String found = value.apply(content.substring(0, bar));
                        out.append(found == null || found.isEmpty() ? content.substring(bar + 1) : found);
                        i = close + 1;
                        textStart = i;
                        continue;
                    }
                }
            }
            i++;
        }
        if (out == null) {
            return null;
        }
        appendBetween(out, s.substring(textStart), between);
        return out.toString();
    }

    private static void appendBetween(StringBuilder out, String text, UnaryOperator<String> between) {
        if (text.isEmpty()) {
            return;
        }
        String done = between == null ? text : between.apply(text);
        out.append(done == null ? text : done);
    }
}
