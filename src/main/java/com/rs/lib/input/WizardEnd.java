package com.rs.lib.input;

/** How a {@link Wizard} ended. Exactly one is delivered, once, per wizard. */
public enum WizardEnd {
    /** Every question was answered and the player confirmed the summary: the only ending that applies anything. */
    CONFIRMED,
    /** The player typed a cancel word, ran out of attempts, or the plugin cancelled it. */
    CANCELLED,
    /** A question waited longer than its step timeout. */
    TIMED_OUT,
    /** The player left the server (or was not online when it started). */
    LEFT,
    /** Another chat question for the same player, from any plugin, took the place of the open one. */
    REPLACED,
    /** The plugin is shutting down. */
    SHUT_DOWN;

    /** The status the wizard's open question ends with when the wizard ends this way. */
    InputStatus questionStatus() {
        return switch (this) {
            case TIMED_OUT -> InputStatus.TIMED_OUT;
            case LEFT -> InputStatus.LEFT;
            case REPLACED -> InputStatus.REPLACED;
            case SHUT_DOWN -> InputStatus.SHUT_DOWN;
            case CONFIRMED, CANCELLED -> InputStatus.CANCELLED;
        };
    }

    /** The wizard ending a question's ending leads to (never {@link #CONFIRMED}). */
    static WizardEnd of(InputStatus status) {
        return switch (status) {
            case TIMED_OUT -> TIMED_OUT;
            case LEFT -> LEFT;
            case REPLACED -> REPLACED;
            case SHUT_DOWN -> SHUT_DOWN;
            case CANCELLED, COMPLETED -> CANCELLED;
        };
    }
}
