package com.rs.lib.region;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Feeds {@link RegionTracker}. Shared by every plugin and registered once by RsLib.
 *
 * <p>Teleports are listened to apart from moves: {@code PlayerTeleportEvent} has its own handler
 * list, so a move listener never sees it, and portals and respawns go through neither (they
 * arrive as a world change or a respawn). Everything runs at {@code MONITOR}, after the move is
 * settled; a move inside the same block returns after comparing three coordinates and the
 * world.</p>
 */
final class RegionListener implements Listener {

    private final RegionTracker tracker;

    RegionListener(RegionTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }
        tracker.moved(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to != null) {
            tracker.teleported(event.getPlayer(), to);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        tracker.changedWorld(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        tracker.teleported(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        tracker.track(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.forget(event.getPlayer().getUniqueId());
    }

    /** True when both are the same block of the same world (by identity). */
    static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ()
                && a.getWorld() == b.getWorld();
    }
}
