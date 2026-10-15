package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.gui.GuiKit.TestPlayer;
import com.rs.lib.gui.GuiKit.TestStack;

/** Real click and drag events through RsLib's menu listener (the inventory view is a test proxy). */
class GuiListenerTest {

    private final GuiKit kit = new GuiKit();
    private final GuiListener listener = new GuiListener();
    private final List<Boolean> cancelledWhenRun = new ArrayList<>();
    private final List<ItemOffer> offers = new ArrayList<>();
    private TestPlayer steve;

    @BeforeEach
    void setUp() {
        steve = kit.player("Steve");
    }

    private GuiSession open(String policy) {
        Gui gui = kit.gui("shop", """
                player-inventory: %s
                title: 'Shop {n}'
                items:
                  button: {material: STONE, slots: [0], click-actions: ['[check]']}
                  in: {material: HOPPER, slots: [1, 2], input: true}
                """.formatted(policy));
        gui.open(steve.player);
        GuiSession session = gui.session(steve.player);
        session.onOffer(offers::add);
        return session;
    }

    private InventoryClickEvent click(int raw, ClickType click, InventoryAction action, ItemStack cursor) {
        Views.View view = new Views.View(kit.inventoryOf(steve), steve.inventory, steve.player);
        view.cursor = cursor;
        InventoryClickEvent event = new InventoryClickEvent(view.view, raw < 0 ? SlotType.OUTSIDE : SlotType.CONTAINER,
                raw, click, action);
        listener.onClick(event);
        return event;
    }

    private void registerCheck(InventoryClickEvent[] holder) {
        kit.engine.engine().register("check", (player, arg, context) -> cancelledWhenRun.add(holder[0].isCancelled()));
    }

    /** Trampa 4: the event is cancelled before the button's actions run. */
    @Test
    void buttonsRunAfterTheEventIsCancelled() {
        open("locked");
        InventoryClickEvent[] current = new InventoryClickEvent[1];
        registerCheck(current);
        Views.View view = new Views.View(kit.inventoryOf(steve), steve.inventory, steve.player);
        current[0] = new InventoryClickEvent(view.view, SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(current[0]);
        assertTrue(current[0].isCancelled());
        assertEquals(List.of(true), cancelledWhenRun);
    }

    /** Trampa 4: COLLECT_TO_CURSOR is cancelled even from the player's open inventory. */
    @Test
    void collectToCursorIsCancelledBelowToo() {
        open("open");
        assertTrue(click(30, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, new TestStack("dirt"))
                .isCancelled());
        assertFalse(click(30, ClickType.LEFT, InventoryAction.PICKUP_ALL, null).isCancelled(),
                "an open player inventory works as usual");
    }

    @Test
    void lockedPlayerInventoryAndOutside() {
        open("locked");
        assertTrue(click(30, ClickType.LEFT, InventoryAction.PICKUP_ALL, null).isCancelled());
        assertTrue(click(-999, ClickType.LEFT, InventoryAction.DROP_ALL_CURSOR, new TestStack("dirt")).isCancelled());
        assertTrue(offers.isEmpty());
    }

    @Test
    void offers() {
        open("open");
        steve.inventory.setItem(3, new TestStack("gold"));
        InventoryClickEvent cursor = click(1, ClickType.RIGHT, InventoryAction.PLACE_ONE, new TestStack("iron"));
        InventoryClickEvent shift = click(kit.inventoryOf(steve).getSize() + 3, ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY, null);
        assertTrue(cursor.isCancelled());
        assertTrue(shift.isCancelled());
        assertEquals(2, offers.size());
        ItemOffer first = offers.get(0);
        assertEquals(ItemOffer.Kind.CURSOR, first.kind());
        assertEquals(1, first.slot());
        assertEquals(-1, first.playerSlot());
        assertEquals("iron", ((TestStack) first.stack()).label);
        assertEquals(ClickType.RIGHT, first.click());
        ItemOffer second = offers.get(1);
        assertEquals(ItemOffer.Kind.SHIFT_CLICK, second.kind());
        assertEquals(-1, second.slot());
        assertEquals(3, second.playerSlot());
        assertEquals("gold", ((TestStack) second.stack()).label);
        assertNotSame(steve.inventory.getItem(3), second.stack(), "a copy: RsLib never takes the item");
        assertNotNull(steve.inventory.getItem(3));
    }

    /** Trampa 18: a drag over one input cell is an offer; over two it is only cancelled. */
    @Test
    void drags() {
        open("open");
        Views.View view = new Views.View(kit.inventoryOf(steve), steve.inventory, steve.player);
        InventoryDragEvent one = new InventoryDragEvent(view.view, null, new TestStack("iron"), true,
                Map.of(1, new TestStack("iron")));
        listener.onDrag(one);
        assertTrue(one.isCancelled());
        assertEquals(1, offers.size());
        assertEquals(ItemOffer.Kind.DRAG, offers.get(0).kind());
        assertEquals(ClickType.RIGHT, offers.get(0).click(), "one item per cell");
        InventoryDragEvent two = new InventoryDragEvent(view.view, null, new TestStack("iron"), false,
                Map.of(1, new TestStack("iron"), 2, new TestStack("iron")));
        listener.onDrag(two);
        assertTrue(two.isCancelled());
        assertEquals(1, offers.size());
        InventoryDragEvent below = new InventoryDragEvent(view.view, null, new TestStack("iron"), false,
                Map.of(40, new TestStack("iron")));
        listener.onDrag(below);
        assertFalse(below.isCancelled(), "a drag only in an open player inventory goes through");
    }

    /** Trampa 1: a menu is its holder; an inventory with the same title and another holder is left alone. */
    @Test
    void menusAreRecognisedByHolderOnly() {
        open("locked");
        Inventory lookalike = kit.inventory(null, 27);
        Views.View view = new Views.View(lookalike, steve.inventory, steve.player);
        InventoryClickEvent event = new InventoryClickEvent(view.view, SlotType.CONTAINER, 0, ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        listener.onClick(event);
        assertFalse(event.isCancelled());
    }

    /** An inventory the session already replaced (new title) takes no click and its close is ignored. */
    @Test
    void replacedInventories() {
        GuiSession session = open("locked");
        Inventory old = kit.inventoryOf(steve);
        session.titlePlaceholders(Ph.of("n", "2"));
        InventoryClickEvent[] current = new InventoryClickEvent[1];
        registerCheck(current);
        Views.View view = new Views.View(old, steve.inventory, steve.player);
        current[0] = new InventoryClickEvent(view.view, SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        listener.onClick(current[0]);
        assertTrue(current[0].isCancelled());
        assertTrue(cancelledWhenRun.isEmpty());
        listener.onClose(new InventoryCloseEvent(view.view));
        assertFalse(session.closed());
        listener.onClose(new InventoryCloseEvent(new Views.View(kit.inventoryOf(steve), steve.inventory,
                steve.player).view));
        assertTrue(session.closed());
    }

    @Test
    void anotherPlayersClickIsCancelled() {
        open("open");
        TestPlayer alex = kit.player("Alex");
        Views.View view = new Views.View(kit.inventoryOf(steve), alex.inventory, alex.player);
        InventoryClickEvent event = new InventoryClickEvent(view.view, SlotType.CONTAINER, 0, ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        listener.onClick(event);
        assertTrue(event.isCancelled());
    }

    /** Trampa 5: clicks another plugin cancelled first are skipped on purpose, at HIGH. */
    @Test
    void clickPriorities() throws NoSuchMethodException {
        EventHandler click = GuiListener.class.getMethod("onClick", InventoryClickEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler drag = GuiListener.class.getMethod("onDrag", InventoryDragEvent.class)
                .getAnnotation(EventHandler.class);
        assertTrue(click.ignoreCancelled());
        assertEquals(EventPriority.HIGH, click.priority());
        assertTrue(drag.ignoreCancelled());
        EventHandler protection = GuiProtection.class.getMethod("onClick", InventoryClickEvent.class)
                .getAnnotation(EventHandler.class);
        assertFalse(protection.ignoreCancelled(), "anti-theft looks at every click");
        assertEquals(EventPriority.HIGHEST, protection.priority());
        assertNull(GuiListener.sessionOf(null));
    }
}
