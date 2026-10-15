package com.rs.lib.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import com.rs.lib.util.LocationUtil;

/** Trampa of the region module: the seven-part form and the normalization are a data contract. */
class CuboidTest {

    private static final World WORLD = Fakes.world("world");
    private static final World NETHER = Fakes.world("nether");

    @Test
    void cornersAreSortedPerAxisAndEdgesAreInclusive() {
        Cuboid box = Cuboid.of("world", 10, 70, -5, -2, 64, 3);
        assertEquals(-2, box.minX());
        assertEquals(64, box.minY());
        assertEquals(-5, box.minZ());
        assertEquals(10, box.maxX());
        assertEquals(70, box.maxY());
        assertEquals(3, box.maxZ());
        assertEquals(13, box.widthX());
        assertEquals(7, box.heightY());
        assertEquals(9, box.depthZ());
        assertEquals(13L * 7 * 9, box.size());
        assertEquals(box, Cuboid.of("world", -2, 64, -5, 10, 70, 3));
        assertEquals(box.hashCode(), Cuboid.of("world", -2, 64, 3, 10, 70, -5).hashCode());
        assertNotEquals(box, Cuboid.of("nether", -2, 64, -5, 10, 70, 3));
        assertEquals(1, Cuboid.of("w", 5, 5, 5, 5, 5, 5).size());
    }

    @Test
    void sizeDoesNotOverflow() {
        Cuboid huge = Cuboid.of("world", -30_000_000, -64, -30_000_000, 29_999_999, 319, 29_999_999);
        assertEquals(60_000_000L * 384L * 60_000_000L, huge.size());
    }

    @Test
    void serializedFormIsSevenPartsWithTheSortedCorners() {
        Cuboid box = Cuboid.of("world_nether", 5, 80, 9, -3, 60, 1);
        assertEquals("world_nether;-3;60;1;5;80;9", box.serialize());
        assertEquals(box, Cuboid.deserialize(box.serialize()));
        assertEquals("Cuboid[world_nether;-3;60;1;5;80;9]", box.toString());
    }

    @Test
    void readingIsLenientButNeverGuesses() {
        // Spaces around the text and the parts; corners sorted again; the world need not be loaded.
        assertEquals(Cuboid.of("unloaded", 0, 0, 0, 10, 10, 10),
                Cuboid.deserialize("  unloaded ; 10; 0 ;10;0 ;10 ; 0  "));
        assertNull(Cuboid.deserialize(null));
        assertNull(Cuboid.deserialize(""));
        assertNull(Cuboid.deserialize("world;1;2;3;4;5"), "six parts");
        assertNull(Cuboid.deserialize("world;1;2;3;4;5;6;7"), "eight parts");
        assertNull(Cuboid.deserialize(";1;2;3;4;5;6"), "no world");
        assertNull(Cuboid.deserialize("world;1;2;x;4;5;6"));
        assertNull(Cuboid.deserialize("world;1.5;2;3;4;5;6"));
        assertNull(Cuboid.deserialize("world;1;2;3;4;5;99999999999"));
        assertNull(Cuboid.deserialize("world;10.5;64;-20.5;0;0"), "a LocationSerializer line is not a cuboid");
    }

    @Test
    void containsChecksBlocksAndTheWorld() {
        Cuboid box = Cuboid.of("world", 0, 0, 0, 10, 10, 10);
        assertTrue(box.contains(0, 0, 0));
        assertTrue(box.contains(10, 10, 10));
        assertFalse(box.contains(11, 10, 10));
        assertFalse(box.contains(-1, 0, 0));
        assertTrue(box.contains("world", 5, 5, 5));
        assertFalse(box.contains("World", 5, 5, 5));
        assertTrue(box.contains(new Location(WORLD, 10.9, 0.1, 10.99)));
        assertFalse(box.contains(new Location(WORLD, 11, 0, 0)));
        assertFalse(box.contains(new Location(WORLD, -0.1, 0, 0)), "block -1");
        assertFalse(box.contains(new Location(NETHER, 5, 5, 5)));
        assertFalse(box.contains(new Location(null, 5, 5, 5)));
        assertFalse(box.contains((Location) null));
    }

    @Test
    void touchingBoxesIntersect() {
        Cuboid a = Cuboid.of("world", 0, 0, 0, 10, 10, 10);
        assertTrue(a.intersects(Cuboid.of("world", 10, 10, 10, 20, 20, 20)), "sharing one block");
        assertTrue(a.intersects(Cuboid.of("world", 2, 2, 2, 3, 3, 3)), "inside");
        assertFalse(a.intersects(Cuboid.of("world", 11, 0, 0, 20, 10, 10)), "side by side");
        assertFalse(a.intersects(Cuboid.of("nether", 0, 0, 0, 10, 10, 10)));
        assertFalse(a.intersects(null));
    }

    @Test
    void expandGrowsAndShrinksBothSidesAndCollapsesPastTheMiddle() {
        Cuboid box = Cuboid.of("world", 0, 0, 0, 10, 4, 3);
        assertEquals(Cuboid.of("world", -2, -1, 0, 12, 5, 3), box.expand(2, 1, 0));
        assertEquals(Cuboid.of("world", 3, 1, 1, 7, 3, 2), box.expand(-3, -1, -1));
        // Y shrinks past itself: one block at the middle (0 + 4) / 2 = 2; Z: (0 + 3) / 2 = 1.
        Cuboid collapsed = box.expand(0, -3, -2);
        assertEquals(2, collapsed.minY());
        assertEquals(2, collapsed.maxY());
        assertEquals(1, collapsed.minZ());
        assertEquals(1, collapsed.maxZ());
        // Rounded down for negative coordinates too: (-5 + -2) / 2 = -3.5 -> -4.
        Cuboid negative = Cuboid.of("world", -5, 0, 0, -2, 0, 0).expand(-10, 0, 0);
        assertEquals(-4, negative.minX());
        assertEquals(-4, negative.maxX());
    }

    @Test
    void blocksAreVisitedXThenYThenZWithoutAList() {
        Cuboid box = Cuboid.of("world", 0, 0, 0, 1, 1, 1);
        List<String> order = new ArrayList<>();
        box.forEach((x, y, z) -> order.add(x + "," + y + "," + z));
        List<String> expected = List.of("0,0,0", "0,0,1", "0,1,0", "0,1,1", "1,0,0", "1,0,1", "1,1,0", "1,1,1");
        assertEquals(expected, order);

        List<String> lazy = new ArrayList<>();
        Iterator<Location> it = box.blocks().iterator();
        while (it.hasNext()) {
            Location at = it.next();
            lazy.add(at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ());
        }
        assertEquals(expected, lazy);
        assertThrows(NoSuchElementException.class, it::next);
        int[] count = {0};
        Cuboid.of("w", 0, 0, 0, 0, 0, 0).blocks().forEach(at -> count[0]++);
        assertEquals(1, count[0]);
    }

    @Test
    void centreIsHalfwayBetweenTheOuterFaces() {
        Location centre = Cuboid.of("world", 0, 60, -4, 9, 60, 5).center();
        assertEquals(5.0, centre.getX());
        assertEquals(60.5, centre.getY());
        assertEquals(1.0, centre.getZ());
    }

    @Test
    void fromTwoLocations() {
        Cuboid box = Cuboid.of(new Location(WORLD, 3.7, 64, -0.5), new Location(WORLD, -1.2, 70.9, 2));
        assertEquals(Cuboid.of("world", -2, 64, -1, 3, 70, 2), box);
        assertThrows(IllegalArgumentException.class,
                () -> Cuboid.of(new Location(WORLD, 0, 0, 0), new Location(NETHER, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> Cuboid.of(new Location(null, 0, 0, 0), new Location(WORLD, 0, 0, 0)));
    }

    @Test
    void distanceToTheNearestPointOfTheBox() {
        Cuboid box = Cuboid.of("world", 0, 0, 0, 9, 9, 9);
        assertEquals(0, LocationUtil.distanceToBoxSquared(box, new Location(WORLD, 5, 5, 5)));
        assertEquals(0, LocationUtil.distanceToBoxSquared(box, new Location(WORLD, 10, 10, 10)), "outer face");
        assertEquals(9, LocationUtil.distanceToBoxSquared(box, new Location(WORLD, 13, 5, 5)));
        assertEquals(4 + 9, LocationUtil.distanceToBoxSquared(box, new Location(WORLD, -2, 5, 13)));
        assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distanceToBoxSquared(box, new Location(NETHER, 5, 5, 5)));
        assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distanceToBoxSquared(box, null));
    }
}
