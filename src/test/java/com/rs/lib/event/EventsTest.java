package com.rs.lib.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import com.rs.lib.region.Cuboid;

/** The event classes: handler lists, cancellation and getters (Trampas 2 and 3 of the event module). */
class EventsTest {

    private static final Player PLAYER = (Player) Proxy.newProxyInstance(EventsTest.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Steve";
                default -> throw new UnsupportedOperationException(method.getName());
            });

    /** A plugin's own event, written the way the javadoc of RsEvent says. */
    static final class RewardEvent extends RsEvent {
        private static final HandlerList HANDLERS = new HandlerList();

        RewardEvent(boolean async) {
            super(async);
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    @Test
    void everyConcreteEventHasItsOwnHandlerListAndTheBasesHaveNone() {
        List<HandlerList> lists = List.of(RsArmourEquipEvent.getHandlerList(), RsChunkMoveEvent.getHandlerList(),
                RsSelectionCompleteEvent.getHandlerList(), RewardEvent.getHandlerList());
        assertEquals(4, Set.copyOf(lists).size(), "a shared HandlerList would mix listeners of different events");
        // Bukkit looks for a static getHandlerList on the class it registers: the bases must not
        // declare one, or a listener of RsPlayerEvent would receive every subclass.
        for (Class<?> base : List.of(RsEvent.class, RsPlayerEvent.class)) {
            assertThrows(NoSuchMethodException.class, () -> base.getDeclaredMethod("getHandlerList"));
            assertTrue(Modifier.isAbstract(base.getModifiers()));
        }
        RsChunkMoveEvent move = new RsChunkMoveEvent(PLAYER, new Location(null, 0, 0, 0), new Location(null, 16, 0, 0));
        assertSame(RsChunkMoveEvent.getHandlerList(), move.getHandlers());
    }

    @Test
    void cancellationIsKeptOnTheEvent() {
        RewardEvent event = new RewardEvent(true);
        assertTrue(event.isAsynchronous());
        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
        event.setCancelled(false);
        assertFalse(event.isCancelled());
        RsArmourEquipEvent armour = new RsArmourEquipEvent(PLAYER, EquipMethod.DEATH, EquipmentSlot.HEAD, null, null);
        assertFalse(armour.isAsynchronous());
        armour.setCancelled(true);
        assertTrue(armour.isCancelled());
    }

    @Test
    void gettersGiveBackWhatTheEventWasMadeWith() {
        RsArmourEquipEvent armour = new RsArmourEquipEvent(PLAYER, EquipMethod.DISPENSER, EquipmentSlot.FEET, null, null);
        assertSame(PLAYER, armour.getPlayer());
        assertEquals(EquipMethod.DISPENSER, armour.getMethod());
        assertEquals(EquipmentSlot.FEET, armour.getSlot());
        assertNull(armour.getOldPiece());
        assertNull(armour.getNewPiece());

        Location from = new Location(null, 1, 2, 3);
        Location to = new Location(null, 17, 2, 3);
        RsChunkMoveEvent move = new RsChunkMoveEvent(PLAYER, from, to);
        assertSame(from, move.fromLocation());
        assertSame(to, move.toLocation());

        Cuboid box = Cuboid.of("world", 0, 0, 0, 1, 1, 1);
        RsSelectionCompleteEvent done = new RsSelectionCompleteEvent(PLAYER, null, "arena", box);
        assertEquals("arena", done.specId());
        assertSame(box, done.cuboid());
        assertNull(done.owner());
        assertNotSame(RsSelectionCompleteEvent.getHandlerList(), RsArmourEquipEvent.getHandlerList());
    }

    @Test
    void equipMethodsKeepTheirNamesAndOrder() {
        assertEquals("SHIFT_CLICK,DRAG,PICK_DROP,HOTBAR,HOTBAR_SWAP,DISPENSER,BROKE,DEATH",
                Stream.of(EquipMethod.values()).map(Enum::name).collect(Collectors.joining(",")));
    }
}
