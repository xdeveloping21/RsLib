package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import com.rs.lib.gui.OfferRouting.DragOutcome;
import com.rs.lib.gui.OfferRouting.Outcome;
import com.rs.lib.gui.OfferRouting.Zone;

/** The parts of the menu module that need nothing at all: decision table, flow arrows, pages, masks. */
class GuiPureTest {

    private final List<String> warnings = new ArrayList<>();

    // ------------------------------------------------------------------ offer routing

    /** Trampa 4: COLLECT_TO_CURSOR is cancelled in every zone and policy, even on an input cell. */
    @Test
    void collectToCursorAlwaysCancels() {
        for (Zone zone : Zone.values()) {
            for (PlayerInventoryPolicy policy : PlayerInventoryPolicy.values()) {
                assertEquals(Outcome.CANCEL, OfferRouting.click(InventoryAction.COLLECT_TO_CURSOR, zone,
                        ClickType.DOUBLE_CLICK, true, false, false, policy));
            }
        }
    }

    @Test
    void clickTable() {
        PlayerInventoryPolicy open = PlayerInventoryPolicy.OPEN;
        PlayerInventoryPolicy locked = PlayerInventoryPolicy.LOCKED;
        InventoryAction place = InventoryAction.PLACE_ALL;
        // Top: input cell + cursor + LEFT/RIGHT is an offer, the rest a button.
        assertEquals(Outcome.OFFER_CURSOR, OfferRouting.click(place, Zone.TOP, ClickType.LEFT, true, false, true, open));
        assertEquals(Outcome.OFFER_CURSOR, OfferRouting.click(place, Zone.TOP, ClickType.RIGHT, true, false, true, open));
        assertEquals(Outcome.BUTTON, OfferRouting.click(place, Zone.TOP, ClickType.SHIFT_LEFT, true, false, true, open));
        assertEquals(Outcome.BUTTON, OfferRouting.click(InventoryAction.PICKUP_ALL, Zone.TOP, ClickType.LEFT, true, true,
                false, open), "an input cell with an empty cursor is still a button");
        assertEquals(Outcome.BUTTON, OfferRouting.click(place, Zone.TOP, ClickType.LEFT, false, false, true, open));
        assertEquals(Outcome.BUTTON, OfferRouting.click(InventoryAction.HOTBAR_SWAP, Zone.TOP, ClickType.NUMBER_KEY,
                false, true, false, locked));
        // Outside and a locked player inventory cancel.
        assertEquals(Outcome.CANCEL, OfferRouting.click(InventoryAction.DROP_ALL_CURSOR, Zone.OUTSIDE, ClickType.LEFT,
                false, false, true, open));
        assertEquals(Outcome.CANCEL, OfferRouting.click(InventoryAction.PICKUP_ALL, Zone.BOTTOM, ClickType.LEFT, false,
                true, false, locked));
        assertEquals(Outcome.CANCEL, OfferRouting.click(InventoryAction.MOVE_TO_OTHER_INVENTORY, Zone.BOTTOM,
                ClickType.SHIFT_LEFT, false, true, false, locked));
        // Open: shift-click on a stack is an offer, anything else passes.
        assertEquals(Outcome.OFFER_SHIFT, OfferRouting.click(InventoryAction.MOVE_TO_OTHER_INVENTORY, Zone.BOTTOM,
                ClickType.SHIFT_RIGHT, false, true, false, open));
        assertEquals(Outcome.PASS, OfferRouting.click(InventoryAction.NOTHING, Zone.BOTTOM, ClickType.SHIFT_LEFT, false,
                true, true, open));
        assertEquals(Outcome.PASS, OfferRouting.click(InventoryAction.PICKUP_ALL, Zone.BOTTOM, ClickType.LEFT, false,
                true, false, open));
    }

    @Test
    void zones() {
        assertEquals(Zone.OUTSIDE, OfferRouting.zone(-999, 27));
        assertEquals(Zone.OUTSIDE, OfferRouting.zone(-1, 27));
        assertEquals(Zone.TOP, OfferRouting.zone(26, 27));
        assertEquals(Zone.BOTTOM, OfferRouting.zone(27, 27));
    }

    /** Trampa 18: a drag over several cells is never an offer, even when they are all input cells. */
    @Test
    void dragTable() {
        assertEquals(DragOutcome.OFFER, OfferRouting.drag(1, true, false, PlayerInventoryPolicy.OPEN));
        assertEquals(DragOutcome.CANCEL, OfferRouting.drag(2, true, false, PlayerInventoryPolicy.OPEN));
        assertEquals(DragOutcome.CANCEL, OfferRouting.drag(1, false, false, PlayerInventoryPolicy.OPEN));
        assertEquals(DragOutcome.CANCEL, OfferRouting.drag(1, true, true, PlayerInventoryPolicy.OPEN));
        assertEquals(DragOutcome.PASS, OfferRouting.drag(0, false, false, PlayerInventoryPolicy.OPEN));
        assertEquals(DragOutcome.CANCEL, OfferRouting.drag(0, false, false, PlayerInventoryPolicy.LOCKED));
    }

    @Test
    void playerInventoryPolicyNames() {
        assertEquals(PlayerInventoryPolicy.OPEN, PlayerInventoryPolicy.parse("Open"));
        assertEquals(PlayerInventoryPolicy.LOCKED, PlayerInventoryPolicy.parse(" locked "));
        assertNull(PlayerInventoryPolicy.parse("closed"));
    }

    // ------------------------------------------------------------------ flow arrows

    private static final int[] SQUARE = {0, 1, 2, 9, 10, 11, 18, 19, 20};

    private int[] flow(String... rows) {
        return FlowOrder.order(SQUARE, Layout.of(rows), "paged-flow", warnings::add);
    }

    @Test
    void flowSnake() {
        assertArrayEquals(new int[] {0, 1, 2, 11, 10, 9, 18, 19, 20}, flow(">>v", "v<<", ">> "));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void flowRowsRightToLeft() {
        assertArrayEquals(new int[] {2, 1, 0, 11, 10, 9, 20, 19, 18}, flow("v<<", "v<<", " <<"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void flowColumnMajor() {
        assertArrayEquals(new int[] {0, 9, 18, 1, 10, 19, 2, 11, 20}, flow("vvv", "vvv", ">> "));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void flowSkipsCellsThatAreNotOrdered() {
        int[] cells = {0, 2, 4};
        assertArrayEquals(new int[] {4, 2, 0}, FlowOrder.order(cells, Layout.of("  < <"), "flow", warnings::add));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** Trampa 13: a broken flow is reported and the default order kept; the menu still loads. */
    @Test
    void brokenFlowsKeepTheDefaultOrder() {
        assertNull(flow(">>v", "v<<", ">>^"), "a loop");
        assertEquals(1, warnings.size(), warnings.toString());
        warnings.clear();
        assertNull(flow(">>v", "x<<", ">> "), "a character that is not an arrow");
        assertEquals(1, warnings.size(), warnings.toString());
        warnings.clear();
        assertNull(flow(">> ", ">> ", ">> "), "three complete paths");
        assertEquals(1, warnings.size(), warnings.toString());
    }

    // ------------------------------------------------------------------ pages and masks

    @Test
    void pagination() {
        Pagination<Integer> pages = Pagination.of(List.of(1, 2, 3, 4, 5), 2);
        assertEquals(3, pages.totalPages());
        assertEquals(List.of(5), pages.page(3));
        assertEquals(List.of(1, 2), pages.page(0), "clamped to the first page");
        assertEquals(List.of(5), pages.page(9), "clamped to the last page");
        assertEquals(1, Pagination.of(List.of(), 4).totalPages(), "an empty list still has one page");
        assertThrows(IllegalArgumentException.class, () -> Pagination.of(List.of(1), 0));
    }

    @Test
    void masks() {
        assertArrayEquals(new int[] {1, 10, 11}, GuiMask.slots('x', " x", " xx"));
        assertArrayEquals(new int[0], GuiMask.slots('y', "xxx"));
    }
}
