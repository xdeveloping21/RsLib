package com.rs.lib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pure decisions of the shared listener, the equipment backup file, warnings and redeem
 * settings. The events themselves need a server.
 */
class ItemRulesTest {

    @TempDir
    Path dir;

    @Test
    void equipRules() {
        // Nothing is refused for the main hand or no slot.
        assertFalse(ItemListener.equipRefused(EquipmentSlot.HAND, EquipmentSlot.HEAD, true));
        assertFalse(ItemListener.equipRefused(null, EquipmentSlot.HEAD, true));
        // no-manual-equip, locked and COMMAND_ONLY refuse every equipment slot.
        assertTrue(ItemListener.equipRefused(EquipmentSlot.HEAD, null, true));
        assertTrue(ItemListener.equipRefused(EquipmentSlot.OFF_HAND, EquipmentSlot.OFF_HAND, true));
        // A declared slot only takes that slot.
        assertFalse(ItemListener.equipRefused(EquipmentSlot.HEAD, EquipmentSlot.HEAD, false));
        assertTrue(ItemListener.equipRefused(EquipmentSlot.CHEST, EquipmentSlot.HEAD, false));
        assertTrue(ItemListener.equipRefused(EquipmentSlot.OFF_HAND, EquipmentSlot.HAND, false));
        assertFalse(ItemListener.equipRefused(EquipmentSlot.CHEST, null, false));
    }

    @Test
    void slotNumbers() {
        assertEquals(EquipmentSlot.FEET, ItemListener.playerSlot(36));
        assertEquals(EquipmentSlot.HEAD, ItemListener.playerSlot(39));
        assertEquals(EquipmentSlot.OFF_HAND, ItemListener.playerSlot(40));
        assertNull(ItemListener.playerSlot(0));
        assertEquals(EquipmentSlot.HEAD, ItemListener.craftingViewSlot(5));
        assertEquals(EquipmentSlot.FEET, ItemListener.craftingViewSlot(8));
        assertEquals(EquipmentSlot.OFF_HAND, ItemListener.craftingViewSlot(45));
        assertNull(ItemListener.craftingViewSlot(36));
    }

    /** Trampa 11: both hands fire for one click; a redeem happens at most once per tick and id. */
    @Test
    void redeemOncePerTickAndId() {
        ItemListener.RedeemStamp stamp = new ItemListener.RedeemStamp();
        UUID player = UUID.randomUUID();
        assertTrue(stamp.first(player, "voucher", 100));
        assertFalse(stamp.first(player, "voucher", 100), "the off-hand event of the same click");
        assertTrue(stamp.first(player, "coin", 100), "another id redeems");
        assertTrue(stamp.first(UUID.randomUUID(), "voucher", 100), "another player redeems");
        assertTrue(stamp.first(player, "voucher", 101), "the next click redeems");
    }

    @Test
    void redeemSpecs() {
        assertEquals(RedeemSpec.Mode.SINGLE, RedeemSpec.single().mode());
        assertEquals(1, RedeemSpec.single().limit());
        assertEquals(RedeemSpec.Mode.HAND_STACK, RedeemSpec.handStack().mode());
        assertEquals(0, RedeemSpec.allMatching().limit());
        assertEquals(0, RedeemSpec.allMatching(-4).limit());
        assertEquals(64, RedeemSpec.allMatching(64).limit());
        RedeemSpec blocked = RedeemSpec.single().blockedOn(List.of(Material.CHEST, Material.BARREL));
        assertEquals(Set.of(Material.CHEST, Material.BARREL), blocked.blockedOn());
        assertTrue(RedeemSpec.single().blockedOn().isEmpty(), "blockedOn returns a copy");
        assertEquals(blocked, RedeemSpec.single().blockedOn(List.of(Material.BARREL, Material.CHEST)));
        assertEquals(blocked.hashCode(), RedeemSpec.single().blockedOn(List.of(Material.BARREL, Material.CHEST)).hashCode());
        assertThrows(UnsupportedOperationException.class, () -> blocked.blockedOn().add(Material.DIRT));
    }

    /** Trampa 12: a real item only goes back into a slot that is empty or holds the plugin's own item. */
    @Test
    void backupTarget() {
        assertEquals(EquipmentBackup.Target.SLOT, EquipmentBackup.target(true, false));
        assertEquals(EquipmentBackup.Target.SLOT, EquipmentBackup.target(false, true));
        assertEquals(EquipmentBackup.Target.INVENTORY, EquipmentBackup.target(false, false));
    }

    /** Trampa 12: every change is on disk at once, and a new start reads it back. */
    @Test
    void backupFileRoundTrip() throws IOException {
        Path file = dir.resolve("data").resolve("equipment-backup.yml");
        Logger logger = Logger.getLogger("backup-test");
        UUID player = UUID.randomUUID();
        EquipmentBackup backup = new EquipmentBackup(file, logger);
        backup.put(player, EquipmentSlot.HEAD, "crown", "QUJD");
        backup.put(player, EquipmentSlot.CHEST, "cape", null);
        assertTrue(Files.exists(file));

        EquipmentBackup again = new EquipmentBackup(file, logger);
        assertEquals(Set.of(player), again.players());
        assertEquals(new EquipmentBackup.Saved("crown", "QUJD"), again.get(player, EquipmentSlot.HEAD));
        assertEquals(new EquipmentBackup.Saved("cape", null), again.get(player, EquipmentSlot.CHEST));
        assertEquals(2, again.all(player).size());

        again.remove(player, EquipmentSlot.HEAD);
        assertNull(new EquipmentBackup(file, logger).get(player, EquipmentSlot.HEAD));
        again.remove(player, EquipmentSlot.CHEST);
        assertFalse(Files.exists(file), "an empty backup leaves no file");
        assertTrue(new EquipmentBackup(file, logger).players().isEmpty());
    }

    /** Trampa 10: each warning is printed once until a reload forgets them. */
    @Test
    void warningsAreDeduplicatedUntilForgotten() {
        Logger logger = Logger.getLogger("warnings-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        List<String> printed = new ArrayList<>();
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                printed.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        String id = UUID.randomUUID().toString();
        Consumer<String> first = ItemWarnings.to(logger, "menus/a.yml -> items." + id);
        Consumer<String> second = ItemWarnings.to(logger, "menus/b.yml -> items." + id);
        first.accept("Unknown material 'X'; using STONE.");
        first.accept("Unknown material 'X'; using STONE.");
        second.accept("Unknown material 'X'; using STONE.");
        assertEquals(2, printed.size(), "same text once per file and path");
        assertTrue(printed.get(0).startsWith("[menus/a.yml -> items." + id + "] "));
        ItemWarnings.forget();
        first.accept("Unknown material 'X'; using STONE.");
        assertEquals(3, printed.size());
    }

    /** A custom-durability call only breaks the stack when it takes it from above 0 to 0. */
    @Test
    void breakFlowOnlyOnTheBreakingCall() {
        assertTrue(ItemDurability.breaks(5, 0));
        assertTrue(ItemDurability.breaks(1, 0));
        assertFalse(ItemDurability.breaks(0, 0), "already broken: no second break flow");
        assertFalse(ItemDurability.breaks(5, 2));
        assertFalse(ItemDurability.breaks(-1, -1));
    }

    /** The stack is found by identity first, then by equality; -1 for a cursor or detached copy. */
    @Test
    void slotOfFindsIdentityFirst() {
        Label a = new Label("a");
        Label equalToA = new Label("a");
        Label b = new Label("b");
        Inventory inventory = inventory(equalToA, null, b, a);
        assertEquals(3, ItemDurability.slotOf(inventory, a));
        assertEquals(2, ItemDurability.slotOf(inventory, b));
        assertEquals(0, ItemDurability.slotOf(inventory, new Label("a")));
        assertEquals(-1, ItemDurability.slotOf(inventory, new Label("cursor")));
    }

    /** The redeem handler receives a snapshot it cannot change and that later changes do not reach. */
    @Test
    void redeemHandlerGetsAnImmutableSnapshot() {
        List<ItemStack> taken = new ArrayList<>(List.of(new Label("x")));
        List<ItemStack> snapshot = ItemListener.snapshot(taken);
        taken.add(new Label("y"));
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new Label("z")));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(0));
    }

    /** Item files may wrap their ids in a root items: section; a plain file reads its top level. */
    @Test
    void itemFileLayouts() throws InvalidConfigurationException {
        YamlConfiguration top = yaml("sword:\n  material: DIAMOND_SWORD\nwand:\n  material: STICK\n");
        assertFalse(ItemRegistry.wrapped(top));
        assertEquals("sword", ItemRegistry.itemsPath(top, "sword"));

        YamlConfiguration wrapped = yaml("items:\n  sword:\n    material: DIAMOND_SWORD\n  wand:\n    material: STICK\n");
        assertTrue(ItemRegistry.wrapped(wrapped));
        assertEquals("items.sword", ItemRegistry.itemsPath(wrapped, "sword"));
        assertEquals("missing", ItemRegistry.itemsPath(wrapped, "missing"));

        // An item that is itself called "items" stays a top-level item.
        assertFalse(ItemRegistry.wrapped(yaml("items:\n  material: CHEST\n  lore: [a]\n")));
        assertFalse(ItemRegistry.wrapped(yaml("items:\n  material: CHEST\n  custom-durability:\n    max: 3\n")));
        // A section holding a plain value is not a wrapper either.
        assertFalse(ItemRegistry.wrapped(yaml("items:\n  sword:\n    material: STONE\n  glow: true\n")));
        assertFalse(ItemRegistry.wrapped(null));
        // A top-level item of the same id wins over the wrapped one.
        YamlConfiguration both = yaml("items:\n  sword:\n    material: STONE\nsword:\n  material: STICK\n");
        assertEquals("sword", ItemRegistry.itemsPath(both, "sword"));
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }

    private static Inventory inventory(ItemStack... cells) {
        return (Inventory) Proxy.newProxyInstance(ItemRulesTest.class.getClassLoader(), new Class<?>[] {Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> cells.length;
                    case "getItem" -> cells[(Integer) args[0]];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A stack that is only a label: equal to any stack with the same label. */
    private static final class Label extends ItemStack {
        private final String label;

        @SuppressWarnings("deprecation")
        Label(String label) {
            super();
            this.label = label;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Label stack && stack.label.equals(label);
        }

        @Override
        public int hashCode() {
            return label.hashCode();
        }
    }
}
