package com.rs.lib.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.hologram.FakeHologramHost.FakeDisplay;
import com.rs.lib.hologram.FakeHologramHost.FakePlayer;
import com.rs.lib.hologram.FakeHologramHost.FakeWorld;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class HologramUtilTest {

    private static final String OTHER_MARK = "otherlib:other_hologram";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private FakeHologramHost host;
    private FakeWorld world;
    private HologramUtil holograms;
    private String plugin;

    @BeforeEach
    void setUp() {
        plugin = "HoloTest" + UUID.randomUUID().toString().substring(0, 8);
        host = new FakeHologramHost(plugin);
        world = host.addWorld("world");
        holograms = new HologramUtil(host);
    }

    @AfterEach
    void tearDown() {
        holograms.shutdown();
    }

    /** Chunk (0, 1). */
    private Location here() {
        return new Location(world.world, 10.5, 64, 20.5);
    }

    private static String plain(Component c) {
        return PLAIN.serialize(c);
    }

    // ---------------------------------------------------------------- spawning

    @Test
    void spawnInALoadedChunkMakesOneNonPersistentMarkedDisplay() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("&6Vote crate", "&7Click"));

        assertEquals(1, host.spawned.size());
        FakeDisplay display = host.spawned.get(0);
        assertFalse(display.persistent, "never saved with the chunk");
        assertEquals(plugin + ":crate", display.mark());
        assertEquals(Display.Billboard.CENTER, display.billboard);
        assertEquals("Vote crate\nClick", plain(display.text));
    }

    @Test
    void spawnNeverLoadsAChunkAndWaitsForIt() {
        holograms.spawn("crate", here(), List.of("a"));
        host.tick();
        assertTrue(host.spawned.isEmpty());

        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        assertTrue(host.spawned.isEmpty(), "spawned on the next tick, not inside the event");
        host.tick();
        assertEquals(1, host.alive().size());
    }

    @Test
    void spawnWithoutAWorldWarnsAndDoesNothing() {
        holograms.spawn("crate", new Location(null, 0, 0, 0), List.of("a"));
        holograms.spawn("crate", null, List.of("a"));

        assertEquals(2, host.warnings.size());
        assertNull(holograms.hologram("crate"));
    }

    @Test
    void spawnReplacesTheHologramWithTheSameId() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("old"));
        FakeDisplay first = host.spawned.get(0);
        holograms.spawn("crate", here(), List.of("new"));

        assertTrue(first.removed);
        assertEquals(1, host.alive().size());
        assertEquals("new", plain(host.alive().get(0).text));
        assertEquals(1, HologramChunkListener.INSTANCE.countAt("world", 0, 1));
    }

    @Test
    void spawnOffTheChunkThreadIsDeferredToIt() {
        world.load(0, 1);
        host.owns = false;
        holograms.spawn("crate", here(), List.of("a"));
        assertTrue(host.spawned.isEmpty());
        host.tick();
        assertEquals(1, host.alive().size());
    }

    @Test
    void aCancelledSpawnIsReportedOnce() {
        world.load(0, 1);
        host.cancelSpawns = true;
        holograms.spawn("crate", here(), List.of("a"));
        holograms.setLines("crate", List.of("b"));
        host.tick();

        assertEquals(1, host.warnings.size());
    }

    // ---------------------------------------------------------------- chunk unload and load (trap 11.6.2)

    @Test
    void anUnloadedChunkTakesTheDisplayAndLoadingItSpawnsExactlyOneNewOne() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay first = host.spawned.get(0);

        world.unload(0, 1, host);
        assertFalse(first.valid);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();

        assertEquals(1, host.alive().size());
        assertNotSame(first, host.alive().get(0));
        assertSame(host.alive().get(0).entity, holograms.hologram("crate").entity);
    }

    @Test
    void loadingAChunkWhoseHologramIsStillThereSpawnsNothing() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();

        assertEquals(1, host.spawned.size());
    }

    @Test
    void theChunkListenerNeverReadsTheWorldsEntities() {
        // FakeWorld fails on any World method other than getName/isChunkLoaded (getEntities included).
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        world.unload(0, 1, host);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 5, 5);
        HologramChunkListener.INSTANCE.chunkLoaded("other", 0, 1);
        host.tick();
        assertEquals(1, host.alive().size());
    }

    @Test
    void linesSetWhileUnloadedAreShownWhenTheChunkComesBack() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        world.unload(0, 1, host);
        holograms.setLines("crate", List.of("b", "c"));
        assertEquals(1, host.spawned.size(), "nothing spawned while unloaded");

        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        assertEquals("b\nc", plain(host.alive().get(0).text));
    }

    @Test
    void aDisplayKilledByAnotherPluginComesBackOnTheNextUpdate() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        host.spawned.get(0).valid = false;

        holograms.setLines("crate", List.of("b"));
        assertEquals(1, host.alive().size());
        assertEquals("b", plain(host.alive().get(0).text));
    }

    // ---------------------------------------------------------------- removed by a command or another plugin

    /** What {@code /kill} does to the display: it is gone and the removal listener hears of it. */
    private static void killed(FakeDisplay display) {
        display.valid = false;
        HologramUtil.removedByOther(display.entity);
    }

    @Test
    void theRemovalListenerExistsOnThisServerApi() {
        assertTrue(HologramUtil.removalListener() instanceof HologramRemovalListener);
    }

    @Test
    void aHologramRemovedByACommandComesBackOnTheNextTick() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("&aHi"));
        FakeDisplay first = host.spawned.get(0);

        killed(first);
        assertTrue(host.alive().isEmpty(), "back on the next tick, not inside the removal");
        host.tick();

        assertEquals(1, host.alive().size());
        FakeDisplay second = host.alive().get(0);
        assertNotSame(first, second);
        assertEquals("Hi", plain(second.text));
        assertEquals(plugin + ":crate", second.mark());
        assertSame(second.entity, holograms.hologram("crate").entity);
        assertTrue(host.warnings.isEmpty(), host.warnings.toString());
    }

    @Test
    void removalsRsLibDidItselfAreNotPutBack() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        holograms.spawn("moved", here(), List.of("b"));
        FakeDisplay crate = host.spawned.get(0);
        FakeDisplay moved = host.spawned.get(1);

        holograms.delete("crate");
        holograms.moveTo("moved", new Location(world.world, 90, 64, 90));
        host.tick();
        HologramUtil.removedByOther(crate.entity);
        HologramUtil.removedByOther(moved.entity);
        host.tick();

        assertTrue(host.alive().isEmpty(), "the moved one waits for its new chunk: " + host.alive());
        assertTrue(crate.removed && moved.removed);
        assertNull(holograms.hologram("crate"));
    }

    @Test
    void displaysThatAreNotTheHologramsOwnAreIgnored() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay foreign = FakeDisplay.marked(OTHER_MARK, plugin + ":crate");
        FakeDisplay copy = FakeDisplay.marked(HologramUtil.MARK.toString(), plugin + ":crate");
        FakeDisplay unknown = FakeDisplay.marked(HologramUtil.MARK.toString(), plugin + ":gone");

        killed(foreign);
        killed(copy);
        killed(unknown);
        host.tick();

        assertEquals(1, host.spawned.size(), "nothing new spawned");
        assertTrue(host.spawned.get(0).valid);
        assertTrue(foreign.calls.isEmpty() && copy.calls.isEmpty() && unknown.calls.isEmpty());
    }

    @Test
    void aRemovalWhileTheChunkIsNotLoadedWaitsForTheChunk() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay first = host.spawned.get(0);

        world.unload(0, 1, host);
        HologramUtil.removedByOther(first.entity);
        host.tick();
        assertTrue(host.alive().isEmpty());

        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        assertEquals(1, host.alive().size());
    }

    @Test
    void tooManyRemovalsInARowAreReportedOnceAndLeaveItDownUntilItsLinesChange() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));

        for (int i = 0; i < HologramUtil.MAX_RESPAWNS; i++) {
            killed(host.alive().get(0));
            host.tick();
            assertEquals(1, host.alive().size(), "respawn " + (i + 1));
        }
        killed(host.alive().get(0));
        host.tick();
        assertTrue(host.alive().isEmpty());
        assertEquals(1, host.warnings.size());
        assertTrue(host.warnings.get(0).contains("'crate'"), host.warnings.get(0));

        holograms.setLines("crate", List.of("b"));
        assertEquals(1, host.alive().size());
        assertEquals("b", plain(host.alive().get(0).text));
    }

    @Test
    void theRespawnWindowStartsAgainAfterTenSeconds() {
        Hologram hologram = new Hologram(holograms, "w", here(), List.of());
        long start = 1_000L;
        for (int i = 0; i < HologramUtil.MAX_RESPAWNS; i++) {
            assertTrue(hologram.countRespawn(start + i));
        }
        assertFalse(hologram.countRespawn(start + HologramUtil.MAX_RESPAWNS));
        assertTrue(hologram.countRespawn(start + HologramUtil.RESPAWN_WINDOW_NANOS + 1));
    }

    // ---------------------------------------------------------------- other plugins' entities (traps 11.6.1, 11.6.3)

    @Test
    void otherPluginsHologramsAreNeverAdoptedChangedOrRemoved() {
        world.load(0, 0);
        holograms.spawn("crate", new Location(world.world, 1, 64, 1), List.of("a"));
        FakeDisplay legacy = FakeDisplay.marked(OTHER_MARK, plugin + ":crate");

        assertFalse(HologramUtil.adopt(legacy.entity));

        assertTrue(legacy.valid);
        assertTrue(legacy.calls.isEmpty(), "untouched: " + legacy.calls);
        assertEquals(plugin + ":crate", legacy.data.get(OTHER_MARK));
        assertTrue(legacy.persistent);
    }

    @Test
    void unmarkedDisplaysAreNeverTouched() {
        FakeDisplay foreign = FakeDisplay.marked(null, null);

        assertFalse(HologramUtil.adopt(foreign.entity));
        assertTrue(foreign.valid);
        assertTrue(foreign.calls.isEmpty());
    }

    @Test
    void anRsLibDisplayNoHologramClaimsIsRemoved() {
        FakeDisplay stale = FakeDisplay.marked(HologramUtil.MARK.toString(), plugin + ":gone");
        FakeDisplay unknownPlugin = FakeDisplay.marked(HologramUtil.MARK.toString(), "NoSuchPlugin:crate");
        FakeDisplay garbage = FakeDisplay.marked(HologramUtil.MARK.toString(), "nocolon");

        assertFalse(HologramUtil.adopt(stale.entity));
        assertFalse(HologramUtil.adopt(unknownPlugin.entity));
        assertFalse(HologramUtil.adopt(garbage.entity));
        assertTrue(stale.removed && unknownPlugin.removed && garbage.removed);
    }

    @Test
    void anRsLibDisplayIsAdoptedByItsHologramWhenItHasNone() {
        holograms.spawn("crate", here(), List.of("&aHi"));
        FakeDisplay found = FakeDisplay.marked(HologramUtil.MARK.toString(), plugin + ":crate");

        assertTrue(HologramUtil.adopt(found.entity));

        assertFalse(found.persistent);
        assertEquals("Hi", plain(found.text));
        assertEquals(Display.Billboard.CENTER, found.billboard);
        assertSame(found.entity, holograms.hologram("crate").entity);
    }

    @Test
    void aSecondCopyOfAnRsLibHologramIsRemoved() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        FakeDisplay copy = FakeDisplay.marked(HologramUtil.MARK.toString(), plugin + ":crate");

        assertFalse(HologramUtil.adopt(copy.entity));
        assertTrue(copy.removed);
        assertTrue(host.spawned.get(0).valid);
        assertTrue(HologramUtil.adopt(host.spawned.get(0).entity), "its own entity stays adopted");
    }

    // ---------------------------------------------------------------- text, billboard, refresh

    @Test
    void linesGoThroughPlaceholdersThenColorsAndJoinWithLineBreaks() {
        host.papi = line -> line.replace("%online%", "&a7");
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("&6Crate", "", "%online% online"));

        assertEquals("Crate\n\n7 online", plain(host.spawned.get(0).text));
    }

    @Test
    void renderOfNoLinesIsEmpty() {
        assertEquals(Component.empty(), HologramUtil.render(List.of(), UnaryOperator.identity()));
        assertEquals(List.of("", "a"), HologramUtil.copy(Arrays.asList(null, "a")));
        assertEquals(List.of(), HologramUtil.copy(null));
    }

    @Test
    void billboardIsKeptForTheNextEntity() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        holograms.setBillboard("crate", Display.Billboard.VERTICAL);
        assertEquals(Display.Billboard.VERTICAL, host.spawned.get(0).billboard);

        world.unload(0, 1, host);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        assertEquals(Display.Billboard.VERTICAL, host.alive().get(0).billboard);
    }

    @Test
    void refreshRendersOnlyWhileTheHologramIsInTheWorldAndOnlyAppliesChanges() {
        AtomicInteger renders = new AtomicInteger();
        int[] online = {1};
        host.papi = line -> {
            renders.incrementAndGet();
            return line.replace("%online%", String.valueOf(online[0]));
        };
        holograms.spawn("crate", here(), List.of("%online%"));
        holograms.refreshEvery("crate", 20);
        FakeHologramHost.Timer timer = host.timers.get(0);
        assertEquals(20, timer.period);

        timer.fire();
        assertEquals(0, renders.get(), "chunk not loaded: no placeholder work");

        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        FakeDisplay display = host.alive().get(0);
        int textsBefore = count(display.calls, "text");
        timer.fire();
        assertEquals(textsBefore, count(display.calls, "text"), "same text is not sent again");
        online[0] = 2;
        timer.fire();
        assertEquals("2", plain(display.text));
    }

    @Test
    void refreshEveryReplacesAndZeroCancels() {
        holograms.spawn("crate", here(), List.of("a"));
        holograms.refreshEvery("crate", 20);
        holograms.refreshEvery("crate", 40);
        assertTrue(host.timers.get(0).cancelled);
        assertFalse(host.timers.get(1).cancelled);

        holograms.refreshEvery("crate", 0);
        assertTrue(host.timers.get(1).cancelled);
        assertEquals(2, host.timers.size());
    }

    // ---------------------------------------------------------------- visibility

    @Test
    void hiddenPlayersStayHiddenOnTheNextEntityUntilTheyLeave() {
        FakePlayer player = new FakePlayer();
        host.players.put(player.id, player);
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        holograms.hideFrom(player.player, "crate");
        assertTrue(player.hidden.contains(host.spawned.get(0).entity));

        world.unload(0, 1, host);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        FakeDisplay second = host.alive().get(0);
        assertTrue(player.hidden.contains(second.entity));

        holograms.showTo(player.player, "crate");
        assertFalse(player.hidden.contains(second.entity));
        assertTrue(holograms.hologram("crate").hiddenFrom.isEmpty());
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteRemovesTheEntityAndTheChunkNoLongerBringsItBack() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        holograms.refreshEvery("crate", 20);
        holograms.delete("crate");

        assertTrue(host.spawned.get(0).removed);
        assertTrue(host.timers.get(0).cancelled);
        world.unload(0, 1, host);
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();
        assertTrue(host.alive().isEmpty());
    }

    @Test
    void deleteWhileUnloadedLeavesNothingBehind() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        world.unload(0, 1, host);
        holograms.delete("crate");
        world.load(0, 1);
        HologramChunkListener.INSTANCE.chunkLoaded("world", 0, 1);
        host.tick();

        assertTrue(host.alive().isEmpty());
        assertEquals(0, HologramChunkListener.INSTANCE.countAt("world", 0, 1));
    }

    @Test
    void deleteAllRemovesEveryHologram() {
        world.load(0, 1);
        world.load(1, 1);
        holograms.spawn("a", here(), List.of("a"));
        holograms.spawn("b", new Location(world.world, 20, 64, 20), List.of("b"));
        holograms.deleteAll();

        assertTrue(host.alive().isEmpty());
        assertNull(holograms.hologram("a"));
        assertTrue(host.warnings.isEmpty());
    }

    @Test
    void unknownIdsWarnOncePerId() {
        holograms.setLines("nope", List.of("a"));
        holograms.delete("nope");
        holograms.refreshEvery("nope", 20);
        holograms.setBillboard("other", Display.Billboard.FIXED);

        assertEquals(2, host.warnings.size(), host.warnings.toString());
    }

    @Test
    void afterShutdownItsDisplaysAreNoLongerClaimed() {
        world.load(0, 1);
        holograms.spawn("crate", here(), List.of("a"));
        holograms.shutdown();
        FakeDisplay leftover = FakeDisplay.marked(HologramUtil.MARK.toString(), plugin + ":crate");

        assertFalse(HologramUtil.adopt(leftover.entity));
        assertTrue(leftover.removed);
    }

    // ---------------------------------------------------------------- helpers

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
