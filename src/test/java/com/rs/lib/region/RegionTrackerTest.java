package com.rs.lib.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.event.RsRegionChangeEvent;
import com.rs.lib.event.RsRegionChangeEvent.Cause;
import com.rs.lib.scheduler.TaskHandle;

/** Enter and leave: one event per step, both sets in it, and every way a player crosses a border. */
class RegionTrackerTest {

    private static final World WORLD = Fakes.world("world");
    private static final World NETHER = Fakes.world("nether");

    /** Players, events, queued tasks and the check timer, all by hand. */
    static final class Host implements RegionHost {
        final List<Player> online = new ArrayList<>();
        final List<RsRegionChangeEvent> events = new ArrayList<>();
        final List<Runnable> queued = new ArrayList<>();
        final Set<Player> elsewhere = new HashSet<>();
        Fakes.Timer timer;
        boolean listened = true;

        @Override
        public Collection<? extends Player> online() {
            return online;
        }

        @Override
        public boolean ownsHere(Player player) {
            return !elsewhere.contains(player);
        }

        @Override
        public void runFor(Player player, Runnable task) {
            queued.add(task);
        }

        @Override
        public boolean listened() {
            return listened;
        }

        @Override
        public void fire(RsRegionChangeEvent event) {
            events.add(event);
        }

        @Override
        public TaskHandle timer(long periodTicks, Runnable task) {
            timer = new Fakes.Timer(periodTicks, task);
            return timer;
        }

        @Override
        public Logger logger() {
            return Logger.getAnonymousLogger();
        }

        void runQueued() {
            List<Runnable> now = new ArrayList<>(queued);
            queued.clear();
            now.forEach(Runnable::run);
        }

        RsRegionChangeEvent last() {
            return events.get(events.size() - 1);
        }
    }

    private final RegionTracker tracker = new RegionTracker();
    private final RegionRegistry arena = new RegionRegistry("Arena", tracker);
    private final RegionRegistry other = new RegionRegistry("Other", tracker);
    private final Host host = new Host();
    private Fakes.FakePlayer alex;

    private static Location at(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    private static List<String> ids(List<RsRegion> regions) {
        return regions.stream().map(RsRegion::id).toList();
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(RegionTrackerTest.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @BeforeEach
    void start() {
        alex = new Fakes.FakePlayer("Alex", at(WORLD, 0.5, 64, 0.5));
        host.online.add(alex.player);
        tracker.attach(host, 5);
    }

    /** Moves Alex as a walk: the location changes and the tracker hears the move. */
    private void walk(double x, double y, double z) {
        Location to = at(alex.at.getWorld(), x, y, z);
        alex.at = to;
        tracker.moved(alex.player, to);
    }

    @Test
    @SuppressWarnings("removal")
    void joiningInsideARegionIsNotEnteringIt() {
        arena.register("spawn", Cuboid.of("world", -10, 0, -10, 10, 100, 10));
        assertEquals(1, host.events.size(), "Alex was online when it was made: he enters");
        host.events.clear();

        Fakes.FakePlayer sam = new Fakes.FakePlayer("Sam", at(WORLD, 1, 64, 1));
        host.online.add(sam.player);
        new RegionListener(tracker).onJoin(new PlayerJoinEvent(sam.player, (String) null));
        assertTrue(host.events.isEmpty());
        assertEquals(List.of("spawn"), ids(arena.of(sam.player)), "but his regions are recorded");
        assertTrue(arena.isIn(sam.player, "spawn"));
    }

    @Test
    void leavingAndEnteringInOneStepIsOneEvent() {
        arena.register("a", Cuboid.of("world", 0, 0, 0, 4, 100, 4));
        arena.register("b", Cuboid.of("world", 5, 0, 0, 9, 100, 4));
        host.events.clear();
        walk(4.5, 64, 0.5);
        assertTrue(host.events.isEmpty(), "still inside a");
        walk(5.5, 64, 0.5);
        assertEquals(1, host.events.size());
        RsRegionChangeEvent event = host.last();
        assertEquals(List.of("a"), ids(event.left()));
        assertEquals(List.of("b"), ids(event.entered()));
        assertEquals(Cause.MOVE, event.cause());
        assertSame(alex.player, event.getPlayer());
        assertEquals(5, event.location().getBlockX());
        walk(20, 64, 0.5);
        assertEquals(List.of("b"), ids(host.last().left()));
        assertTrue(host.last().entered().isEmpty());
        assertTrue(arena.of(alex.player).isEmpty());
    }

    @Test
    void overlappingRegionsComeInRegistrationOrder() {
        walk(100, 64, 100);
        arena.register("outer", Cuboid.of("world", -20, 0, -20, 20, 100, 20));
        arena.register("inner", Cuboid.of("world", -2, 0, -2, 2, 100, 2));
        other.register("shop", Cuboid.of("world", -5, 0, -5, 5, 100, 5));
        host.events.clear();
        walk(0.5, 64, 0.5);
        RsRegionChangeEvent event = host.last();
        assertEquals(List.of("outer", "inner", "shop"), ids(event.entered()));
        assertEquals(List.of("outer", "inner"), ids(event.entered(plugin("Arena"))));
        assertEquals(List.of("shop"), ids(event.entered(plugin("Other"))));
        assertTrue(event.involves(plugin("Other")));
        assertFalse(event.involves(plugin("Nobody")));
        assertEquals(List.of("outer", "inner"), ids(arena.at(at(WORLD, 0, 64, 0))));
    }

    @Test
    void moveListenerIgnoresStepsInsideTheSameBlock() {
        arena.register("a", Cuboid.of("world", 1, 0, 0, 4, 100, 4));
        host.events.clear();
        RegionListener listener = new RegionListener(tracker);
        Location from = at(WORLD, 0.1, 64, 0.1);
        listener.onMove(new PlayerMoveEvent(alex.player, from, at(WORLD, 0.9, 64.5, 0.9)));
        assertTrue(host.events.isEmpty());
        listener.onMove(new PlayerMoveEvent(alex.player, from, at(WORLD, 1.2, 64, 0.5)));
        assertEquals(List.of("a"), ids(host.last().entered()));
    }

    @Test
    void teleportsAndWorldChangesCarryTheirCause() {
        arena.register("lobby", Cuboid.of("world", 100, 0, 100, 110, 100, 110));
        arena.register("hell", Cuboid.of("nether", 0, 0, 0, 10, 100, 10));
        host.events.clear();
        RegionListener listener = new RegionListener(tracker);

        Location lobby = at(WORLD, 105, 64, 105);
        listener.onTeleport(new PlayerTeleportEvent(alex.player, alex.at, lobby));
        alex.at = lobby;
        assertEquals(Cause.TELEPORT, host.last().cause());
        assertEquals(List.of("lobby"), ids(host.last().entered()));

        Location hell = at(NETHER, 5, 64, 5);
        listener.onTeleport(new PlayerTeleportEvent(alex.player, alex.at, hell));
        alex.at = hell;
        assertEquals(Cause.WORLD_CHANGE, host.last().cause());
        assertEquals(List.of("lobby"), ids(host.last().left()));
        assertEquals(List.of("hell"), ids(host.last().entered()));
        int before = host.events.size();
        tracker.changedWorld(alex.player);
        assertEquals(before, host.events.size(), "the world change after the teleport finds nothing new");

        // A portal: no teleport event reaches the listener, only the world change.
        alex.at = at(WORLD, 105, 64, 105);
        tracker.changedWorld(alex.player);
        assertEquals(Cause.WORLD_CHANGE, host.last().cause());
        assertEquals(List.of("hell"), ids(host.last().left()));
        assertEquals(List.of("lobby"), ids(host.last().entered()));
    }

    @Test
    void creatingOrRemovingARegionAroundAStandingPlayerCounts() {
        RsRegion spawn = arena.register("spawn", Cuboid.of("world", -10, 0, -10, 10, 100, 10));
        assertEquals(Cause.REGION_REGISTERED, host.last().cause());
        assertEquals(List.of(spawn), host.last().entered());

        assertTrue(arena.unregister("spawn"));
        assertEquals(Cause.REGION_UNREGISTERED, host.last().cause());
        assertEquals(List.of("spawn"), ids(host.last().left()));
        assertFalse(arena.unregister("spawn"));
        assertEquals(2, host.events.size());

        arena.register("far", Cuboid.of("world", 500, 0, 500, 510, 100, 510));
        assertEquals(2, host.events.size(), "a region elsewhere changes nothing");
    }

    @Test
    void registeringAgainWithAnotherBoxOnlyReportsWhatChanged() {
        arena.register("zone", Cuboid.of("world", -10, 0, -10, 10, 100, 10));
        host.events.clear();
        arena.register("zone", Cuboid.of("world", -5, 0, -5, 5, 100, 5));
        assertTrue(host.events.isEmpty(), "still inside the new box: no leave, no enter");
        assertEquals(1, arena.all().size());
        arena.register("zone", Cuboid.of("world", 50, 0, 50, 60, 100, 60));
        assertEquals(List.of("zone"), ids(host.last().left()));
        assertEquals(Cause.REGION_REGISTERED, host.last().cause());
    }

    @Test
    void unregisterAllLeavesButShutdownIsSilent() {
        arena.register("a", Cuboid.of("world", -10, 0, -10, 10, 100, 10));
        arena.register("b", Cuboid.of("world", -1, 0, -1, 1, 100, 1));
        other.register("c", Cuboid.of("world", -3, 0, -3, 3, 100, 3));
        host.events.clear();
        arena.unregisterAll();
        assertEquals(List.of("a", "b"), ids(host.last().left()));
        assertEquals(List.of("c"), ids(other.of(alex.player)));

        host.events.clear();
        other.shutdown();
        assertTrue(host.events.isEmpty(), "a plugin going down tells nobody");
        assertTrue(other.all().isEmpty());
        assertTrue(other.of(alex.player).isEmpty());
        walk(30, 64, 30);
        assertTrue(host.events.isEmpty(), "and the released region is not left later either");

        // Registered again (the plugin came back) around a standing player: he enters it.
        walk(0.5, 64, 0.5);
        other.register("c", Cuboid.of("world", -3, 0, -3, 3, 100, 3));
        assertEquals(List.of("c"), ids(host.last().entered()));
    }

    @Test
    void thePeriodicCheckCatchesUnreportedMoves() {
        assertNotNull(host.timer);
        assertEquals(5, host.timer.period);
        arena.register("a", Cuboid.of("world", 10, 0, 10, 20, 100, 20));
        host.events.clear();
        host.timer.fire();
        assertTrue(host.events.isEmpty(), "nobody moved");
        alex.at = at(WORLD, 15, 64, 15); // moved by packet: no event
        host.timer.fire();
        assertEquals(Cause.CHECK, host.last().cause());
        assertEquals(List.of("a"), ids(host.last().entered()));
        host.timer.fire();
        assertEquals(1, host.events.size(), "found once");
    }

    @Test
    void playersOnAnotherThreadAreJudgedThereOnce() {
        host.elsewhere.add(alex.player);
        arena.register("a", Cuboid.of("world", -10, 0, -10, 10, 100, 10));
        arena.register("b", Cuboid.of("world", -5, 0, -5, 5, 100, 5));
        assertTrue(host.events.isEmpty(), "never from the wrong thread");
        assertEquals(1, host.queued.size(), "two registrations, one queued re-check");
        host.runQueued();
        assertEquals(1, host.events.size());
        assertEquals(List.of("a", "b"), ids(host.last().entered()));

        alex.at = at(WORLD, 50, 64, 50);
        host.timer.fire();
        host.timer.fire();
        assertEquals(1, host.queued.size(), "the check is queued once too");
        host.runQueued();
        assertEquals(Cause.CHECK, host.last().cause());
        assertEquals(List.of("a", "b"), ids(host.last().left()));
    }

    @Test
    @SuppressWarnings("removal")
    void quittingForgetsThePlayerWithoutAnEvent() {
        arena.register("a", Cuboid.of("world", -10, 0, -10, 10, 100, 10));
        host.events.clear();
        assertEquals(1, tracker.trackedCount());
        new RegionListener(tracker).onQuit(new PlayerQuitEvent(alex.player, (String) null));
        assertEquals(0, tracker.trackedCount());
        assertTrue(host.events.isEmpty());
        assertTrue(arena.of(alex.player).isEmpty());
    }

    @Test
    void nobodyListeningBuildsNoEvent() {
        host.listened = false;
        arena.register("a", Cuboid.of("world", -10, 0, -10, 10, 100, 10));
        assertTrue(host.events.isEmpty());
        assertTrue(arena.isIn(alex.player, "a"), "the record is still kept");
    }

    @Test
    void theCheckIntervalIsClampedAndRestarts() {
        Fakes.Timer first = host.timer;
        tracker.interval(0);
        assertTrue(first.cancelled);
        assertEquals(1, host.timer.period);
        tracker.interval(10_000);
        assertEquals(200, host.timer.period);
        Fakes.Timer current = host.timer;
        tracker.interval(500);
        assertSame(current, host.timer, "the same clamped period does not restart it");
        tracker.detach();
        assertTrue(current.cancelled);
        assertEquals(0, tracker.trackedCount());
    }

    @Test
    void idsAreCheckedAndScopedToTheirPlugin() {
        assertThrows(IllegalArgumentException.class, () -> arena.register(" ", Cuboid.of("world", 0, 0, 0, 1, 1, 1)));
        arena.register("x", Cuboid.of("world", 0, 0, 0, 1, 1, 1));
        other.register("x", Cuboid.of("world", 5, 5, 5, 6, 6, 6));
        assertEquals(Cuboid.of("world", 0, 0, 0, 1, 1, 1), arena.get("x").area());
        assertEquals(Cuboid.of("world", 5, 5, 5, 6, 6, 6), other.get("x").area());
        assertNull(arena.get("y"));
        assertEquals("Arena:x", arena.get("x").key());
    }
}
