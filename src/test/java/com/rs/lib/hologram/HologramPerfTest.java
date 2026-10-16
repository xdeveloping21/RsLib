package com.rs.lib.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.hologram.FakeHologramHost.FakeDisplay;
import com.rs.lib.hologram.FakeHologramHost.FakePlayer;
import com.rs.lib.hologram.FakeHologramHost.FakeWorld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Holograms nobody sees do no placeholder work; moves glide; the view range is kept. */
class HologramPerfTest {

    private FakeHologramHost host;
    private FakeWorld world;
    private HologramUtil holograms;
    private final AtomicInteger renders = new AtomicInteger();
    private final int[] online = {1};

    @BeforeEach
    void setUp() {
        host = new FakeHologramHost("HoloPerf" + UUID.randomUUID().toString().substring(0, 8));
        world = host.addWorld("world");
        holograms = new HologramUtil(host);
        host.papi = line -> {
            renders.incrementAndGet();
            return line.replace("%online%", String.valueOf(online[0]));
        };
    }

    @AfterEach
    void tearDown() {
        holograms.shutdown();
    }

    /** Chunk (0, 1). */
    private Location here() {
        return new Location(world.world, 10.5, 64, 20.5);
    }

    private FakeDisplay spawnRefreshing() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("%online% online"));
        holograms.refreshEvery("crate", 20);
        return host.alive().get(0);
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    // ---------------------------------------------------------------- nobody near

    @Test
    void aRefreshNobodyCanSeeRendersNothingAndAPlayerComingNearRendersAgain() {
        FakeDisplay display = spawnRefreshing();
        host.stand("world", 40, 40);
        host.stand("nether", 0, 1);
        renders.set(0);
        online[0] = 7;

        for (int i = 0; i < 10; i++) {
            host.timers.get(0).fire();
        }
        assertEquals(0, renders.get(), "no placeholder work while nobody is near");
        assertEquals("1 online", plain(display.text));
        assertTrue(holograms.hologram("crate").stale);

        host.stand("world", 3, -2);
        host.timers.get(0).fire();
        assertEquals(1, renders.get());
        assertEquals("7 online", plain(display.text));
        assertFalse(holograms.hologram("crate").stale);
    }

    @Test
    void theSavingCountsOneRenderPerRefreshOnlyWhileSomeoneIsNear() {
        spawnRefreshing();
        UUID far = host.stand("world", 100, 100);
        renders.set(0);
        int refreshes = 50;
        for (int i = 0; i < refreshes; i++) {
            host.timers.get(0).fire();
        }
        int unseen = renders.get();
        host.standing.remove(0);
        host.stand("world", 0, 1);
        for (int i = 0; i < refreshes; i++) {
            host.timers.get(0).fire();
        }
        assertEquals(0, unseen);
        assertEquals(refreshes, renders.get(), "same work as before when a player is near");
        assertNotSame(null, far);
    }

    @Test
    void thePlayersItIsHiddenFromDoNotCount() {
        spawnRefreshing();
        FakePlayer hidden = new FakePlayer();
        host.players.put(hidden.id, hidden);
        holograms.hideFrom(hidden.player, "crate");
        host.standing = new ArrayList<>();
        host.standing.add(new FakeHologramHost.Standing(hidden.id, "world", 0, 1));
        renders.set(0);

        host.timers.get(0).fire();
        assertEquals(0, renders.get());
    }

    @Test
    void theRadiusIsTheViewRangeInChunksPlusOne() {
        assertEquals(5, HologramUtil.radiusChunks(0f), "default: 64 blocks, 4 chunks, plus one");
        assertEquals(5, HologramUtil.radiusChunks(1f));
        assertEquals(3, HologramUtil.radiusChunks(0.5f));
        assertEquals(9, HologramUtil.radiusChunks(2f));
        assertEquals(2, HologramUtil.radiusChunks(0.1f));
        assertTrue(HologramUtil.within(5, -5, 5));
        assertFalse(HologramUtil.within(6, 0, 5));
        assertFalse(HologramUtil.within(0, -6, 5));

        spawnRefreshing();
        host.timers.get(0).fire();
        assertEquals(5, host.lastRadius);
        holograms.setViewRange("crate", 2f);
        host.timers.get(0).fire();
        assertEquals(9, host.lastRadius);
    }

    @Test
    void aPlayerStartingToTrackAStaleHologramGetsFreshTextAtOnce() {
        FakeDisplay display = spawnRefreshing();
        host.standing = new ArrayList<>();
        online[0] = 5;
        host.timers.get(0).fire();
        assertEquals("1 online", plain(display.text));
        renders.set(0);

        HologramChunkListener.INSTANCE.tracked(display.id);
        assertEquals("5 online", plain(display.text), "rendered before the entity is sent");
        assertEquals(1, renders.get());

        HologramChunkListener.INSTANCE.tracked(display.id);
        assertEquals(1, renders.get(), "a fresh hologram is not rendered again on tracking");
        HologramChunkListener.INSTANCE.tracked(UUID.randomUUID());
        assertEquals(1, renders.get());
    }

    @Test
    void showingAStaleHologramAgainRendersIt() {
        FakeDisplay display = spawnRefreshing();
        FakePlayer player = new FakePlayer();
        holograms.hideFrom(player.player, "crate");
        host.standing = new ArrayList<>();
        online[0] = 9;
        host.timers.get(0).fire();
        assertEquals("1 online", plain(display.text));

        holograms.showTo(player.player, "crate");
        assertEquals("9 online", plain(display.text));
    }

    @Test
    void setLinesStillRendersAtOnceEvenWithNobodyNear() {
        FakeDisplay display = spawnRefreshing();
        host.standing = new ArrayList<>();
        holograms.setLines("crate", List.of("new"));
        assertEquals("new", plain(display.text));
    }

    @Test
    void deletingOrReplacingTheEntityForgetsItsStaleMark() {
        FakeDisplay display = spawnRefreshing();
        host.standing = new ArrayList<>();
        int before = HologramChunkListener.INSTANCE.staleCount();
        host.timers.get(0).fire();
        assertEquals(before + 1, HologramChunkListener.INSTANCE.staleCount());

        world.unload(0, 1, host);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        assertEquals(before, HologramChunkListener.INSTANCE.staleCount(), "the new entity was rendered fresh");
        assertFalse(holograms.hologram("crate").stale);
        FakeDisplay second = host.alive().get(0);
        assertNotSame(display, second);

        host.timers.get(0).fire();
        assertEquals(before + 1, HologramChunkListener.INSTANCE.staleCount());
        holograms.delete("crate");
        assertEquals(before, HologramChunkListener.INSTANCE.staleCount());
        assertTrue(second.removed);
    }

    // ---------------------------------------------------------------- view range

    @Test
    void theViewRangeIsAppliedNowAndToEveryNewEntityAndZeroRestoresTheDefault() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay first = host.alive().get(0);
        assertFalse(first.calls.stream().anyMatch(c -> c.startsWith("setViewRange")), "the default is left alone");

        holograms.setViewRange("crate", 2.5f);
        assertEquals(2.5f, first.viewRange);

        world.unload(0, 1, host);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        FakeDisplay second = host.alive().get(0);
        assertNotSame(first, second);
        assertEquals(2.5f, second.viewRange);

        holograms.setViewRange("crate", 0f);
        assertEquals(1f, second.viewRange);
        holograms.setViewRange("crate", Float.NaN);
        assertEquals(1f, second.viewRange);
    }

    // ---------------------------------------------------------------- moves

    @Test
    void aMoveInsideLoadedChunksTeleportsTheSameEntityWithTheDefaultGlide() {
        world.load(0, 1);
        world.load(2, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay display = host.alive().get(0);

        Location to = new Location(world.world, 40.5, 70, 20.5);
        holograms.moveTo("crate", to);

        assertEquals(1, host.spawned.size(), "no new entity");
        assertEquals(40.5, display.at.getX());
        assertEquals(70.0, display.at.getY());
        assertEquals(HologramUtil.DEFAULT_MOVE_TICKS, display.teleportTicks);
        assertEquals(0, HologramChunkListener.INSTANCE.countAt("world", 0, 1));
        assertEquals(1, HologramChunkListener.INSTANCE.countAt("world", 2, 1));
        assertEquals(40.5, holograms.hologram("crate").place.x());

        holograms.moveTo("crate", new Location(world.world, 41, 70, 20));
        assertEquals(1, count(display.calls, "teleportDuration " + HologramUtil.DEFAULT_MOVE_TICKS),
                "the same duration is not sent again");
        holograms.moveTo("crate", new Location(world.world, 42, 70, 20), 500);
        assertEquals(HologramUtil.MAX_MOVE_TICKS, display.teleportTicks);
        holograms.moveTo("crate", new Location(world.world, 43, 70, 20), -4);
        assertEquals(0, display.teleportTicks);
        assertEquals(4, count(display.calls, "teleport"));
    }

    @Test
    void aServerThatCannotGlideStillMoves() {
        host.canGlide = false;
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay display = host.alive().get(0);
        holograms.moveTo("crate", new Location(world.world, 12, 64, 20), 10);
        assertEquals(1, count(display.calls, "teleport"));
        assertEquals(-1, display.teleportTicks);
        assertEquals(-1, holograms.hologram("crate").teleportTicks);
    }

    @Test
    void theGlideIsGivenToTheNextEntityToo() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        holograms.moveTo("crate", new Location(world.world, 12, 64, 20), 8);
        world.unload(0, 1, host);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        assertEquals(8, host.alive().get(0).teleportTicks);
    }

    @Test
    void aMoveIntoAnUnloadedChunkRemovesTheEntityUntilThatChunkLoads() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay display = host.alive().get(0);

        Location to = new Location(world.world, 200, 64, 200);
        holograms.moveTo("crate", to);
        assertTrue(display.removed);
        assertTrue(host.alive().isEmpty());
        assertNull(holograms.hologram("crate").entity);

        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        assertTrue(host.alive().isEmpty(), "the old chunk no longer brings it back");

        world.load(12, 12);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 12, 12);
        host.tick();
        assertEquals(1, host.alive().size());
        assertEquals(12, host.alive().get(0).chunkX);
        assertEquals(200.0, host.alive().get(0).at.getX());
    }

    @Test
    void aMoveToAnotherWorldSpawnsThereWhenItsChunkIsLoaded() {
        world.load(0, 1);
        FakeWorld nether = host.addWorld("nether");
        nether.load(0, 0);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay display = host.alive().get(0);

        holograms.moveTo("crate", new Location(nether.world, 1, 64, 1));
        assertTrue(display.removed);
        assertEquals(1, host.alive().size());
        assertSame(nether.world, host.alive().get(0).at.getWorld());
        assertEquals("nether", holograms.hologram("crate").world());
        assertEquals(1, HologramChunkListener.INSTANCE.countAt("nether", 0, 0));
    }

    @Test
    void aMoveWithoutAWorldWarnsAndKeepsThePlace() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        holograms.moveTo("crate", new Location(null, 1, 2, 3));
        assertEquals(1, host.warnings.size(), host.warnings.toString());
        assertEquals(10.5, holograms.hologram("crate").place.x());
        assertEquals(1, host.alive().size());
    }

    @Test
    void theTeleportDurationIsSetByReflectionWhereTheApiHasIt() {
        List<Object> calls = new ArrayList<>();
        TextDisplay display = (TextDisplay) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {TextDisplay.class}, (proxy, method, args) -> {
                    calls.add(method.getName() + " " + args[0]);
                    return null;
                });
        assertTrue(DisplayMoves.setTeleportDuration(display, 6));
        assertEquals(List.of("setTeleportDuration 6"), calls);
    }

    private static int count(List<String> calls, String name) {
        int n = 0;
        for (String call : calls) {
            if (call.equals(name)) {
                n++;
            }
        }
        return n;
    }
}
