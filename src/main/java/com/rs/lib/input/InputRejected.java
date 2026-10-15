package com.rs.lib.input;

import com.rs.lib.Ph;

/**
 * An answer an {@link InputParser} refused: the player gets the message {@code langKey} and
 * the question keeps waiting. Cheap to throw: it carries no stack trace.
 */
public final class InputRejected extends Exception {

    private static final Ph[] NONE = new Ph[0];

    private final String langKey;
    private final transient Ph[] placeholders;

    public InputRejected(String langKey, Ph... placeholders) {
        super(langKey, null, false, false);
        this.langKey = langKey;
        this.placeholders = placeholders == null ? NONE : placeholders.clone();
    }

    /** The message to send. */
    public String langKey() {
        return langKey;
    }

    /** Its placeholders. */
    public Ph[] placeholders() {
        return placeholders.clone();
    }
}
