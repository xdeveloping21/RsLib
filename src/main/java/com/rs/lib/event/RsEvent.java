package com.rs.lib.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * Base of a plugin's own cancellable events. There is no event bus of RsLib's own: events go
 * through Bukkit's plugin manager like any other, and are listened to with
 * {@code @EventHandler}.
 *
 * <pre>
 * public final class RewardEvent extends RsEvent {
 *     private static final HandlerList HANDLERS = new HandlerList();
 *
 *     public HandlerList getHandlers() { return HANDLERS; }
 *     public static HandlerList getHandlerList() { return HANDLERS; }
 * }
 *
 * if (new RewardEvent().call()) { ... }
 * </pre>
 *
 * <p>Every concrete event declares its own static {@code HandlerList} and the pair of
 * methods above. This class has none on purpose: a list shared with the base would mix the
 * listeners of every subclass.</p>
 */
public abstract class RsEvent extends Event implements Cancellable {

    private boolean cancelled;

    /** A synchronous event: call it on the main thread (or the owning region on Folia). */
    protected RsEvent() {
        super();
    }

    /** {@code async} true for an event fired off the main thread. */
    protected RsEvent(boolean async) {
        super(async);
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
