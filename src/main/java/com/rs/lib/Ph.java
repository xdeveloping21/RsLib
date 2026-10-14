package com.rs.lib;

import java.util.Objects;

/**
 * A local placeholder: a key, its value and how the value is shown.
 *
 * <p>The key is written without delimiters and matches both {@code %key%} and {@code {key}}
 * in a text. A value made with {@link #of} is part of the template, so colors, MiniMessage
 * tags and placeholders inside it are rendered (what a value read from a config wants, such
 * as a rank prefix {@code &c[Admin]}). A value made with {@link #literal} is shown exactly
 * as written: use it for anything a player typed, so nobody can inject colors, clicks,
 * hovers or another plugin's placeholders.</p>
 *
 * @param key     placeholder name without delimiters
 * @param value   replacement text, never null
 * @param literal whether the value is shown as plain text instead of being rendered
 */
public record Ph(String key, String value, boolean literal) {

    public Ph {
        Objects.requireNonNull(key, "key");
        value = value == null ? "null" : value;
    }

    /** A formatted pair: the value is rendered like the text around it. */
    public Ph(String key, String value) {
        this(key, value, false);
    }

    /** A formatted pair from any value, converted with {@link String#valueOf(Object)}. */
    public static Ph of(String key, Object value) {
        return new Ph(key, String.valueOf(value), false);
    }

    /** A literal pair: keeps the surrounding style but is never read as formatting or placeholders. */
    public static Ph literal(String key, Object value) {
        return new Ph(key, String.valueOf(value), true);
    }
}
