package com.rs.lib.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Rs;
import com.rs.lib.event.RsRegionChangeEvent;

/**
 * Named regions with enter and leave, reached with {@code rs().regions()}.
 *
 * <pre>
 * rs().regions().register("spawn", Cuboid.of("world", -50, 0, -50, 50, 320, 50));
 *
 * &#64;EventHandler
 * public void onRegion(RsRegionChangeEvent event) {
 *     for (RsRegion left : event.left(plugin)) { ... }
 *     for (RsRegion entered : event.entered(plugin)) { ... }
 * }
 * </pre>
 *
 * <ul>
 *   <li>Regions live in memory only: register them again on enable, from the plugin's own
 *   data. Shutting the plugin down removes them without leave events.</li>
 *   <li>{@link RsRegionChangeEvent} fires for a move into another block, a teleport, a respawn,
 *   a world change (portals too), and when a region is registered around a player or removed
 *   from under one. Joining inside a region does not count as entering it; the player's
 *   regions are recorded silently.</li>
 *   <li>Every few ticks ({@code regions.check-interval-ticks} in RsLib's config.yml, 5 by
 *   default) every player whose block changed without an event is judged again
 *   ({@link RsRegionChangeEvent.Cause#CHECK}).</li>
 *   <li>Lookups go through an index by chunk: the regions at a block cost one map lookup.</li>
 * </ul>
 *
 * <p>Ids are unique within the plugin; the regions of other plugins never clash with them.
 * Registering and unregistering are safe from any thread; the events fire on the player's
 * thread.</p>
 */
public final class RegionRegistry {

    private static final Listener LISTENER = new RegionListener(RegionTracker.SHARED);

    private final String owner;
    private final RegionTracker tracker;

    /** Created by the context. */
    public RegionRegistry(Rs rs) {
        this(rs.plugin().getName(), RegionTracker.SHARED);
    }

    RegionRegistry(String owner, RegionTracker tracker) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.tracker = tracker;
    }

    // ------------------------------------------------------------------ shared, for RsLib

    /** The listener every plugin shares; RsLib registers it. */
    public static Listener sharedListener() {
        return LISTENER;
    }

    /** Starts recording players and the periodic check; called by RsLib when it enables. */
    public static void startShared(JavaPlugin rslib, long checkIntervalTicks) {
        RegionTracker.SHARED.attach(RegionHost.server(rslib), checkIntervalTicks);
    }

    /** A new check period in ticks (clamped to 1-200); called by RsLib on reload. */
    public static void checkInterval(long ticks) {
        RegionTracker.SHARED.interval(ticks);
    }

    /** Stops the check and forgets the players; called by RsLib when it disables. */
    public static void stopShared() {
        RegionTracker.SHARED.detach();
    }

    // ------------------------------------------------------------------ this plugin's regions

    /**
     * Registers {@code area} as region {@code id}, replacing this plugin's region with that id.
     * Players already inside enter it (and those inside the replaced box only leave it), with
     * {@link RsRegionChangeEvent.Cause#REGION_REGISTERED}.
     *
     * @throws IllegalArgumentException for a blank id
     */
    public RsRegion register(String id, Cuboid area) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(area, "area");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A region needs an id");
        }
        return tracker.register(owner, id, area);
    }

    /** Removes region {@code id}; whoever is inside leaves it. False when there was none. */
    public boolean unregister(String id) {
        return id != null && tracker.unregister(owner, id, false);
    }

    /** Removes every region of this plugin; whoever is inside leaves them. */
    public void unregisterAll() {
        tracker.unregisterAll(owner, false);
    }

    /** This plugin's region {@code id}, or null. */
    public RsRegion get(String id) {
        return id == null ? null : tracker.get(owner, id);
    }

    /** This plugin's regions, in registration order. */
    public List<RsRegion> all() {
        return List.copyOf(tracker.owned(owner));
    }

    /** This plugin's regions containing the block of {@code location}, in registration order. */
    public List<RsRegion> at(Location location) {
        if (location == null) {
            return List.of();
        }
        return mine(tracker.at(RegionTracker.worldName(location), location.getBlockX(), location.getBlockY(),
                location.getBlockZ()));
    }

    /** This plugin's regions {@code player} is in, as of the last event or check. */
    public List<RsRegion> of(Player player) {
        return player == null ? List.of() : of(player.getUniqueId());
    }

    /** This plugin's regions the player is in, as of the last event or check; empty when offline. */
    public List<RsRegion> of(UUID player) {
        return player == null ? List.of() : mine(tracker.current(player));
    }

    /** True when {@code player} is in this plugin's region {@code id}, as of the last event or check. */
    public boolean isIn(Player player, String id) {
        if (player == null || id == null) {
            return false;
        }
        for (RsRegion region : tracker.current(player.getUniqueId())) {
            if (region.sameKey(owner, id)) {
                return true;
            }
        }
        return false;
    }

    /** Removes this plugin's regions without leave events; called by the context on shutdown. */
    public void shutdown() {
        tracker.unregisterAll(owner, true);
    }

    private List<RsRegion> mine(List<RsRegion> regions) {
        List<RsRegion> mine = new ArrayList<>(regions.size());
        for (RsRegion region : regions) {
            if (region.owner().equals(owner)) {
                mine.add(region);
            }
        }
        return List.copyOf(mine);
    }
}
