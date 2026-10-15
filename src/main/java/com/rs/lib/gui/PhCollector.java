package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;

/**
 * What a {@code bindPaged} mapper fills for one entry of the page: the placeholders of the
 * template and, optionally, a stack of the plugin's own that gives the cell its look (the
 * template then only adds its name and lore lines on top and keeps its behaviour).
 */
public final class PhCollector {

    private static final Ph[] NONE = new Ph[0];

    private final List<Ph> phs = new ArrayList<>(8);
    private ItemStack stack;

    PhCollector() {
    }

    /** Adds the placeholder {@code {key}} / {@code %key%}. */
    public PhCollector add(String key, Object value) {
        phs.add(Ph.of(key, value));
        return this;
    }

    /** Adds a ready placeholder, such as {@code Ph.literal(...)} for text a player wrote. */
    public PhCollector add(Ph ph) {
        if (ph != null) {
            phs.add(ph);
        }
        return this;
    }

    /** Uses {@code stack} (null clears it) as the cell's look; it is copied, never changed. */
    public PhCollector stack(ItemStack stack) {
        this.stack = stack;
        return this;
    }

    /** The placeholders added so far. */
    public Ph[] toArray() {
        return phs.isEmpty() ? NONE : phs.toArray(NONE);
    }

    ItemStack stackOrNull() {
        return stack;
    }
}
