package com.rs.lib.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * An item a player tried to put into a menu, handed to {@link GuiSession#onOffer}.
 *
 * <p>The event that carried it is already cancelled, so the item is back where it was:
 * RsLib never moves, takes or changes it. {@link #stack()} is a copy with the real amount.
 * Accepting the offer is the plugin's job: it takes what it keeps from the player (for a
 * {@link Kind#SHIFT_CLICK}, slot {@link #playerSlot()} of the player's inventory; for the
 * others, the cursor) and calls {@code player.updateInventory()}, because the client still
 * draws the stack it moved.</p>
 *
 * @param viewer     the player
 * @param kind       how the item was offered
 * @param slot       the menu cell it was offered to, or -1 for a shift-click
 * @param playerSlot the slot of the player's inventory the stack is in (shift-click), or -1
 * @param stack      a copy of the offered stack, real amount
 * @param click      the click ({@code RIGHT} or {@code LEFT} for a drag: one item per cell or spread)
 */
public record ItemOffer(Player viewer, Kind kind, int slot, int playerSlot, ItemStack stack, ClickType click) {

    /** How an item was offered. */
    public enum Kind {
        /** Left or right click on an input cell with a stack on the cursor. */
        CURSOR,
        /** A drag of the cursor stack over exactly one input cell. */
        DRAG,
        /** Shift-click on a stack in the player's inventory (needs {@code player-inventory: open}). */
        SHIFT_CLICK
    }
}
