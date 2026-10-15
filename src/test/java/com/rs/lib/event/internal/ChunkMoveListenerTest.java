package com.rs.lib.event.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;

import com.rs.lib.event.RsChunkMoveEvent;

/** Trampas 3 and 4 of the event module: a hot path that allocates nothing, and a binding cancel. */
class ChunkMoveListenerTest {

    private static final World WORLD = FakeSink.world("world");
    private static final World NETHER = FakeSink.world("nether");
    private static final Player PLAYER = (Player) Proxy.newProxyInstance(ChunkMoveListenerTest.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> throw new UnsupportedOperationException(method.getName());
            });

    /** A location that counts its copies. */
    static final class Counted extends Location {
        int clones;

        Counted(World world, double x, double y, double z) {
            super(world, x, y, z);
        }

        @Override
        public Location clone() {
            clones++;
            return super.clone();
        }
    }

    private final FakeSink sink = new FakeSink();
    private final ChunkMoveListener listener = new ChunkMoveListener(sink);

    private PlayerMoveEvent move(Location from, Location to) {
        PlayerMoveEvent event = new PlayerMoveEvent(PLAYER, from, to);
        listener.onMove(event);
        return event;
    }

    @Test
    void aMoveInsideOneChunkTouchesNothing() {
        Counted from = new Counted(WORLD, 0.5, 64, 0.5);
        Counted to = new Counted(WORLD, 15.9, 80, 15.9);
        move(from, to);
        // Negative coordinates: -0.5 is block -1, chunk -1; -16 is still chunk -1.
        Counted negFrom = new Counted(WORLD, -0.5, 64, -0.5);
        Counted negTo = new Counted(WORLD, -16, 64, -16);
        move(negFrom, negTo);
        assertEquals(0, from.clones + to.clones + negFrom.clones + negTo.clones);
        assertEquals(0, sink.asked, "the listeners are not even looked up");
        assertTrue(sink.fired.isEmpty());
    }

    @Test
    void crossingAChunkOrWorldFiresWithCopies() {
        Counted from = new Counted(WORLD, 15.5, 64, 0);
        Counted to = new Counted(WORLD, 16.5, 64, 0);
        PlayerMoveEvent event = move(from, to);
        assertEquals(1, sink.fired.size());
        RsChunkMoveEvent fired = (RsChunkMoveEvent) sink.fired.get(0);
        assertSame(PLAYER, fired.getPlayer());
        assertNotSame(from, fired.fromLocation());
        assertNotSame(to, fired.toLocation());
        assertEquals(16.5, fired.toLocation().getX());
        assertFalse(event.isCancelled());

        move(new Location(WORLD, 0, 0, 0), new Location(WORLD, 0, 0, -1));
        move(new Location(WORLD, 0, 0, 0), new Location(NETHER, 0, 0, 0));
        assertEquals(3, sink.fired.size());
    }

    @Test
    void cancellingTheEventCancelsTheMove() {
        sink.reaction = event -> ((Cancellable) event).setCancelled(true);
        assertTrue(move(new Location(WORLD, 0, 0, 0), new Location(WORLD, 16, 0, 0)).isCancelled());
    }

    @Test
    void aCrossingNobodyListensToCopiesNothing() {
        sink.listened = false;
        Counted from = new Counted(WORLD, 15.5, 64, 0);
        Counted to = new Counted(WORLD, 16.5, 64, 0);
        move(from, to);
        assertEquals(1, sink.asked);
        assertEquals(0, from.clones + to.clones);
        assertTrue(sink.fired.isEmpty());
    }

    @Test
    void registeredAtNormalIgnoringCancelledMoves() throws NoSuchMethodException {
        EventHandler handler = ChunkMoveListener.class.getMethod("onMove", PlayerMoveEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.NORMAL, handler.priority());
        assertTrue(handler.ignoreCancelled());
        assertSame(ChunkMoveListener.shared(), ChunkMoveListener.shared());
    }
}
