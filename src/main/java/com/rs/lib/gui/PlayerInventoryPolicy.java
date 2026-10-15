package com.rs.lib.gui;

import java.util.Locale;

/**
 * What a player may do with their own inventory while a menu is open ({@code player-inventory}
 * in the menu file).
 */
public enum PlayerInventoryPolicy {

    /** Every click and drag in the player's inventory is cancelled (the default). */
    LOCKED,

    /**
     * The player uses their inventory as usual; a shift-click on a stack there becomes an
     * {@link ItemOffer} instead of moving it, and cells marked {@code input: true} receive
     * offers from the cursor and from drags.
     */
    OPEN;

    /** {@code locked}/{@code open} in any case, {@code -} read as {@code _}; null when unknown. */
    static PlayerInventoryPolicy parse(String text) {
        if (text == null) {
            return null;
        }
        String name = text.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        for (PlayerInventoryPolicy policy : values()) {
            if (policy.name().equals(name)) {
                return policy;
            }
        }
        return null;
    }
}
