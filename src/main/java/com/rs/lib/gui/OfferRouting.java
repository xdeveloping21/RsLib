package com.rs.lib.gui;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

/**
 * What a click or a drag on an open menu does: the pure decision table, with no server.
 *
 * <p>Clicks, by zone (TOP: a menu cell; BOTTOM: the player's inventory; OUTSIDE: outside the
 * window):</p>
 * <ol>
 *     <li>{@code COLLECT_TO_CURSOR} (the double click that gathers stacks) is always cancelled,
 *     in every zone and policy: it could pull stacks out of the menu from below.</li>
 *     <li>TOP: an input cell clicked LEFT or RIGHT with a stack on the cursor is cancelled and
 *     becomes a CURSOR offer; every other TOP click is cancelled and runs the cell as a button
 *     (so an input cell with an empty cursor still works as a button).</li>
 *     <li>OUTSIDE, or a LOCKED player inventory: cancelled, nothing runs.</li>
 *     <li>BOTTOM with OPEN: a shift-click on a stack is cancelled and becomes a SHIFT_CLICK
 *     offer; anything else goes through untouched.</li>
 * </ol>
 * <p>Drags: touching no menu cell goes through with OPEN and is cancelled with LOCKED; touching
 * exactly one cell that is an input cell with a non-empty stack is cancelled and becomes a DRAG
 * offer; anything else (two or more cells, even all inputs) is cancelled.</p>
 */
final class OfferRouting {

    private OfferRouting() {
    }

    /** Where a click landed. */
    enum Zone { TOP, BOTTOM, OUTSIDE }

    /** What to do with a click. Every outcome but {@link #PASS} cancels the event first. */
    enum Outcome {
        /** Cancel, nothing else. */
        CANCEL,
        /** Cancel and run the cell as a button. */
        BUTTON,
        /** Cancel and hand the cursor stack to the offer handler. */
        OFFER_CURSOR,
        /** Cancel and hand the clicked stack of the player's inventory to the offer handler. */
        OFFER_SHIFT,
        /** Leave the event alone. */
        PASS
    }

    /** What to do with a drag. Every outcome but {@link #PASS} cancels the event first. */
    enum DragOutcome { CANCEL, OFFER, PASS }

    /** The zone of raw slot {@code rawSlot} in a view whose top inventory has {@code topSize} slots. */
    static Zone zone(int rawSlot, int topSize) {
        if (rawSlot < 0) {
            return Zone.OUTSIDE;
        }
        return rawSlot < topSize ? Zone.TOP : Zone.BOTTOM;
    }

    static Outcome click(InventoryAction action, Zone zone, ClickType click, boolean inputCell, boolean cursorEmpty,
                         boolean clickedEmpty, PlayerInventoryPolicy policy) {
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            return Outcome.CANCEL;
        }
        if (zone == Zone.TOP) {
            if (inputCell && !cursorEmpty && (click == ClickType.LEFT || click == ClickType.RIGHT)) {
                return Outcome.OFFER_CURSOR;
            }
            return Outcome.BUTTON;
        }
        if (zone == Zone.OUTSIDE || policy != PlayerInventoryPolicy.OPEN) {
            return Outcome.CANCEL;
        }
        if ((click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) && !clickedEmpty) {
            return Outcome.OFFER_SHIFT;
        }
        return Outcome.PASS;
    }

    /**
     * @param topCells    how many menu cells the drag touches
     * @param singleInput when it touches exactly one, whether that cell is an input cell
     * @param stackEmpty  whether the dragged stack is empty
     */
    static DragOutcome drag(int topCells, boolean singleInput, boolean stackEmpty, PlayerInventoryPolicy policy) {
        if (topCells == 0) {
            return policy == PlayerInventoryPolicy.OPEN ? DragOutcome.PASS : DragOutcome.CANCEL;
        }
        if (topCells == 1 && singleInput && !stackEmpty) {
            return DragOutcome.OFFER;
        }
        return DragOutcome.CANCEL;
    }
}
