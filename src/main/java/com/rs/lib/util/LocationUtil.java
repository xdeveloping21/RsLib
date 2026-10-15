package com.rs.lib.util;

import java.util.Objects;

import org.bukkit.Location;

/**
 * Small location geometry. Pure apart from reading the locations; safe from any thread.
 *
 * <p>Two locations are in the same world when their worlds are equal (two locations without a
 * world count as the same). A location whose world was unloaded is in no world.</p>
 */
public final class LocationUtil {

    /** Stands for the world of a location whose world was unloaded; equal to nothing else. */
    private static final Object UNLOADED = new Object();

    private LocationUtil() {
    }

    /**
     * True when {@code point} is inside the block box with corners {@code a} and {@code b},
     * both corners' blocks included, in any order: with corners at blocks 0 and 10, x = 10.9
     * is inside and x = 11 is not. False when any of them is null or the worlds differ.
     */
    public static boolean inCuboid(Location point, Location a, Location b) {
        if (point == null || a == null || b == null || !sameWorld(point, a) || !sameWorld(a, b)) {
            return false;
        }
        return between(point.getBlockX(), a.getBlockX(), b.getBlockX())
                && between(point.getBlockY(), a.getBlockY(), b.getBlockY())
                && between(point.getBlockZ(), a.getBlockZ(), b.getBlockZ());
    }

    /**
     * Squared horizontal distance (Y ignored); {@link Double#POSITIVE_INFINITY} when either
     * is null or the worlds differ.
     */
    public static double distance2dSquared(Location a, Location b) {
        if (a == null || b == null || !sameWorld(a, b)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Horizontal distance (Y ignored); {@link Double#POSITIVE_INFINITY} as in {@link #distance2dSquared}. */
    public static double distance2d(Location a, Location b) {
        return Math.sqrt(distance2dSquared(a, b));
    }

    /**
     * Squared distance from {@code point} to the nearest point of the block box from block
     * {@code (minX, minY, minZ)} to block {@code (maxX, maxY, maxZ)}, both included (the box
     * spans {@code [min, max + 1)} in each axis); 0 inside, {@link Double#POSITIVE_INFINITY}
     * for a null point. The world is not checked. Corners may come in any order.
     */
    public static double distanceToBoxSquared(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Location point) {
        if (point == null) {
            return Double.POSITIVE_INFINITY;
        }
        return distanceToBoxSquared(minX, minY, minZ, maxX, maxY, maxZ, point.getX(), point.getY(), point.getZ());
    }

    static double distanceToBoxSquared(int x1, int y1, int z1, int x2, int y2, int z2,
            double x, double y, double z) {
        double dx = gap(x, Math.min(x1, x2), Math.max(x1, x2) + 1.0);
        double dy = gap(y, Math.min(y1, y2), Math.max(y1, y2) + 1.0);
        double dz = gap(z, Math.min(z1, z2), Math.max(z1, z2) + 1.0);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Distance from {@code v} to the interval {@code [low, high]}. */
    private static double gap(double v, double low, double high) {
        return v < low ? low - v : v > high ? v - high : 0;
    }

    private static boolean between(int v, int a, int b) {
        return v >= Math.min(a, b) && v <= Math.max(a, b);
    }

    private static boolean sameWorld(Location a, Location b) {
        Object wa = worldOf(a);
        return wa != UNLOADED && Objects.equals(wa, worldOf(b));
    }

    /** The world of {@code location}, null when it has none, {@link #UNLOADED} when it was unloaded. */
    private static Object worldOf(Location location) {
        try {
            return location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return UNLOADED;
        }
    }
}
