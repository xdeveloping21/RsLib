package com.rs.lib.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The chunk index: every chunk a box touches, negative coordinates, wide boxes and removal. */
class RegionIndexTest {

    private final RegionIndex index = new RegionIndex();
    private long order;

    private RsRegion region(String id, Cuboid box) {
        return new RsRegion("Test", id, box, order++);
    }

    @Test
    void aBoxIsFoundInEveryChunkItTouchesAndNowhereElse() {
        RsRegion box = region("box", Cuboid.of("world", -17, 10, -1, 16, 20, 15));
        index.add(box);
        // X from chunk -2 to chunk 1, Z from chunk -1 to chunk 0: eight cells.
        assertEquals(8, index.cellsIn("world"));
        assertEquals(List.of(box), index.at("world", -17, 10, -1));
        assertEquals(List.of(box), index.at("world", 16, 20, 15));
        assertEquals(List.of(), index.at("world", -18, 10, 0), "one block west of the box");
        assertEquals(List.of(), index.at("world", 0, 21, 0), "above it, same chunk");
        assertEquals(List.of(), index.at("nether", 0, 15, 0), "another world");
        assertEquals(List.of(), index.at(null, 0, 15, 0));
    }

    @Test
    void overlappingRegionsComeBackInRegistrationOrder() {
        RsRegion big = region("big", Cuboid.of("world", -100, 0, -100, 100, 100, 100));
        RsRegion small = region("small", Cuboid.of("world", 0, 0, 0, 3, 100, 3));
        index.add(small);
        index.add(big);
        assertEquals(List.of(big, small), index.at("world", 1, 50, 1));
    }

    @Test
    void wideBoxesSkipTheCellsButAreStillFound() {
        RsRegion map = region("map", Cuboid.of("world", -5000, -64, -5000, 5000, 320, 5000));
        assertTrue(RegionIndex.cellCount(map.area()) > RegionIndex.MAX_CELLS);
        RsRegion spot = region("spot", Cuboid.of("world", 0, 0, 0, 0, 0, 0));
        index.add(map);
        index.add(spot);
        assertEquals(1, index.wideIn("world"));
        assertEquals(1, index.cellsIn("world"));
        assertEquals(List.of(map, spot), index.at("world", 0, 0, 0));
        assertEquals(List.of(map), index.at("world", 4999, 0, -4999));
        assertTrue(index.remove(map));
        assertEquals(0, index.wideIn("world"));
        assertEquals(List.of(spot), index.at("world", 0, 0, 0));
    }

    @Test
    void removingEmptiesTheCellsAndCountsOnce() {
        RsRegion a = region("a", Cuboid.of("world", 0, 0, 0, 40, 10, 40));
        RsRegion b = region("b", Cuboid.of("world", 20, 0, 20, 50, 10, 50));
        index.add(a);
        index.add(b);
        assertEquals(2, index.size());
        assertTrue(index.remove(a));
        assertFalse(index.remove(a));
        assertEquals(1, index.size());
        assertEquals(List.of(), index.at("world", 5, 5, 5));
        assertEquals(List.of(b), index.at("world", 30, 5, 30));
        assertTrue(index.remove(b));
        assertEquals(0, index.cellsIn("world"));
        assertEquals(0, index.size());
    }

    @Test
    void anEqualRegionIsNotRemovedInsteadOfTheIndexedOne() {
        RsRegion indexed = region("same", Cuboid.of("world", 0, 0, 0, 1, 1, 1));
        RsRegion lookalike = new RsRegion("Test", "same", Cuboid.of("world", 0, 0, 0, 1, 1, 1), 99);
        index.add(indexed);
        assertFalse(index.remove(lookalike), "removal is by identity, not by key");
        assertEquals(List.of(indexed), index.at("world", 0, 0, 0));
    }

    @Test
    void chunkKeysKeepNegativeCoordinatesApart() {
        assertFalse(RegionIndex.chunkKey(-1, 0) == RegionIndex.chunkKey(0, -1));
        assertFalse(RegionIndex.chunkKey(-1, -1) == RegionIndex.chunkKey(1, 1));
    }
}
