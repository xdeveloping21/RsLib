package com.rs.lib.action;

import org.bukkit.entity.Player;

/**
 * One action tag, registered with {@link ActionEngine#register(String, ActionHandler)}.
 *
 * <p>Runs on the thread that owns the player (the main thread on Paper). {@code arg} is the
 * text after the tag with local placeholders and then PlaceholderAPI already resolved, and
 * player-typed values back as plain characters; it is empty when the line has no argument.
 * {@code player} is null only when the plugin ran the list without one.</p>
 */
@FunctionalInterface
public interface ActionHandler {

    void run(Player player, String arg, ActionContext context);
}
