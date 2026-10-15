package com.rs.lib.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerEvent;

/**
 * Base of cancellable events about one player; {@link #getPlayer()} is that player. Works like
 * {@link RsEvent}: each concrete event declares its own static {@code HandlerList}.
 */
public abstract class RsPlayerEvent extends PlayerEvent implements Cancellable {

    private boolean cancelled;

    /** A synchronous event about {@code player}. */
    protected RsPlayerEvent(Player player) {
        super(player);
    }

    /** {@code async} true for an event fired off the main thread. */
    protected RsPlayerEvent(Player player, boolean async) {
        super(player, async);
    }

    /**
     * Fires the event through Bukkit's plugin manager.
     *
     * @return true when no listener cancelled it
     */
    public boolean call() {
        Bukkit.getPluginManager().callEvent(this);
        return !cancelled;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
