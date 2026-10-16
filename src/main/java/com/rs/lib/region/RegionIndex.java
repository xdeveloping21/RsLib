package com.rs.lib.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the registered regions are, by world and chunk, so the regions at a block cost one map
 * lookup and a few box checks whatever how many regions exist.
 *
 * <p>A region is listed in every chunk it touches. One that touches more than
 * {@value #MAX_CELLS} chunks (wider than about 500 x 500 blocks) is kept in a short per-world
 * list instead and checked on every lookup in that world, so registering a whole map does not
 * fill memory with chunk cells.</p>
 *
 * <p>Changes are serialised; lookups take no lock and see either the old or the new arrays
 * (they are replaced, never changed in place). Safe from any thread.</p>
 */
final class RegionIndex {

    /** Chunk cells one region may take before it goes to the wide list. */
    static final int MAX_CELLS = 1024;

    private static final RsRegion[] NONE = new RsRegion[0];
    private static final Comparator<RsRegion> BY_ORDER = Comparator.comparingLong(r -> r.order);

    private final Map<String, WorldCells> worlds = new ConcurrentHashMap<>();
    private volatile int size;

    /** One world: chunk key to the regions touching that chunk, plus the wide ones. */
    private static final class WorldCells {
        final Map<Long, RsRegion[]> cells = new ConcurrentHashMap<>();
        volatile RsRegion[] wide = NONE;
    }

    /** Number of regions indexed. */
    int size() {
        return size;
    }

    /** Adds {@code region}; the caller removed any region with the same key first. */
    synchronized void add(RsRegion region) {
        Cuboid box = region.area();
        WorldCells world = worlds.computeIfAbsent(box.worldName(), k -> new WorldCells());
        if (cellCount(box) > MAX_CELLS) {
            world.wide = with(world.wide, region);
        } else {
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    world.cells.merge(chunkKey(cx, cz), new RsRegion[] {region}, RegionIndex::concat);
                }
            }
        }
        size++;
    }

    /** Removes {@code region} (matched by identity); false when it was not indexed. */
    synchronized boolean remove(RsRegion region) {
        Cuboid box = region.area();
        WorldCells world = worlds.get(box.worldName());
        if (world == null) {
            return false;
        }
        boolean found;
        if (cellCount(box) > MAX_CELLS) {
            RsRegion[] before = world.wide;
            world.wide = without(before, region);
            found = world.wide != before;
        } else {
            found = false;
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    Long key = chunkKey(cx, cz);
                    RsRegion[] before = world.cells.get(key);
                    if (before == null) {
                        continue;
                    }
                    RsRegion[] after = without(before, region);
                    if (after != before) {
                        found = true;
                        if (after.length == 0) {
                            world.cells.remove(key);
                        } else {
                            world.cells.put(key, after);
                        }
                    }
                }
            }
        }
        if (found) {
            size--;
            if (world.cells.isEmpty() && world.wide.length == 0) {
                worlds.remove(box.worldName());
            }
        }
        return found;
    }

    /** The regions containing block {@code (x, y, z)} of {@code world}, in registration order. */
    List<RsRegion> at(String world, int x, int y, int z) {
        if (world == null || size == 0) {
            return List.of();
        }
        WorldCells cells = worlds.get(world);
        if (cells == null) {
            return List.of();
        }
        RsRegion[] near = cells.cells.get(chunkKey(x >> 4, z >> 4));
        RsRegion[] wide = cells.wide;
        List<RsRegion> found = null;
        if (near != null) {
            for (RsRegion region : near) {
                if (region.area().contains(x, y, z)) {
                    found = add(found, region);
                }
            }
        }
        for (RsRegion region : wide) {
            if (region.area().contains(x, y, z)) {
                found = add(found, region);
            }
        }
        if (found == null) {
            return List.of();
        }
        if (found.size() > 1) {
            found.sort(BY_ORDER);
        }
        return List.copyOf(found);
    }

    /** Chunk cells a box touches, in {@code long} so a huge box does not overflow. */
    static long cellCount(Cuboid box) {
        long width = (long) (box.maxX() >> 4) - (box.minX() >> 4) + 1;
        long depth = (long) (box.maxZ() >> 4) - (box.minZ() >> 4) + 1;
        return width * depth;
    }

    /** Number of chunk cells in use; for tests. */
    int cellsIn(String world) {
        WorldCells cells = worlds.get(world);
        return cells == null ? 0 : cells.cells.size();
    }

    /** Number of wide regions in a world; for tests. */
    int wideIn(String world) {
        WorldCells cells = worlds.get(world);
        return cells == null ? 0 : cells.wide.length;
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static List<RsRegion> add(List<RsRegion> list, RsRegion region) {
        List<RsRegion> into = list == null ? new ArrayList<>(2) : list;
        into.add(region);
        return into;
    }

    private static RsRegion[] concat(RsRegion[] a, RsRegion[] b) {
        RsRegion[] joined = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }

    private static RsRegion[] with(RsRegion[] array, RsRegion region) {
        return concat(array, new RsRegion[] {region});
    }

    /** {@code array} without {@code region} (by identity), or {@code array} itself when absent. */
    private static RsRegion[] without(RsRegion[] array, RsRegion region) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == region) {
                RsRegion[] less = new RsRegion[array.length - 1];
                System.arraycopy(array, 0, less, 0, i);
                System.arraycopy(array, i + 1, less, i, array.length - i - 1);
                return less;
            }
        }
        return array;
    }
}
