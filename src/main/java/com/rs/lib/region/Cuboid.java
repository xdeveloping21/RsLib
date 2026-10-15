package com.rs.lib.region;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * An immutable box of blocks: a world name and two corners, both corners' blocks included.
 * The corners are sorted per axis when it is made, so {@code min <= max} on each axis whatever
 * order they came in.
 *
 * <p>Stored as text with {@link #serialize()}: {@code world;minX;minY;minZ;maxX;maxY;maxZ}
 * (seven parts separated by {@code ;}). That form is a data contract: saved cuboids must read
 * back the same. It is not the {@code LocationSerializer} form.</p>
 *
 * <p>Pure apart from {@link #world()}, {@link #center()}, {@link #blocks()} and
 * {@link #contains(Location)}, which read the server's worlds or the location's; safe from
 * any thread.</p>
 */
public final class Cuboid {

    private static final char SEPARATOR = ';';
    private static final int PARTS = 7;

    private final String world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /** The box between blocks {@code (x1, y1, z1)} and {@code (x2, y2, z2)} of {@code world}, corners in any order. */
    public static Cuboid of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        Objects.requireNonNull(world, "world");
        return new Cuboid(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /**
     * The box between the blocks of {@code a} and {@code b}.
     *
     * @throws IllegalArgumentException when either has no world or they are in different worlds
     */
    public static Cuboid of(Location a, Location b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        World wa = a.getWorld();
        World wb = b.getWorld();
        if (wa == null || wb == null) {
            throw new IllegalArgumentException("Both corners need a world");
        }
        if (!wa.getName().equals(wb.getName())) {
            throw new IllegalArgumentException("The corners are in different worlds: " + wa.getName()
                    + " and " + wb.getName());
        }
        return of(wa.getName(), a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    // ------------------------------------------------------------------ getters

    public String worldName() {
        return world;
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Blocks along X ({@code maxX - minX + 1}). */
    public int widthX() {
        return maxX - minX + 1;
    }

    /** Blocks along Y. */
    public int heightY() {
        return maxY - minY + 1;
    }

    /** Blocks along Z. */
    public int depthZ() {
        return maxZ - minZ + 1;
    }

    /** Number of blocks, computed in {@code long} so a huge box does not overflow. */
    public long size() {
        return ((long) maxX - minX + 1) * ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1);
    }

    // ------------------------------------------------------------------ tests

    /** True when block {@code (x, y, z)} is inside; the world is not checked. */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True when block {@code (x, y, z)} of the world named {@code worldName} is inside. */
    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName) && contains(x, y, z);
    }

    /** True when the block of {@code location} is inside; false for null, no world or an unloaded world. */
    public boolean contains(Location location) {
        if (location == null) {
            return false;
        }
        World at;
        try {
            at = location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return false;
        }
        return at != null && contains(at.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** True when both are in the same world and share at least one block (touching faces count). */
    public boolean intersects(Cuboid other) {
        return other != null && world.equals(other.world)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    // ------------------------------------------------------------------ derived boxes

    /**
     * The box grown by {@code dx}, {@code dy}, {@code dz} blocks on both sides of each axis
     * (negative values shrink it). An axis shrunk past itself collapses to one block at its
     * middle (rounded down).
     */
    public Cuboid expand(int dx, int dy, int dz) {
        long[] x = axis(minX, maxX, dx);
        long[] y = axis(minY, maxY, dy);
        long[] z = axis(minZ, maxZ, dz);
        return new Cuboid(world, (int) x[0], (int) y[0], (int) z[0], (int) x[1], (int) y[1], (int) z[1]);
    }

    private static long[] axis(int min, int max, int delta) {
        long low = (long) min - delta;
        long high = (long) max + delta;
        if (low > high) {
            long middle = Math.floorDiv((long) min + max, 2L);
            return new long[] {middle, middle};
        }
        return new long[] {clamp(low), clamp(high)};
    }

    private static long clamp(long value) {
        return Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    // ------------------------------------------------------------------ blocks

    /** A callback for {@link #forEach(BlockConsumer)}. */
    @FunctionalInterface
    public interface BlockConsumer {

        void accept(int x, int y, int z);
    }

    /** Calls {@code consumer} for every block, X outermost, then Y, then Z; nothing is allocated. */
    public void forEach(BlockConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    consumer.accept(x, y, z);
                    if (z == Integer.MAX_VALUE) {
                        break;
                    }
                }
                if (y == Integer.MAX_VALUE) {
                    break;
                }
            }
            if (x == Integer.MAX_VALUE) {
                break;
            }
        }
    }

    /**
     * Every block as a location, in the order of {@link #forEach}, made one at a time while
     * iterating (no list is built). The world is looked up when iteration starts; the locations
     * have no world when it is not loaded.
     */
    public Iterable<Location> blocks() {
        return () -> new BlockIterator(world());
    }

    /** The loaded world with this name, or null. */
    public World world() {
        return Bukkit.getServer() == null ? null : Bukkit.getWorld(world);
    }

    /** The geometric centre: halfway between the outer faces ({@code (min + max) / 2 + 0.5} per axis). */
    public Location center() {
        return new Location(world(), (minX + (double) maxX) / 2.0 + 0.5, (minY + (double) maxY) / 2.0 + 0.5,
                (minZ + (double) maxZ) / 2.0 + 0.5);
    }

    // ------------------------------------------------------------------ text

    /** {@code world;minX;minY;minZ;maxX;maxY;maxZ}. */
    public String serialize() {
        return world + SEPARATOR + minX + SEPARATOR + minY + SEPARATOR + minZ
                + SEPARATOR + maxX + SEPARATOR + maxY + SEPARATOR + maxZ;
    }

    /**
     * Reads {@link #serialize()}'s form back. Lenient: spaces around the text and each part are
     * ignored, the corners are sorted again, and the world does not need to be loaded. Null for
     * null or anything that is not exactly seven parts with a world name and six whole numbers.
     */
    public static Cuboid deserialize(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = new String[PARTS];
        int start = 0;
        for (int i = 0; i < PARTS; i++) {
            int end = text.indexOf(SEPARATOR, start);
            if (i < PARTS - 1 ? end < 0 : end >= 0) {
                return null;
            }
            parts[i] = text.substring(start, i < PARTS - 1 ? end : text.length()).trim();
            start = end + 1;
        }
        if (parts[0].isEmpty()) {
            return null;
        }
        try {
            return of(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Cuboid c && c.minX == minX && c.minY == minY && c.minZ == minZ
                && c.maxX == maxX && c.maxY == maxY && c.maxZ == maxZ && c.world.equals(world);
    }

    @Override
    public int hashCode() {
        int hash = world.hashCode();
        hash = 31 * hash + minX;
        hash = 31 * hash + minY;
        hash = 31 * hash + minZ;
        hash = 31 * hash + maxX;
        hash = 31 * hash + maxY;
        return 31 * hash + maxZ;
    }

    /** {@code Cuboid[world;minX;minY;minZ;maxX;maxY;maxZ]}. */
    @Override
    public String toString() {
        return "Cuboid[" + serialize() + "]";
    }

    /** Walks the blocks without a list; one new location per step. */
    private final class BlockIterator implements Iterator<Location> {

        private final World at;
        private int x = minX;
        private int y = minY;
        private int z = minZ;
        private boolean done;

        BlockIterator(World at) {
            this.at = at;
        }

        @Override
        public boolean hasNext() {
            return !done;
        }

        @Override
        public Location next() {
            if (done) {
                throw new NoSuchElementException();
            }
            Location here = new Location(at, x, y, z);
            if (z < maxZ) {
                z++;
            } else if (y < maxY) {
                z = minZ;
                y++;
            } else if (x < maxX) {
                z = minZ;
                y = minY;
                x++;
            } else {
                done = true;
            }
            return here;
        }
    }
}
