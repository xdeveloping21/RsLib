package com.rs.lib.region;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.rs.lib.event.RsRegionChangeEvent;
import com.rs.lib.event.RsRegionChangeEvent.Cause;
import com.rs.lib.scheduler.TaskHandle;

/**
 * The regions of every plugin and which of them each online player is in. One instance serves
 * the whole server ({@link #SHARED}); RsLib attaches it to the server when it enables.
 *
 * <p>A player's record (world, block, regions) is written only on the thread that owns the
 * player, from the move, teleport, world change and respawn listeners, from the periodic check
 * and from the re-check a registration asks for. The record is set on join without an event and
 * dropped on quit.</p>
 */
final class RegionTracker {

    static final RegionTracker SHARED = new RegionTracker();

    /** Ticks between periodic checks unless RsLib's config.yml says otherwise. */
    static final long DEFAULT_INTERVAL = 5L;
    static final long MIN_INTERVAL = 1L;
    static final long MAX_INTERVAL = 200L;

    private final RegionIndex index = new RegionIndex();
    private final Map<String, RsRegion> byKey = new ConcurrentHashMap<>();
    private final Map<UUID, Tracked> players = new ConcurrentHashMap<>();
    private long nextOrder;
    private volatile RegionHost host;
    private volatile long interval = DEFAULT_INTERVAL;
    /** Guarded by {@code this}. */
    private TaskHandle poll;

    /** Where one player was last judged to be. */
    static final class Tracked {
        volatile String world;
        volatile int x;
        volatile int y;
        volatile int z;
        volatile List<RsRegion> regions = List.of();
        /** A periodic check is queued on the player's thread. */
        final AtomicBoolean checkQueued = new AtomicBoolean();
        /** A re-check after a registration is queued on the player's thread. */
        final AtomicBoolean judgeQueued = new AtomicBoolean();
        volatile Cause queuedCause = Cause.CHECK;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts serving {@code server}: online players are recorded without events and the check starts. */
    void attach(RegionHost server, long periodTicks) {
        host = server;
        interval = clampInterval(periodTicks);
        for (Player player : server.online()) {
            if (server.ownsHere(player)) {
                track(player, player.getLocation());
            } else {
                server.runFor(player, () -> track(player, player.getLocation()));
            }
        }
        restartPoll();
    }

    /** A new check period (clamped to 1-200 ticks); restarts the check when it changed. */
    void interval(long periodTicks) {
        long clamped = clampInterval(periodTicks);
        if (clamped != interval) {
            interval = clamped;
            restartPoll();
        }
    }

    /** Stops the check and forgets every player; the regions stay registered. */
    void detach() {
        synchronized (this) {
            if (poll != null) {
                poll.cancel();
                poll = null;
            }
        }
        host = null;
        players.clear();
    }

    private void restartPoll() {
        RegionHost server = host;
        synchronized (this) {
            if (poll != null) {
                poll.cancel();
                poll = null;
            }
            if (server == null) {
                return;
            }
            try {
                poll = server.timer(interval, this::poll);
            } catch (RuntimeException notScheduled) {
                server.logger().warning("Could not schedule the region check (" + notScheduled.getMessage()
                        + "); enter and leave still follow moves and teleports.");
            }
        }
    }

    static long clampInterval(long ticks) {
        return Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, ticks));
    }

    long interval() {
        return interval;
    }

    // ------------------------------------------------------------------ regions

    /**
     * Registers {@code area} as {@code owner}'s region {@code id}, replacing the one with that
     * id (which keeps its place in the order). Players inside the old or the new box are judged
     * again, with {@link Cause#REGION_REGISTERED}.
     */
    RsRegion register(String owner, String id, Cuboid area) {
        RsRegion old;
        RsRegion region;
        synchronized (this) {
            old = byKey.get(key(owner, id));
            region = new RsRegion(owner, id, area, old == null ? nextOrder++ : old.order);
            if (old != null) {
                index.remove(old);
            }
            index.add(region);
            byKey.put(region.key(), region);
        }
        Set<String> worlds = new HashSet<>(2);
        worlds.add(area.worldName());
        if (old != null) {
            worlds.add(old.worldName());
        }
        recheck(worlds, Cause.REGION_REGISTERED);
        return region;
    }

    /**
     * Removes {@code owner}'s region {@code id}. Not silent: whoever was inside leaves, with
     * {@link Cause#REGION_UNREGISTERED}. Silent: nobody is told (its plugin is shutting down).
     */
    boolean unregister(String owner, String id, boolean silent) {
        RsRegion region;
        synchronized (this) {
            region = byKey.remove(key(owner, id));
            if (region == null) {
                return false;
            }
            index.remove(region);
            if (silent) {
                region.released = true;
            }
        }
        if (!silent) {
            recheck(Set.of(region.worldName()), Cause.REGION_UNREGISTERED);
        }
        return true;
    }

    /** Removes every region of {@code owner}; see {@link #unregister}. Returns how many. */
    int unregisterAll(String owner, boolean silent) {
        List<RsRegion> mine = owned(owner);
        Set<String> worlds = new HashSet<>();
        int removed = 0;
        synchronized (this) {
            for (RsRegion region : mine) {
                if (byKey.remove(region.key(), region)) {
                    index.remove(region);
                    if (silent) {
                        region.released = true;
                    }
                    worlds.add(region.worldName());
                    removed++;
                }
            }
        }
        if (!silent && !worlds.isEmpty()) {
            recheck(worlds, Cause.REGION_UNREGISTERED);
        }
        return removed;
    }

    RsRegion get(String owner, String id) {
        return byKey.get(key(owner, id));
    }

    /** {@code owner}'s regions in registration order. */
    List<RsRegion> owned(String owner) {
        List<RsRegion> mine = new ArrayList<>();
        for (RsRegion region : byKey.values()) {
            if (region.owner().equals(owner)) {
                mine.add(region);
            }
        }
        mine.sort((a, b) -> Long.compare(a.order, b.order));
        return mine;
    }

    /** Every region containing block {@code (x, y, z)} of {@code world}, in registration order. */
    List<RsRegion> at(String world, int x, int y, int z) {
        return index.at(world, x, y, z);
    }

    /** The regions {@code player} was last judged to be in (any plugin), or empty when untracked. */
    List<RsRegion> current(UUID player) {
        Tracked tracked = players.get(player);
        if (tracked == null) {
            return List.of();
        }
        List<RsRegion> live = new ArrayList<>(tracked.regions.size());
        for (RsRegion region : tracked.regions) {
            if (!region.released) {
                live.add(region);
            }
        }
        return live;
    }

    RegionIndex index() {
        return index;
    }

    // ------------------------------------------------------------------ players

    /** Join: records where the player is, without an event. Also used for anyone untracked. */
    Tracked track(Player player, Location at) {
        Tracked tracked = new Tracked();
        players.put(player.getUniqueId(), tracked);
        judge(player, tracked, at, null);
        return tracked;
    }

    /** Quit: the record goes, without an event. */
    void forget(UUID player) {
        players.remove(player);
    }

    /** A move into another block ({@code to}), on the player's thread. */
    void moved(Player player, Location to) {
        step(player, to, Cause.MOVE);
    }

    /** A teleport or respawn to {@code to}, on the player's thread. */
    void teleported(Player player, Location to) {
        Tracked tracked = players.get(player.getUniqueId());
        String world = worldName(to);
        boolean sameWorld = tracked != null && world != null && world.equals(tracked.world);
        step(player, to, sameWorld ? Cause.TELEPORT : Cause.WORLD_CHANGE);
    }

    /** The player is in another world now (portals included), on the player's thread. */
    void changedWorld(Player player) {
        step(player, player.getLocation(), Cause.WORLD_CHANGE);
    }

    private void step(Player player, Location to, Cause cause) {
        Tracked tracked = players.get(player.getUniqueId());
        if (tracked == null) {
            track(player, to);
            return;
        }
        if (index.size() == 0 && tracked.regions.isEmpty()) {
            // Nothing registered anywhere: only the world is kept, for the re-checks.
            tracked.world = worldName(to);
            return;
        }
        judge(player, tracked, to, cause);
    }

    /** One periodic check: anyone whose block changed without an event is judged again. */
    void poll() {
        RegionHost server = host;
        if (server == null || index.size() == 0) {
            return;
        }
        for (Player player : server.online()) {
            if (server.ownsHere(player)) {
                check(player);
            } else {
                Tracked tracked = players.get(player.getUniqueId());
                if (tracked == null || tracked.checkQueued.compareAndSet(false, true)) {
                    server.runFor(player, () -> {
                        if (tracked != null) {
                            tracked.checkQueued.set(false);
                        }
                        check(player);
                    });
                }
            }
        }
    }

    private void check(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Location here = player.getLocation();
        Tracked tracked = players.get(player.getUniqueId());
        if (tracked == null) {
            track(player, here);
            return;
        }
        String world = worldName(here);
        if (here.getBlockX() == tracked.x && here.getBlockY() == tracked.y && here.getBlockZ() == tracked.z
                && world != null && world.equals(tracked.world)) {
            return;
        }
        judge(player, tracked, here, Cause.CHECK);
    }

    /** Judges again every tracked player in one of {@code worlds}, on their own thread. */
    private void recheck(Set<String> worlds, Cause cause) {
        RegionHost server = host;
        if (server == null) {
            return;
        }
        for (Player player : server.online()) {
            Tracked tracked = players.get(player.getUniqueId());
            if (tracked == null || tracked.world != null && !worlds.contains(tracked.world)
                    && tracked.regions.isEmpty()) {
                continue;
            }
            if (server.ownsHere(player)) {
                judge(player, tracked, player.getLocation(), cause);
                continue;
            }
            tracked.queuedCause = cause;
            if (tracked.judgeQueued.compareAndSet(false, true)) {
                server.runFor(player, () -> {
                    tracked.judgeQueued.set(false);
                    if (players.get(player.getUniqueId()) == tracked && player.isOnline()) {
                        judge(player, tracked, player.getLocation(), tracked.queuedCause);
                    }
                });
            }
        }
    }

    /**
     * Records {@code at} as where the player is and fires the event when the regions changed;
     * {@code cause} null records without an event (join).
     */
    private void judge(Player player, Tracked tracked, Location at, Cause cause) {
        String world = worldName(at);
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        List<RsRegion> now = index.at(world, x, y, z);
        List<RsRegion> before = tracked.regions;
        tracked.world = world;
        tracked.x = x;
        tracked.y = y;
        tracked.z = z;
        tracked.regions = now;
        if (cause == null || before.isEmpty() && now.isEmpty()) {
            return;
        }
        List<RsRegion> left = null;
        for (RsRegion region : before) {
            if (!region.released && !now.contains(region)) {
                left = add(left, region);
            }
        }
        List<RsRegion> entered = null;
        for (RsRegion region : now) {
            if (!containsLive(before, region)) {
                entered = add(entered, region);
            }
        }
        RegionHost server = host;
        if (left == null && entered == null || server == null || !server.listened()) {
            return;
        }
        try {
            server.fire(new RsRegionChangeEvent(player, left == null ? List.of() : left,
                    entered == null ? List.of() : entered, cause, at.clone()));
        } catch (RuntimeException e) {
            server.logger().log(Level.WARNING, "A region change listener failed for " + player.getName(), e);
        }
    }

    private static boolean containsLive(List<RsRegion> regions, RsRegion wanted) {
        for (RsRegion region : regions) {
            if (!region.released && region.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static List<RsRegion> add(List<RsRegion> list, RsRegion region) {
        List<RsRegion> into = list == null ? new ArrayList<>(2) : list;
        into.add(region);
        return into;
    }

    /** The name of the location's world, or null without one (or when it is unloaded). */
    static String worldName(Location location) {
        if (location == null) {
            return null;
        }
        try {
            World world = location.getWorld();
            return world == null ? null : world.getName();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
    }

    private static String key(String owner, String id) {
        return owner + ':' + id;
    }

    /** Players with a record; for tests. */
    int trackedCount() {
        return players.size();
    }
}
