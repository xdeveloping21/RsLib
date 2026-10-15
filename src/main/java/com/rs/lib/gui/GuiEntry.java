package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;

/**
 * What a {@code bindEach} filler chooses for one cell of a region: the template to show, its
 * placeholders and, optionally, a stack of the plugin's own for the look. A cell whose filler
 * picks no template (or a template whose {@code view-requirements} fail) shows whatever the
 * menu file declares under it.
 */
public final class GuiEntry {

    private static final Ph[] NONE = new Ph[0];

    private final List<Ph> phs = new ArrayList<>(8);
    private String template;
    private ItemStack stack;

    GuiEntry() {
    }

    /** Shows the template {@code templateId} of the menu file in this cell. */
    public GuiEntry template(String templateId) {
        this.template = templateId;
        return this;
    }

    /** Adds the placeholder {@code {key}} / {@code %key%}. */
    public GuiEntry add(String key, Object value) {
        phs.add(Ph.of(key, value));
        return this;
    }

    /** Adds a ready placeholder, such as {@code Ph.literal(...)} for text a player wrote. */
    public GuiEntry add(Ph ph) {
        if (ph != null) {
            phs.add(ph);
        }
        return this;
    }

    /** Uses {@code stack} (null clears it) as the cell's look; it is copied, never changed. */
    public GuiEntry stack(ItemStack stack) {
        this.stack = stack;
        return this;
    }

    String templateId() {
        return template;
    }

    Ph[] phs() {
        return phs.isEmpty() ? NONE : phs.toArray(NONE);
    }

    ItemStack stackOrNull() {
        return stack;
    }
}
