package com.rs.lib.region;

import java.util.Objects;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import com.rs.lib.scheduler.TaskHandle;

/**
 * One player's selection in progress, from {@link SelectionManager#begin} or the first wand
 * click. Never saved to disk; a config reload does not touch it.
 *
 * <p>The positions are copies: changing a location after setting it, or one read back, changes
 * nothing here. On a session from {@link SelectionManager} (main thread), {@link #setPos1} and
 * {@link #setPos2} do what a left or right click of the wand does (particles, messages,
 * {@code onUpdate}, completion), without the permission check; with the player offline, or
 * once the session ended, they only store the position.</p>
 */
public final class SelectionSession {

    private final UUID playerId;
    private final SelectionSpec spec;
    private final long createdAtMillis;
    /** The manager that runs the wand pipeline for the setters; null in tests. */
    private final SelectionManager manager;
    private volatile long lastActivityMillis;
    private volatile Location pos1;
    private volatile Location pos2;
    /** The drawing task while one runs; touched on the player's thread. */
    TaskHandle renderer;
    /** Set once by whoever ends the session. */
    private volatile boolean ended;

    SelectionSession(UUID playerId, SelectionSpec spec, long createdAtMillis) {
        this(playerId, spec, createdAtMillis, null);
    }

    SelectionSession(UUID playerId, SelectionSpec spec, long createdAtMillis, SelectionManager manager) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.createdAtMillis = createdAtMillis;
        this.lastActivityMillis = createdAtMillis;
        this.manager = manager;
    }

    public UUID playerId() {
        return playerId;
    }

    public SelectionSpec spec() {
        return spec;
    }

    /** When the session started, in {@link System#currentTimeMillis()} time. */
    public long createdAtMillis() {
        return createdAtMillis;
    }

    /** When a position was last set (or the session started), in {@link System#currentTimeMillis()} time; the timeout counts from here. */
    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    void touch(long nowMillis) {
        this.lastActivityMillis = nowMillis;
    }

    /** A copy of the first position, or null. */
    public Location pos1() {
        Location at = pos1;
        return at == null ? null : at.clone();
    }

    /** A copy of the second position, or null. */
    public Location pos2() {
        Location at = pos2;
        return at == null ? null : at.clone();
    }

    /** Sets the first position like a left click (see the class notes); null only clears it. */
    public void setPos1(Location location) {
        if (location == null || manager == null || !manager.applyPosition(this, true, location)) {
            store1(location);
        }
    }

    /** Sets the second position like a right click (see the class notes); null only clears it. */
    public void setPos2(Location location) {
        if (location == null || manager == null || !manager.applyPosition(this, false, location)) {
            store2(location);
        }
    }

    void store1(Location location) {
        this.pos1 = location == null ? null : location.clone();
    }

    void store2(Location location) {
        this.pos2 = location == null ? null : location.clone();
    }

    /** True when both positions are set and in the same world. */
    public boolean hasBothPositions() {
        Location a = pos1;
        Location b = pos2;
        return a != null && b != null && sameWorld(a, b);
    }

    /** True when at least one position is set. */
    boolean hasAnyPosition() {
        return pos1 != null || pos2 != null;
    }

    /** The box between both positions, or null when one is missing or they are in different (or unloaded) worlds. */
    public Cuboid cuboid() {
        Location a = pos1;
        Location b = pos2;
        if (a == null || b == null || !sameWorld(a, b)) {
            return null;
        }
        return Cuboid.of(a, b);
    }

    /** Clears both positions. */
    public void clearPositions() {
        pos1 = null;
        pos2 = null;
    }

    /** The stored positions themselves (not copies), for the renderer. */
    Location rawPos1() {
        return pos1;
    }

    Location rawPos2() {
        return pos2;
    }

    boolean ended() {
        return ended;
    }

    /** True for the caller that ends the session first. */
    synchronized boolean end() {
        if (ended) {
            return false;
        }
        ended = true;
        return true;
    }

    static boolean sameWorld(Location a, Location b) {
        World wa = worldOf(a);
        World wb = worldOf(b);
        return wa != null && wb != null && wa.getName().equals(wb.getName());
    }

    /** The world of {@code location}, or null when it has none or it was unloaded. */
    static World worldOf(Location location) {
        try {
            return location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
    }
}
