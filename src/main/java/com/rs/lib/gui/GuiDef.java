package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.event.inventory.InventoryType;

import com.rs.lib.action.ActionList;
import com.rs.lib.item.ItemAppearance;

/**
 * One menu file, parsed once ({@code guis/<id>.yml}) and shared by every session that shows
 * it. Immutable; a reload builds a new one.
 *
 * <p>The cell tables are worked out here, at load: for each cell, which declared items may
 * paint it (in declaration order), whether it receives offered items, which items are
 * navigation arrows. So a render or a click never searches the file.</p>
 */
public final class GuiDef {

    private final String id;
    private final String file;
    private final String title;
    private final int rows;
    private final String inventoryTypeName;
    private volatile InventoryType inventoryType;
    private final int size;
    private final String openSound;
    private final String closeSound;
    private final ActionList closeActions;
    private final int updateInterval;
    private final boolean pagination;
    private final boolean strictClicks;
    private final PlayerInventoryPolicy playerInventory;
    private final Layout layout;
    private final int[] pagedSlots;
    private final Map<String, int[]> regions;
    private final String[] regionOrder;
    private final List<GuiItemDef> items;
    private final Map<String, GuiTemplate> templates;
    private final ItemAppearance.TextResolver resolver;
    private final int[][] candidates;
    private final boolean[] declaredInput;
    private final int[] pagedIndex;
    private final int[][] regionIndex;

    GuiDef(String id, String file, String title, int rows, String inventoryTypeName, int size, String openSound,
           String closeSound, ActionList closeActions, int updateInterval, boolean pagination, boolean strictClicks,
           PlayerInventoryPolicy playerInventory, Layout layout, int[] pagedSlots, Map<String, int[]> regions,
           List<GuiItemDef> items, Map<String, GuiTemplate> templates, ItemAppearance.TextResolver resolver) {
        this.id = id;
        this.file = file;
        this.title = title;
        this.rows = rows;
        this.inventoryTypeName = inventoryTypeName;
        this.size = size;
        this.openSound = openSound;
        this.closeSound = closeSound;
        this.closeActions = closeActions;
        this.updateInterval = updateInterval;
        this.pagination = pagination;
        this.strictClicks = strictClicks;
        this.playerInventory = playerInventory;
        this.layout = layout;
        this.pagedSlots = pagedSlots;
        this.regions = Collections.unmodifiableMap(new LinkedHashMap<>(regions));
        this.regionOrder = regions.keySet().toArray(String[]::new);
        this.items = List.copyOf(items);
        this.templates = Collections.unmodifiableMap(new LinkedHashMap<>(templates));
        this.resolver = resolver;
        this.candidates = new int[size][];
        this.declaredInput = new boolean[size];
        List<List<Integer>> perSlot = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            perSlot.add(new ArrayList<>(2));
        }
        for (int i = 0; i < this.items.size(); i++) {
            GuiItemDef item = this.items.get(i);
            item.guiId(id);
            for (int slot : item.slots()) {
                if (slot < size && !perSlot.get(slot).contains(i)) {
                    perSlot.get(slot).add(i);
                    declaredInput[slot] |= item.input();
                }
            }
        }
        for (int slot = 0; slot < size; slot++) {
            candidates[slot] = perSlot.get(slot).stream().mapToInt(Integer::intValue).toArray();
        }
        for (GuiTemplate template : this.templates.values()) {
            template.item().guiId(id);
        }
        this.pagedIndex = indexOf(pagedSlots, size);
        this.regionIndex = new int[regionOrder.length][];
        for (int r = 0; r < regionOrder.length; r++) {
            regionIndex[r] = indexOf(this.regions.get(regionOrder[r]), size);
        }
    }

    /** For each cell, its position in {@code cells}, or -1. */
    private static int[] indexOf(int[] cells, int size) {
        int[] index = new int[size];
        java.util.Arrays.fill(index, -1);
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] >= 0 && cells[i] < size && index[cells[i]] < 0) {
                index[cells[i]] = i;
            }
        }
        return index;
    }

    // ------------------------------------------------------------------ public reading

    /** The menu id: the file name without {@code .yml}, {@code <namespace>:<name>} for extra folders. */
    public String id() {
        return id;
    }

    /** The file it was read from, relative to the plugin folder. */
    public String file() {
        return file;
    }

    /** {@code title} as written (placeholders unresolved). */
    public String title() {
        return title;
    }

    /** Chest rows (from the layout when there is one). */
    public int rows() {
        return rows;
    }

    /** {@code inventory-type}, or null for a chest. */
    public InventoryType inventoryType() {
        if (inventoryTypeName == null) {
            return null;
        }
        InventoryType type = inventoryType;
        if (type == null) {
            type = InventoryType.valueOf(inventoryTypeName);
            inventoryType = type;
        }
        return type;
    }

    /** The name of {@code inventory-type} ({@code DROPPER}), or null for a chest. */
    public String inventoryTypeName() {
        return inventoryTypeName;
    }

    /** How many cells the menu inventory has. */
    public int size() {
        return size;
    }

    public String openSound() {
        return openSound;
    }

    public String closeSound() {
        return closeSound;
    }

    /** {@code close-actions}, as written. */
    public List<String> closeActions() {
        return closeActions.lines();
    }

    /** {@code update-interval} in ticks; 0 when the menu is not refreshed on its own. */
    public int updateInterval() {
        return updateInterval;
    }

    /** {@code pagination: true}: page actions and {@code bindPaged} work. */
    public boolean pagination() {
        return pagination;
    }

    public boolean strictClicks() {
        return strictClicks;
    }

    public PlayerInventoryPolicy playerInventory() {
        return playerInventory;
    }

    /** The {@code layout} rows, or an empty list. */
    public List<String> layout() {
        return layout == null ? List.of() : layout.lines();
    }

    /** The cells of {@code paged-key}, in fill order ({@code paged-flow}, else row by row). */
    public int[] pagedSlots() {
        return pagedSlots.clone();
    }

    /** The cells of region {@code regionId} in fill order; empty when it has none or does not exist. */
    public int[] regionSlots(String regionId) {
        int[] cells = regionId == null ? null : regions.get(regionId);
        return cells == null ? new int[0] : cells.clone();
    }

    /** The declared region ids, in file order. */
    public List<String> regionIds() {
        return List.of(regionOrder);
    }

    /** The {@code items:} entries in file order. */
    public List<GuiItemDef> items() {
        return items;
    }

    /** The template {@code templateId}, or null. */
    public GuiTemplate template(String templateId) {
        return templateId == null ? null : templates.get(templateId);
    }

    /** Every template by id, in file order. */
    public Map<String, GuiTemplate> templates() {
        return templates;
    }

    // ------------------------------------------------------------------ package

    ActionList closeActionList() {
        return closeActions;
    }

    ItemAppearance.TextResolver resolver() {
        return resolver;
    }

    /** Indices into {@link #items()} that may paint {@code slot}, in declaration order. */
    int[] candidates(int slot) {
        return candidates[slot];
    }

    /** True when a declared item with {@code input: true} covers {@code slot}. */
    boolean declaredInput(int slot) {
        return slot >= 0 && slot < size && declaredInput[slot];
    }

    /** The position of {@code slot} among the paged cells, or -1. */
    int pagedPosition(int slot) {
        return pagedIndex[slot];
    }

    int regionCount() {
        return regionOrder.length;
    }

    String regionId(int index) {
        return regionOrder[index];
    }

    /** The position of {@code slot} in region number {@code index}, or -1. */
    int regionPosition(int index, int slot) {
        return regionIndex[index][slot];
    }

    boolean hasRegion(String regionId) {
        return regions.containsKey(regionId);
    }

    int regionNumber(String regionId) {
        for (int i = 0; i < regionOrder.length; i++) {
            if (regionOrder[i].equals(regionId)) {
                return i;
            }
        }
        return -1;
    }
}
