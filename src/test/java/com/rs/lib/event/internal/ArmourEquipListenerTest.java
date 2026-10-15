package com.rs.lib.event.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.rs.lib.event.EquipMethod;
import com.rs.lib.event.RsArmourEquipEvent;

/**
 * The armour event (Trampas 1, 3 and 5 of the event module): methods, the binding dispenser
 * cancel, the one-tick dispenser de-duplication, and how it sits next to the item module.
 */
class ArmourEquipListenerTest {

    /** A stack that is only a material, an amount and, when damaged, a damage meta. */
    static final class Piece extends ItemStack {
        final Material type;
        final int amount;
        final int damage;
        final int maxDamage;

        @SuppressWarnings("deprecation")
        Piece(Material type, int amount, int damage, int maxDamage) {
            super();
            this.type = type;
            this.amount = amount;
            this.damage = damage;
            this.maxDamage = maxDamage;
        }

        static Piece piece(Material type) {
            return new Piece(type, 1, 0, 0);
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public boolean hasItemMeta() {
            return maxDamage > 0;
        }

        @Override
        public ItemMeta getItemMeta() {
            if (maxDamage <= 0) {
                return null;
            }
            return (ItemMeta) Proxy.newProxyInstance(Piece.class.getClassLoader(), new Class<?>[] {Damageable.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasDamage" -> damage > 0;
                        case "getDamage" -> damage;
                        case "hasMaxDamage" -> true;
                        case "getMaxDamage" -> maxDamage;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Override
        public Piece clone() {
            return new Piece(type, amount, damage, maxDamage);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Piece p && p.type == type && p.amount == amount && p.damage == damage;
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + damage;
        }

        @Override
        public String toString() {
            return type.name();
        }
    }

    private final UUID id = UUID.randomUUID();
    private final Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
    private boolean dead;
    private final PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getItem" -> worn.get((EquipmentSlot) args[0]);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    private final Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isDead" -> dead;
                case "getInventory" -> inventory;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Steve";
                default -> throw new UnsupportedOperationException(method.getName());
            });

    private final FakeSink sink = new FakeSink();
    private int tick = 100;
    private final ArmourEquipListener listener = new ArmourEquipListener(sink, () -> tick);

    private RsArmourEquipEvent only() {
        assertEquals(1, sink.fired.size(), sink.fired.toString());
        RsArmourEquipEvent event = (RsArmourEquipEvent) sink.fired.get(0);
        sink.fired.clear();
        return event;
    }

    // ------------------------------------------------------------------ Paper's change event

    @Test
    void handChangesArePickDropAndAPieceThatWoreOutIsBroke() {
        Piece helmet = Piece.piece(Material.DIAMOND_HELMET);
        listener.changed(player, "HEAD", null, helmet);
        RsArmourEquipEvent on = only();
        assertEquals(EquipMethod.PICK_DROP, on.getMethod());
        assertEquals(EquipmentSlot.HEAD, on.getSlot());
        assertNull(on.getOldPiece());
        assertSame(helmet, on.getNewPiece());

        listener.changed(player, "FEET", new Piece(Material.IRON_BOOTS, 1, 194, 195), Piece.piece(Material.AIR));
        RsArmourEquipEvent worn = only();
        assertEquals(EquipMethod.PICK_DROP, worn.getMethod(), "damage below the maximum is a hand change");
        assertNull(worn.getNewPiece(), "air is reported as null");

        listener.changed(player, "FEET", new Piece(Material.IRON_BOOTS, 1, 195, 195), null);
        assertEquals(EquipMethod.BROKE, only().getMethod());
        // Replaced by another piece: not broken, whatever the old one's damage.
        listener.changed(player, "FEET", new Piece(Material.IRON_BOOTS, 1, 195, 195), Piece.piece(Material.GOLDEN_BOOTS));
        assertEquals(EquipMethod.PICK_DROP, only().getMethod());
    }

    @Test
    void nothingToNothingAndChangesOfTheDeadAreNotReported() {
        listener.changed(player, "CHEST", null, null);
        listener.changed(player, "CHEST", Piece.piece(Material.AIR), new Piece(Material.CAVE_AIR, 1, 0, 0));
        listener.changed(player, "CHEST", Piece.piece(Material.ELYTRA), new Piece(Material.ELYTRA, 0, 0, 0));
        assertEquals(1, sink.fired.size(), "an empty stack is nothing; the third change takes the elytra off");
        sink.fired.clear();
        dead = true;
        listener.changed(player, "CHEST", Piece.piece(Material.ELYTRA), null);
        assertTrue(sink.fired.isEmpty(), "the death reports the pieces");
    }

    @Test
    void paperChangesAreReadThroughTheSlotTypeEveryVersionHas() {
        @SuppressWarnings("deprecation")
        PlayerArmorChangeEvent event = new PlayerArmorChangeEvent(player, PlayerArmorChangeEvent.SlotType.LEGS,
                Piece.piece(Material.AIR), Piece.piece(Material.CHAINMAIL_LEGGINGS));
        listener.onArmourChange(event);
        assertEquals(EquipmentSlot.LEGS, only().getSlot());
        sink.listened = false;
        listener.onArmourChange(event);
        assertTrue(sink.fired.isEmpty(), "nothing is built while nobody listens");
    }

    // ------------------------------------------------------------------ dispensers

    @Test
    void cancellingTheDispenserEventStopsTheDispenser() {
        worn.put(EquipmentSlot.HEAD, Piece.piece(Material.LEATHER_HELMET));
        Piece helmet = Piece.piece(Material.IRON_HELMET);
        assertTrue(listener.dispensing(player, helmet));
        RsArmourEquipEvent event = only();
        assertEquals(EquipMethod.DISPENSER, event.getMethod());
        assertEquals(EquipmentSlot.HEAD, event.getSlot());
        assertEquals(Piece.piece(Material.LEATHER_HELMET), event.getOldPiece());
        assertNotSame(worn.get(EquipmentSlot.HEAD), event.getOldPiece(), "listeners get a copy, not the worn stack");

        sink.reaction = e -> ((Cancellable) e).setCancelled(true);
        assertFalse(listener.dispensing(player, helmet));
        sink.fired.clear();
        // Not armour: nothing to report, the dispenser goes on.
        assertTrue(listener.dispensing(player, Piece.piece(Material.SADDLE)));
        assertTrue(sink.fired.isEmpty());
    }

    @Test
    void paperChangeAfterADispenserInTheSameOrNextTickIsTheSameChange() {
        Piece boots = Piece.piece(Material.DIAMOND_BOOTS);
        listener.dispensed(player, boots);
        tick++;
        listener.changed(player, "FEET", null, boots);
        assertTrue(sink.fired.isEmpty(), "next tick: dropped");

        listener.dispensed(player, boots);
        listener.changed(player, "FEET", null, boots);
        assertTrue(sink.fired.isEmpty(), "same tick: dropped");

        listener.dispensed(player, boots);
        listener.changed(player, "HEAD", null, Piece.piece(Material.DIAMOND_HELMET));
        assertEquals(EquipmentSlot.HEAD, only().getSlot(), "another slot is another change");

        tick += 2;
        listener.changed(player, "FEET", null, boots);
        assertEquals(EquipMethod.PICK_DROP, only().getMethod(), "two ticks later the mark is stale");

        listener.dispensed(player, boots);
        listener.changed(player, "FEET", null, boots);
        listener.changed(player, "FEET", boots, null);
        assertEquals(1, sink.fired.size(), "a mark drops one change only");
    }

    @Test
    void marksAreForgottenWhenThePlayerLeaves() {
        listener.dispensed(player, Piece.piece(Material.GOLDEN_CHESTPLATE));
        assertEquals(1, listener.marked());
        listener.forget(id);
        assertEquals(0, listener.marked());
    }

    @Test
    void theDispenserIsReportedAfterTheItemModuleHadItsSay() throws Exception {
        EventHandler ours = ArmourEquipListener.class.getMethod("onDispense", BlockDispenseArmorEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler mark = ArmourEquipListener.class.getMethod("onDispensed", BlockDispenseArmorEvent.class)
                .getAnnotation(EventHandler.class);
        // The item module refuses registered items that cannot be equipped by hand.
        EventHandler items = Class.forName("com.rs.lib.item.ItemListener")
                .getMethod("onDispenseArmour", BlockDispenseArmorEvent.class).getAnnotation(EventHandler.class);
        assertTrue(items.ignoreCancelled());
        assertTrue(ours.priority().getSlot() > items.priority().getSlot(),
                "a dispense the item module stops never reaches RsArmourEquipEvent");
        assertTrue(ours.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, ours.priority(), "it may still cancel the dispenser");
        assertEquals(EventPriority.MONITOR, mark.priority());
        assertTrue(mark.ignoreCancelled(), "only a dispenser that really fired leaves a mark");
    }

    // ------------------------------------------------------------------ death

    @Test
    void deathReportsEveryPieceUnlessTheInventoryIsKept() throws Exception {
        worn.put(EquipmentSlot.HEAD, Piece.piece(Material.TURTLE_HELMET));
        worn.put(EquipmentSlot.LEGS, Piece.piece(Material.NETHERITE_LEGGINGS));
        listener.died(player, true);
        assertTrue(sink.fired.isEmpty());
        listener.died(player, false);
        assertEquals(2, sink.fired.size());
        for (Object fired : sink.fired) {
            RsArmourEquipEvent event = (RsArmourEquipEvent) fired;
            assertEquals(EquipMethod.DEATH, event.getMethod());
            assertNull(event.getNewPiece());
        }
        assertEquals(List.of(EquipmentSlot.HEAD, EquipmentSlot.LEGS),
                sink.fired.stream().map(e -> ((RsArmourEquipEvent) e).getSlot()).toList());
        EventHandler handler = ArmourEquipListener.class.getMethod("onDeath", PlayerDeathEvent.class)
                .getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, handler.priority());
    }
}
