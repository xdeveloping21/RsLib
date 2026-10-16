package com.rs.lib.item;

/**
 * How a registered item may reach a player ({@code obtain-via}).
 *
 * <p>{@link #COMMAND_ONLY} keeps the item to commands and the API: its {@code recipe} is not
 * registered (one warning) and a crafting grid never shows it as a result. Equipping follows
 * the item's own properties ({@code no-manual-equip}, {@code locked}, {@code equipment-slot}).
 * Mobs never pick up any registered item, whatever the mode.</p>
 */
public enum ObtainMode {
    UNRESTRICTED,
    COMMAND_ONLY
}
