package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

/** Location text and geometry with fake worlds; looking a world up by name needs a running server. */
class LocationTest {

    private final World world = Fakes.world("world");
    private final World nether = Fakes.world("world_nether");

    @Test
    void serializesWithoutLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            Location location = new Location(world, 10.5, 64, -3.25, 90f, -12.5f);
            assertEquals("world;10.5;64.0;-3.25;90.0;-12.5", LocationSerializer.serialize(location));
        } finally {
            Locale.setDefault(before);
        }
        assertNull(LocationSerializer.serialize(null));
        assertNull(LocationSerializer.serialize(new Location(null, 1, 2, 3)));
    }

    @Test
    void readsSixOrFourParts() {
        LocationSerializer.Parts full = LocationSerializer.parse("world;10.5;64.0;-3.25;90.0;-12.5");
        assertEquals(new LocationSerializer.Parts("world", 10.5, 64, -3.25, 90f, -12.5f), full);
        LocationSerializer.Parts shortForm = LocationSerializer.parse("my world; 1 ; 2 ; 3");
        assertEquals(new LocationSerializer.Parts("my world", 1, 2, 3, 0f, 0f), shortForm);
        String written = LocationSerializer.write("w", 0.1 + 0.2, 1e-7, 123456789.125, 0.1f, -90f);
        LocationSerializer.Parts back = LocationSerializer.parse(written);
        assertEquals(0.1 + 0.2, back.x());
        assertEquals(1e-7, back.y());
        assertEquals(0.1f, back.yaw());
    }

    @Test
    void malformedTextReadsAsNull() {
        assertNull(LocationSerializer.parse(null));
        assertNull(LocationSerializer.parse(""));
        assertNull(LocationSerializer.parse("world;1;2"));
        assertNull(LocationSerializer.parse("world;1;2;3;4"));
        assertNull(LocationSerializer.parse("world;1;2;3;4;5;6"));
        assertNull(LocationSerializer.parse(";1;2;3"));
        assertNull(LocationSerializer.parse("world;1,5;2;3"));
        assertNull(LocationSerializer.parse("world;NaN;2;3"));
        assertNull(LocationSerializer.parse("world;1;Infinity;3"));
        assertNull(LocationSerializer.parse("world;1;2;3;x;0"));
    }

    @Test
    void cuboidIncludesBothCornerBlocks() {
        Location a = new Location(world, 10, 70, 10);
        Location b = new Location(world, 0, 60, 0);
        assertTrue(LocationUtil.inCuboid(new Location(world, 10.9, 70.9, 0), a, b));
        assertTrue(LocationUtil.inCuboid(new Location(world, 0, 60, 5), a, b));
        assertFalse(LocationUtil.inCuboid(new Location(world, 11, 65, 5), a, b));
        assertFalse(LocationUtil.inCuboid(new Location(world, -0.1, 65, 5), a, b));
        assertFalse(LocationUtil.inCuboid(new Location(nether, 5, 65, 5), a, b));
        assertFalse(LocationUtil.inCuboid(null, a, b));
    }

    @Test
    void horizontalDistanceIgnoresY() {
        Location a = new Location(world, 0, 0, 0);
        Location b = new Location(world, 3, 200, 4);
        assertEquals(25, LocationUtil.distance2dSquared(a, b));
        assertEquals(5, LocationUtil.distance2d(a, b));
        assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distance2d(a, new Location(nether, 3, 0, 4)));
        assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distance2d(a, null));
        assertEquals(25, LocationUtil.distance2dSquared(new Location(null, 0, 0, 0), new Location(null, 3, 0, 4)));
    }

    @Test
    void distanceToBoxReachesTheFarFaceOfTheMaxBlock() {
        assertEquals(0, LocationUtil.distanceToBoxSquared(0, 0, 0, 10, 10, 10, 10.9, 5, 5));
        assertEquals(1, LocationUtil.distanceToBoxSquared(0, 0, 0, 10, 10, 10, 12, 5, 5), 1e-9);
        assertEquals(4, LocationUtil.distanceToBoxSquared(10, 10, 10, 0, 0, 0, 5, -2, 5), 1e-9);
        assertEquals(3, LocationUtil.distanceToBoxSquared(0, 0, 0, 0, 0, 0, 2, 2, 2), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distanceToBoxSquared(0, 0, 0, 1, 1, 1, null));
        assertEquals(1, LocationUtil.distanceToBoxSquared(0, 0, 0, 1, 1, 1, new Location(world, 3, 1, 1)), 1e-9);
    }

    @Test
    void armourSlotsComeFromTheMaterialName() {
        assertEquals(EquipmentSlot.HEAD, ArmourUtil.slotOfName("DIAMOND_HELMET"));
        assertEquals(EquipmentSlot.HEAD, ArmourUtil.slotOfName("TURTLE_HELMET"));
        assertEquals(EquipmentSlot.HEAD, ArmourUtil.slotOfName("PLAYER_HEAD"));
        assertEquals(EquipmentSlot.HEAD, ArmourUtil.slotOfName("WITHER_SKELETON_SKULL"));
        assertEquals(EquipmentSlot.HEAD, ArmourUtil.slotOfName("CARVED_PUMPKIN"));
        assertEquals(EquipmentSlot.CHEST, ArmourUtil.slotOfName("NETHERITE_CHESTPLATE"));
        assertEquals(EquipmentSlot.CHEST, ArmourUtil.slotOfName("ELYTRA"));
        assertEquals(EquipmentSlot.LEGS, ArmourUtil.slotOfName("CHAINMAIL_LEGGINGS"));
        assertEquals(EquipmentSlot.FEET, ArmourUtil.slotOfName("LEATHER_BOOTS"));
        assertEquals(EquipmentSlot.FEET, ArmourUtil.slotOfName("COPPER_BOOTS"));
        assertNull(ArmourUtil.slotOfName("PUMPKIN"));
        assertNull(ArmourUtil.slotOfName("DIAMOND_SWORD"));
        assertNull(ArmourUtil.slotOf(null));
    }
}
