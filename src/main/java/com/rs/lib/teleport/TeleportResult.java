package com.rs.lib.teleport;

/** What {@link Teleports#request} did. */
public enum TeleportResult {

    /** The wait started; the player is moved when it ends unless something cancels it. */
    WARMUP_STARTED,
    /** No wait: the player is being moved now. */
    TELEPORTED,
    /** The player already waits for a teleport of this plugin; nothing changed. */
    ALREADY_PENDING,
    /** The options' cooldown category is still running for the player. */
    ON_COOLDOWN,
    /** No player, no destination, the destination's world is not loaded, or the plugin is shutting down. */
    FAILED;

    /** True for {@link #WARMUP_STARTED} and {@link #TELEPORTED}. */
    public boolean accepted() {
        return this == WARMUP_STARTED || this == TELEPORTED;
    }

    /** The opposite of {@link #accepted()}. */
    public boolean rejected() {
        return !accepted();
    }
}
