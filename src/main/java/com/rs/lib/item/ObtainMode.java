package com.rs.lib.item;

/**
 * How a registered item may reach a player's equipment ({@code obtain-via}).
 *
 * <p>{@link #COMMAND_ONLY} implies {@code no-manual-equip}: the item can only be worn through
 * {@link ItemRegistry#apply}. It does not stop crafting; mobs never pick up any registered
 * item, whatever the mode.</p>
 */
public enum ObtainMode {
    UNRESTRICTED,
    COMMAND_ONLY
}
