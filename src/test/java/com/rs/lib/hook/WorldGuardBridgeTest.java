package com.rs.lib.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * The WorldGuard bridge: without WorldGuard (or with one it cannot reach) nothing is inside a
 * region and every flag allows; with the stand-in classes under {@code com.sk89q} on the test
 * class path, the reflection path answers what WorldGuard says.
 */
class WorldGuardBridgeTest {

    private static final World WORLD = world("world");
    private static final Location SPAWN = new Location(WORLD, 5, 64, 5);
    private static final StateFlag PVP = new StateFlag("pvp");
    private final UUID memberId = UUID.randomUUID();

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(WorldGuardBridgeTest.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @AfterEach
    void reset() {
        WorldGuardBridge.invalidate();
        WorldGuard.REGIONS.clear();
        WorldGuard.FLAGS.clear();
        WorldGuard.broken = false;
    }

    private void installSpawn() {
        ProtectedRegion spawn = new ProtectedRegion("spawn", "world", 0, 0, 10, 10, 10);
        spawn.flags.put(PVP, StateFlag.State.DENY);
        spawn.members.add(memberId);
        WorldGuard.REGIONS.add(new ProtectedRegion("town", "world", -100, -100, 100, 100, 0));
        WorldGuard.REGIONS.add(spawn);
        WorldGuard.REGIONS.add(new ProtectedRegion("spawn", "nether", 0, 0, 10, 10, 0));
        WorldGuard.FLAGS.put("pvp", PVP);
        WorldGuard.FLAGS.put("greeting", "a string flag");
    }

    @Test
    void withoutAServerNothingIsInsideAndEverythingIsAllowed() {
        WorldGuardBridge.invalidate();
        assertFalse(WorldGuardBridge.available());
        assertEquals(List.of(), WorldGuardBridge.regionIds(SPAWN));
        assertFalse(WorldGuardBridge.isIn(SPAWN, "spawn"));
        assertTrue(WorldGuardBridge.allows(SPAWN, "pvp"));
        assertTrue(WorldGuardBridge.allows(player(memberId), SPAWN, "pvp"));
    }

    @Test
    void notInstalledAnswersTheSame() {
        installSpawn();
        WorldGuardBridge.use(null);
        assertFalse(WorldGuardBridge.available());
        assertEquals(List.of(), WorldGuardBridge.regionIds(SPAWN));
        assertTrue(WorldGuardBridge.allows(SPAWN, "pvp"));
    }

    @Test
    void anUnreachableApiFallsBackAndIsReportedOnce() {
        ClassLoader empty = new ClassLoader(null) {
        };
        WorldGuardBridge.use(empty);
        WorldGuardBridge.use(empty);
        assertFalse(WorldGuardBridge.available());
        assertTrue(WorldGuardBridge.warned().contains("connect"));
        assertTrue(WorldGuardBridge.allows(SPAWN, "pvp"));
        assertEquals(List.of(), WorldGuardBridge.regionIds(SPAWN));
    }

    @Test
    void regionsComeFromWorldGuardHighestPriorityFirst() {
        installSpawn();
        WorldGuardBridge.use(getClass().getClassLoader());
        assertTrue(WorldGuardBridge.available());
        assertEquals(List.of("spawn", "town"), WorldGuardBridge.regionIds(SPAWN));
        assertTrue(WorldGuardBridge.isIn(SPAWN, "SPAWN"));
        assertEquals(List.of("town"), WorldGuardBridge.regionIds(new Location(WORLD, 50, 64, 50)));
        assertEquals(List.of(), WorldGuardBridge.regionIds(new Location(WORLD, 500, 64, 500)));
        assertEquals(List.of(), WorldGuardBridge.regionIds(new Location(null, 5, 64, 5)), "no world, no region");
    }

    @Test
    void flagsAllowUnlessWorldGuardDenies() {
        installSpawn();
        WorldGuardBridge.use(getClass().getClassLoader());
        assertFalse(WorldGuardBridge.allows(SPAWN, "pvp"), "spawn denies pvp");
        assertFalse(WorldGuardBridge.allows(player(UUID.randomUUID()), SPAWN, "pvp"));
        assertTrue(WorldGuardBridge.allows(player(memberId), SPAWN, "pvp"), "the player is passed to WorldGuard");
        assertTrue(WorldGuardBridge.allows(new Location(WORLD, 50, 64, 50), "pvp"), "no region sets it");
        assertTrue(WorldGuardBridge.allows(SPAWN, "no-such-flag"));
        assertTrue(WorldGuardBridge.allows(SPAWN, "greeting"), "not a state flag");
        assertTrue(WorldGuardBridge.warned().contains("flag:greeting"));
    }

    @Test
    void aFailingQueryFallsBackOpen() {
        installSpawn();
        WorldGuardBridge.use(getClass().getClassLoader());
        WorldGuard.broken = true;
        assertEquals(List.of(), WorldGuardBridge.regionIds(SPAWN));
        assertTrue(WorldGuardBridge.allows(SPAWN, "pvp"));
    }
}
