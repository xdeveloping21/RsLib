package com.rs.lib.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;

/**
 * An entry of {@code templates:} in a menu file: a look and a behaviour the plugin places at
 * run time, with {@link GuiSession#bind(int, GuiTemplate, Ph...)}, {@code bindPaged},
 * {@code bindEach} or, when the template declares {@code slots:}/{@code key:},
 * {@link GuiSession#bind(String, Ph...)}. It has every key of a menu item.
 */
public final class GuiTemplate {

    private final GuiItemDef item;

    GuiTemplate(GuiItemDef item) {
        this.item = item;
    }

    public String id() {
        return item.id();
    }

    /** The cells it declares with {@code slots:} or {@code key:} (empty when none). */
    public int[] slots() {
        return item.slots();
    }

    /** True when it declares where it goes. */
    public boolean hasSlots() {
        return item.hasSlots();
    }

    /** Renders its look for {@code viewer} (may be null) with {@code phs}, without the menu stamp. */
    public ItemStack render(Player viewer, Ph... phs) {
        return item.render(viewer, phs);
    }

    /** Everything it declares. */
    public GuiItemDef item() {
        return item;
    }

    @Override
    public String toString() {
        return "GuiTemplate[" + item.guiId() + " " + item.id() + "]";
    }
}
