package com.rs.lib.input;

/** How a chat question ended. */
public enum InputStatus {
    /** The player answered and the answer was accepted. */
    COMPLETED,
    /** The player typed a cancel word, ran out of attempts, or the plugin cancelled it. */
    CANCELLED,
    /** Nobody answered in time. */
    TIMED_OUT,
    /** The player left the server (or was not online when the question opened). */
    LEFT,
    /** Another question for the same player, from any plugin, took its place. */
    REPLACED,
    /** The plugin is shutting down. */
    SHUT_DOWN
}
