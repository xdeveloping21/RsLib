package com.rs.lib.chat;

import org.bukkit.entity.Player;

/**
 * Reads a chat message before anything else on the server and decides whether to take it out
 * of the public chat ({@code rs().chat().intercept(...)}).
 */
@FunctionalInterface
public interface ChatInterceptor {

    /**
     * Called on the chat thread (not the main thread) with what {@code player} typed, as plain
     * literal text. Returns true to take the message: it is cancelled and no later interceptor,
     * chat formatter or bridge sees it. Returns false to leave it in the chat.
     */
    boolean take(Player player, String text);
}
