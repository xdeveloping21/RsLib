package com.rs.lib.chat;

import org.bukkit.entity.Player;

/**
 * Whether one player reads another's chat messages ({@code rs().chat().rule(...)}). Every rule of
 * every plugin must agree: a player who any rule refuses is taken off the message's audience.
 */
@FunctionalInterface
public interface AudienceRule {

    /**
     * Called on the chat thread (not the main thread) for each player who would read the
     * message, never for the speaker. Must be quick and only read shared state.
     */
    boolean canRead(Player reader, Player speaker);
}
