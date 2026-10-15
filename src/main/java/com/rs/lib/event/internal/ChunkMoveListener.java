package com.rs.lib.event.internal;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import com.rs.lib.event.RsChunkMoveEvent;

/**
 * Fires {@link RsChunkMoveEvent} when a move crosses into another chunk or world. Shared by
 * every plugin and registered once by RsLib.
 *
 * <p>{@code PlayerMoveEvent} fires many times per second per player, so a move inside the same
 * chunk returns after comparing four block coordinates and the world's identity, without
 * allocating anything. A crossing nobody listens to returns as well; only then are the two
 * locations copied for the event. Cancelling the event cancels the move.</p>
 */
public final class ChunkMoveListener implements Listener {

    private static final ChunkMoveListener SHARED = new ChunkMoveListener(EventSink.SERVER);

    private final EventSink sink;

    ChunkMoveListener(EventSink sink) {
        this.sink = sink;
    }

    /** The instance RsLib registers. */
    public static Listener shared() {
        return SHARED;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameChunk(from, to) || !sink.listened(RsChunkMoveEvent.getHandlerList())) {
            return;
        }
        RsChunkMoveEvent moved = new RsChunkMoveEvent(event.getPlayer(), from.clone(), to.clone());
        sink.call(moved);
        if (moved.isCancelled()) {
            event.setCancelled(true);
        }
    }

    /** True when both are in the same chunk ({@code block >> 4}) of the same world (by identity). */
    static boolean sameChunk(Location a, Location b) {
        return (a.getBlockX() >> 4) == (b.getBlockX() >> 4)
                && (a.getBlockZ() >> 4) == (b.getBlockZ() >> 4)
                && a.getWorld() == b.getWorld();
    }
}
