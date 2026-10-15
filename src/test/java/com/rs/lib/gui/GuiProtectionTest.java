package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.gui.GuiKit.TestPlayer;
import com.rs.lib.gui.GuiKit.TestStack;

/**
 * Anti-theft: stamped stacks outside menus are deleted. Click, number key, off-hand key, drag,
 * swap and drop are driven with real events; use, death and item-spawn events need server
 * objects (block faces, damage sources, locations) and are covered through the stamp check
 * they share.
 */
class GuiProtectionTest {

    private final GuiKit kit = new GuiKit();
    private final GuiProtection protection = new GuiProtection();
    private TestPlayer steve;

    /** A stack stamped by some plugin's menu (any namespace). */
    private static TestStack stamped(String label) {
        return TestStack.withKeys(label, new NamespacedKey("someplugin", GuiManager.ITEM_TAG));
    }

    @BeforeEach
    void setUp() {
        steve = kit.player("Steve");
    }

    /** Trampa 16: the stamp is found by its key name under any namespace, SnLib's name included. */
    @Test
    void stampsAreFoundUnderAnyNamespace() {
        assertTrue(GuiProtection.marked(stamped("a")));
        assertTrue(GuiProtection.marked(TestStack.withKeys("b", new NamespacedKey("other", "snlib_gui_item"))));
        assertTrue(GuiProtection.marked(TestStack.withKeys("c", new NamespacedKey("x", "something"),
                new NamespacedKey("y", GuiManager.ITEM_TAG))));
        assertFalse(GuiProtection.marked(TestStack.withKeys("d", new NamespacedKey("x", "rslib_gui_items"))));
        assertFalse(GuiProtection.marked(new TestStack("plain")));
        assertFalse(GuiProtection.marked(null));
    }

    @Test
    void menusAreRecognisedIncludingSnLibOnes() {
        assertFalse(GuiProtection.isMenu(null));
        assertFalse(GuiProtection.isMenu(kit.inventory(null, 9)));
        assertTrue(GuiProtection.isMenu(kit.inventory(new SnGuiHolder(), 9)));
        assertTrue(GuiProtection.isLegacyHolder(SnGuiHolder.class));
    }

    /** A holder class with SnLib's name, as an SnLib menu would have. */
    static final class SnGuiHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private InventoryClickEvent click(Inventory top, int raw, ClickType type, InventoryAction action, ItemStack cursor,
                                      int hotbar) {
        Views.View view = new Views.View(top, steve.inventory, steve.player);
        view.cursor = cursor;
        InventoryClickEvent event = hotbar >= 0
                ? new InventoryClickEvent(view.view, SlotType.CONTAINER, raw, type, action, hotbar)
                : new InventoryClickEvent(view.view, SlotType.CONTAINER, raw, type, action);
        protection.onClick(event);
        return event;
    }

    /** Vector 1: a stamped stack clicked outside a menu is deleted and the click cancelled. */
    @Test
    void clickedOutsideAMenu() {
        Inventory chest = kit.inventory(null, 27);
        steve.inventory.setItem(4, stamped("loot"));
        InventoryClickEvent event = click(chest, 27 + 4, ClickType.LEFT, InventoryAction.PICKUP_ALL, null, -1);
        assertTrue(event.isCancelled());
        assertNull(steve.inventory.getItem(4));
        chest.setItem(2, stamped("in a chest"));
        assertTrue(click(chest, 2, ClickType.LEFT, InventoryAction.PICKUP_ALL, null, -1).isCancelled());
        assertNull(chest.getItem(2));
    }

    @Test
    void stacksInsideAMenuAreLeftAlone() {
        Gui gui = kit.gui("shop", "items:\n  a: {material: STONE, slots: [0]}\n");
        gui.open(steve.player);
        Inventory menu = kit.inventoryOf(steve);
        menu.setItem(0, stamped("button"));
        InventoryClickEvent event = click(menu, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL, null, -1);
        assertFalse(event.isCancelled(), "the menu listener handles its own cells");
        assertNotNull(menu.getItem(0));
    }

    @Test
    void cursorNumberKeyAndOffHand() {
        Inventory chest = kit.inventory(null, 27);
        steve.cursor = stamped("held");
        assertTrue(click(chest, 0, ClickType.LEFT, InventoryAction.PLACE_ALL, steve.cursor, -1).isCancelled());
        assertNull(steve.cursor);

        steve.inventory.setItem(3, stamped("hotbar"));
        assertTrue(click(chest, 0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, null, 3).isCancelled());
        assertNull(steve.inventory.getItem(3));

        steve.offHand = stamped("offhand");
        assertTrue(click(chest, 0, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, null, 40).isCancelled());
        assertNull(steve.offHand);

        steve.inventory.setItem(5, new TestStack("mine"));
        assertFalse(click(chest, 27 + 5, ClickType.LEFT, InventoryAction.PICKUP_ALL, null, -1).isCancelled());
        assertNotNull(steve.inventory.getItem(5));
    }

    /** Vector 2: dragging a stamped stack is cancelled. */
    @Test
    void drags() {
        Views.View view = new Views.View(kit.inventory(null, 27), steve.inventory, steve.player);
        InventoryDragEvent event = new InventoryDragEvent(view.view, null, stamped("x"), false,
                java.util.Map.of(30, stamped("x")));
        protection.onDrag(event);
        assertTrue(event.isCancelled());
    }

    /** Vector 4: swapping hands with a stamped stack removes it from the hand it was in. */
    @Test
    void swapHands() {
        steve.offHand = stamped("from offhand");
        steve.mainHand = new TestStack("sword");
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(steve.player, steve.offHand, steve.mainHand);
        protection.onSwap(event);
        assertTrue(event.isCancelled());
        assertNull(steve.offHand);
        assertEquals("sword", ((TestStack) steve.mainHand).label);
    }

    /** Trampa 17 (vector 5): a dropped stamped stack's entity is removed; the drop itself is not cancelled. */
    @Test
    void dropsRemoveTheEntity() {
        List<String> removed = new ArrayList<>();
        TestStack stack = stamped("dropped");
        Item entity = (Item) Proxy.newProxyInstance(GuiProtectionTest.class.getClassLoader(), new Class<?>[] {Item.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemStack" -> stack;
                    case "remove" -> {
                        removed.add("removed");
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        PlayerDropItemEvent event = new PlayerDropItemEvent(steve.player, entity);
        protection.onDrop(event);
        assertFalse(event.isCancelled(), "cancelling would hand it back");
        assertEquals(List.of("removed"), removed);
    }
}
