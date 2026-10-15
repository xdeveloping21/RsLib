package com.rs.lib.event;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/**
 * A player moved into another chunk or world by moving (walking, flying, riding, swimming).
 * Fired by RsLib from {@code PlayerMoveEvent}; teleports, joins and respawns do not fire it.
 * Cancelling it cancels the move.
 *
 * <p>The locations are copies taken for this event. The chunks are looked up when asked
 * for.</p>
 */
public final class RsChunkMoveEvent extends RsPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Location from;
    private final Location to;

    public RsChunkMoveEvent(Player player, Location from, Location to) {
        super(player);
        this.from = from;
        this.to = to;
    }

    public Location fromLocation() {
        return from;
    }

    public Location toLocation() {
        return to;
    }

    public Chunk fromChunk() {
        return from.getChunk();
    }

    public Chunk toChunk() {
        return to.getChunk();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
