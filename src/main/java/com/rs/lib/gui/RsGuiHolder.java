package com.rs.lib.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/**
 * The holder of every menu inventory RsLib opens, one per session. It is how a menu is
 * recognised ({@code inventory.getHolder(false) instanceof RsGuiHolder}), never by title, and
 * it stays the same when the session's inventory is created again (a title change).
 *
 * <p>A plugin that writes into {@link #getInventory()} directly gets what it wrote replaced
 * on the session's next render, as with any menu cell RsLib owns.</p>
 */
public final class RsGuiHolder implements InventoryHolder {

    private final Plugin owner;
    private final GuiSession session;

    RsGuiHolder(Plugin owner, GuiSession session) {
        this.owner = owner;
        this.session = session;
    }

    /** The plugin whose menu this is. */
    public Plugin owner() {
        return owner;
    }

    public String guiId() {
        return session.guiId();
    }

    public GuiSession session() {
        return session;
    }

    @Override
    public Inventory getInventory() {
        return session.inventoryForPlugin();
    }
}
