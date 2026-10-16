package com.rs.lib.chat;

import java.util.function.Predicate;

import org.bukkit.entity.Player;

/**
 * One chat message as RsLib sees it, whichever chat event the server fires
 * ({@link ChatEvents}). Handed to the handlers on the chat thread, which is not the main thread.
 */
public interface ChatLine {

    /** The player who wrote the message. */
    Player speaker();

    /** What the player typed, as plain text (never parsed as colours or tags). */
    String text();

    boolean cancelled();

    /** Takes the message out of the public chat. */
    void cancel();

    /**
     * Removes from the message's audience every player for whom {@code drop} is true; the
     * console and other non-player audiences stay. Returns false when the audience cannot be
     * changed (another plugin handed it over as unmodifiable); nothing is removed then.
     */
    boolean removeViewers(Predicate<Player> drop);
}
