package com.rs.lib.gui;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

import org.bukkit.inventory.ItemStack;

import com.rs.lib.Ph;

/** What a {@link GuiSession}'s binds hold, and the cell arithmetic of a paged bind. */
final class SessionBinds {

    private SessionBinds() {
    }

    /** A template bound to one cell. */
    record Manual(GuiTemplate template, ItemStack stack, Ph[] phs) {
    }

    /** The paged bind: its data, cells (in fill order) and mapper. */
    record Paged<T>(GuiTemplate template, Pagination<T> data, int[] slots, int[] positions,
                    BiConsumer<T, PhCollector> mapper) {
    }

    /** A bound region: one entry per cell. */
    record Region<T>(List<T> data, BiConsumer<T, GuiEntry> filler) {
    }

    /** The cells of {@code slots} inside a menu of {@code size} cells, each once. */
    static int[] cells(int[] slots, int size) {
        if (slots == null) {
            return new int[0];
        }
        boolean[] seen = new boolean[size];
        int[] out = new int[slots.length];
        int n = 0;
        for (int slot : slots) {
            if (slot >= 0 && slot < size && !seen[slot]) {
                seen[slot] = true;
                out[n++] = slot;
            }
        }
        return Arrays.copyOf(out, n);
    }

    /** For each of the {@code size} cells, its position among {@code cells}, or -1. */
    static int[] positions(int[] cells, int size) {
        int[] index = new int[size];
        Arrays.fill(index, -1);
        for (int i = 0; i < cells.length; i++) {
            index[cells[i]] = i;
        }
        return index;
    }
}
