package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;

import com.rs.lib.action.ActionEngine;
import com.rs.lib.action.ActionList;
import com.rs.lib.action.Requirement;
import com.rs.lib.internal.YamlChecks;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.util.SlotParser;

/**
 * Reads one menu file into a {@link GuiDef}. Never throws for a value written wrong: it is
 * reported with the file and the key, and the key keeps its default. Each item and template is
 * read on its own, so one broken entry never hides the others.
 */
final class GuiParser {

    /** Where load-time problems go: {@code path} is the key path inside the file ("" for the top). */
    @FunctionalInterface
    interface Warn {
        void at(String path, String message);
    }

    static final String DEFAULT_TITLE = "Menu";
    static final int DEFAULT_ROWS = 3;
    static final String NAV_PREVIOUS = "[previous-page]";
    static final String NAV_NEXT = "[next-page]";
    /** Why a key, paged-key or region written as a space selects nothing. */
    static final String SPACE = "a space is always an empty cell";

    private static final Set<String> ROOT_LISTS = Set.of("layout", "paged-flow");
    private static final Set<String> ROOT_INTS = Set.of("rows", "update-interval");
    private static final Set<String> ROOT_SECTIONS = Set.of("items", "templates", "regions");
    private static final Set<String> ITEM_INTS = Set.of("update-interval");
    private static final Set<String> ITEM_SECTIONS = Set.of("nav-disabled");

    private final String id;
    private final String file;
    private final ConfigurationSection root;
    private final ItemAppearance.TextResolver resolver;
    private final ActionEngine actions;
    private final Warn warn;
    private final Logger logger;
    private Layout layout;
    private InventoryTypes types = InventoryTypes.SERVER;
    private int typeSize;

    private GuiParser(String id, String file, ConfigurationSection root, ItemAppearance.TextResolver resolver,
                      ActionEngine actions, Warn warn, Logger logger) {
        this.id = id;
        this.file = file;
        this.root = root;
        this.resolver = resolver;
        this.actions = actions;
        this.warn = warn;
        this.logger = logger;
    }

    /**
     * Parses the menu {@code id} read from {@code file}.
     *
     * @param resolver how texts resolve placeholders per viewer (the file's own, then the locals, then PlaceholderAPI)
     * @param actions  the plugin's action engine (action lists and requirements)
     * @param warn     where problems in the YAML go
     * @param logger   where an unexpected failure of one entry is logged
     */
    static GuiDef parse(String id, String file, ConfigurationSection root, ItemAppearance.TextResolver resolver,
                        ActionEngine actions, Warn warn, Logger logger) {
        return parse(id, file, root, resolver, actions, warn, logger, InventoryTypes.SERVER);
    }

    /** {@link #parse(String, String, ConfigurationSection, ItemAppearance.TextResolver, ActionEngine, Warn, Logger)} with the inventory types of {@code types}. */
    static GuiDef parse(String id, String file, ConfigurationSection root, ItemAppearance.TextResolver resolver,
                        ActionEngine actions, Warn warn, Logger logger, InventoryTypes types) {
        GuiParser parser = new GuiParser(id, file, root, resolver, actions, warn, logger);
        parser.types = types;
        return parser.read();
    }

    private Consumer<String> at(String path) {
        return message -> warn.at(path, message);
    }

    private GuiDef read() {
        Consumer<String> top = at("");
        YamlChecks.check(root, top, ROOT_LISTS, ROOT_INTS, ROOT_SECTIONS);
        String title = text(root, "title", DEFAULT_TITLE);
        layout = Layout.parse(root.get("layout"), "layout", top);
        int rows = DEFAULT_ROWS;
        if (root.contains("rows")) {
            Object raw = root.get("rows");
            if (raw instanceof Number n && n.intValue() >= 1 && n.intValue() <= Layout.MAX_ROWS) {
                rows = n.intValue();
            } else if (raw instanceof Number) {
                top.accept("'rows' must be 1 to 6, got " + raw + "; using " + DEFAULT_ROWS);
            }
        }
        if (layout != null) {
            if (root.contains("rows") && rows != layout.rows()) {
                top.accept("'rows' is " + rows + " but the layout has " + layout.rows() + " rows; using "
                        + layout.rows());
            }
            rows = layout.rows();
        }
        String type = inventoryType(top);
        int size = type == null ? rows * Layout.COLUMNS : typeSize;
        String openSound = text(root, "open-sound", "");
        String closeSound = text(root, "close-sound", "");
        ActionList closeActions = actions.compile(lines(root, "close-actions"), file + ", close-actions");
        int updateInterval = ticks(root, "update-interval", top);
        boolean pagination = bool(root, "pagination", false, top);
        boolean strictClicks = bool(root, "strict-clicks", false, top);
        PlayerInventoryPolicy policy = PlayerInventoryPolicy.LOCKED;
        if (root.contains("player-inventory")) {
            String raw = String.valueOf(root.get("player-inventory"));
            PlayerInventoryPolicy parsed = PlayerInventoryPolicy.parse(raw);
            if (parsed == null) {
                top.accept("'player-inventory' must be locked or open, got '" + raw + "'; using locked");
            } else {
                policy = parsed;
            }
        }
        int[] pagedSlots = pagedSlots(pagination, top);
        Map<String, int[]> regions = regions(pagedSlots);
        List<GuiItemDef> items = new ArrayList<>();
        ConfigurationSection itemSection = root.getConfigurationSection("items");
        if (itemSection != null) {
            for (String key : itemSection.getKeys(false)) {
                GuiItemDef item = entry(itemSection, key, "items." + key, false);
                if (item != null) {
                    items.add(item);
                    if (item.input() && policy != PlayerInventoryPolicy.OPEN) {
                        warn.at("items." + key, "'input: true' needs 'player-inventory: open' in this menu;"
                                + " nothing can be offered to it");
                    }
                }
            }
        }
        Map<String, GuiTemplate> templates = new LinkedHashMap<>();
        ConfigurationSection templateSection = root.getConfigurationSection("templates");
        if (templateSection != null) {
            for (String key : templateSection.getKeys(false)) {
                GuiItemDef item = entry(templateSection, key, "templates." + key, true);
                if (item != null) {
                    templates.put(key, new GuiTemplate(item));
                    if (item.input() && policy != PlayerInventoryPolicy.OPEN) {
                        warn.at("templates." + key, "'input: true' needs 'player-inventory: open' in this menu;"
                                + " nothing can be offered to it");
                    }
                }
            }
        }
        return new GuiDef(id, file, title, rows, type, size, openSound, closeSound, closeActions, updateInterval,
                pagination, strictClicks, policy, layout, pagedSlots, regions, items, templates, resolver);
    }

    // ------------------------------------------------------------------ menu keys

    /** The name of {@code inventory-type} (null for a chest); sets {@link #typeSize}. */
    private String inventoryType(Consumer<String> top) {
        if (!root.contains("inventory-type")) {
            return null;
        }
        String raw = String.valueOf(root.get("inventory-type")).trim();
        if (raw.isEmpty() || raw.equalsIgnoreCase("CHEST")) {
            return null;
        }
        String name = raw.replace('-', '_').toUpperCase(Locale.ROOT);
        int found = types.size(name);
        if (found <= 0) {
            top.accept("'inventory-type' " + raw + " is not an inventory type; using a chest");
            return null;
        }
        typeSize = found;
        if (layout != null) {
            top.accept("'inventory-type' " + raw + " with a layout: the layout assumes rows of 9 cells,"
                    + " so cells past the " + found + " of this inventory are not shown");
        }
        return name;
    }

    private int[] pagedSlots(boolean pagination, Consumer<String> top) {
        int[] slots = new int[0];
        if (root.contains("paged-key")) {
            String raw = String.valueOf(root.get("paged-key"));
            if (raw.length() != 1) {
                top.accept("'paged-key' must be one character, got '" + raw + "'; ignored");
            } else if (raw.charAt(0) == GuiMask.EMPTY) {
                top.accept("'paged-key' ' ': " + SPACE + "; nothing is paged");
            } else if (layout == null) {
                top.accept("'paged-key' needs a layout; ignored");
            } else if (!layout.contains(raw.charAt(0))) {
                top.accept("'paged-key' '" + raw + "' is not in the layout; nothing is paged");
            } else {
                slots = layout.slotsOf(raw.charAt(0));
                if (!pagination) {
                    top.accept("'paged-key' is set but 'pagination' is not true; bindPaged does nothing in this menu");
                }
            }
        }
        if (root.contains("paged-flow")) {
            if (slots.length == 0) {
                top.accept("'paged-flow' needs a layout and a 'paged-key' found in it; ignored");
            } else {
                Layout grid = Layout.parse(root.get("paged-flow"), "paged-flow", top);
                int[] ordered = FlowOrder.order(slots, grid, "paged-flow", top);
                if (ordered != null) {
                    slots = ordered;
                }
            }
        }
        return slots;
    }

    private Map<String, int[]> regions(int[] pagedSlots) {
        Map<String, int[]> out = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("regions");
        if (section == null) {
            return out;
        }
        boolean[] paged = new boolean[Layout.MAX_ROWS * Layout.COLUMNS];
        for (int slot : pagedSlots) {
            paged[slot] = true;
        }
        Map<Integer, String> taken = new LinkedHashMap<>();
        for (String regionId : section.getKeys(false)) {
            String path = "regions." + regionId;
            Consumer<String> w = at(path);
            int[] cells = regionCells(section, regionId, w);
            boolean pagedOverlap = false;
            String earlier = null;
            for (int cell : cells) {
                if (cell < paged.length && paged[cell]) {
                    pagedOverlap = true;
                }
                String before = taken.put(cell, regionId);
                if (earlier == null && before != null && !before.equals(regionId)) {
                    earlier = before;
                }
            }
            if (pagedOverlap) {
                w.accept("shares cells with 'paged-key'; a paged bind wins there");
            }
            if (earlier != null) {
                w.accept("shares cells with region '" + earlier + "'; this region, declared later, wins there");
            }
            out.put(regionId, cells);
        }
        return out;
    }

    private int[] regionCells(ConfigurationSection section, String regionId, Consumer<String> w) {
        Object value = section.get(regionId);
        if (!(value instanceof ConfigurationSection region)) {
            String text = value == null ? "" : String.valueOf(value);
            return text.isBlank() && !text.equals(" ") ? new int[0] : keyCells(text, w);
        }
        boolean hasSlots = region.contains("slots");
        boolean hasKey = region.contains("key");
        int[] cells;
        if (hasSlots) {
            if (hasKey) {
                w.accept("has both 'slots' and 'key'; 'slots' wins");
            }
            cells = SlotParser.parse(region.get("slots"), message -> w.accept("slots: " + message));
        } else if (hasKey) {
            Object key = region.get("key");
            String text = key == null ? "" : String.valueOf(key);
            cells = text.isBlank() && !text.equals(" ") ? new int[0] : keyCells(text, w);
        } else {
            if (!region.getKeys(false).isEmpty()) {
                w.accept("needs 'key:' or 'slots:'; the region has no cells");
            }
            return new int[0];
        }
        if (region.contains("flow") && cells.length > 0) {
            Layout grid = Layout.parse(region.get("flow"), "flow", w);
            int[] ordered = FlowOrder.order(cells, grid, "flow", w);
            if (ordered != null) {
                cells = ordered;
            }
        }
        return cells;
    }

    /** The layout cells of a one-character key; a missing letter is silent (that is how a button is removed). */
    private int[] keyCells(String text, Consumer<String> w) {
        if (text.length() != 1) {
            w.accept("key '" + text + "' must be one character; nothing is shown");
            return new int[0];
        }
        if (text.charAt(0) == GuiMask.EMPTY) {
            w.accept("key ' ': " + SPACE + "; the region has no cells");
            return new int[0];
        }
        if (layout == null) {
            w.accept("key '" + text + "' needs a layout; nothing is shown");
            return new int[0];
        }
        return layout.slotsOf(text.charAt(0));
    }

    // ------------------------------------------------------------------ items and templates

    private GuiItemDef entry(ConfigurationSection parent, String key, String path, boolean template) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            warn.at(path, "is not a section (keys below it); skipped");
            return null;
        }
        try {
            return item(section, key, path, template);
        } catch (RuntimeException | LinkageError broken) {
            logger.log(Level.WARNING, "[" + file + "] " + path + " could not be loaded; skipped", broken);
            return null;
        }
    }

    private GuiItemDef item(ConfigurationSection section, String key, String path, boolean template) {
        Consumer<String> w = at(path);
        YamlChecks.check(section, w, Set.of(), ITEM_INTS, ITEM_SECTIONS);
        int[] slots = new int[0];
        boolean located = false;
        boolean hasSlots = section.contains("slots");
        boolean hasKey = section.contains("key");
        if (hasSlots) {
            if (hasKey) {
                w.accept("has both 'slots' and 'key'; 'slots' wins");
            }
            slots = SlotParser.parse(section.get("slots"), message -> w.accept("slots: " + message));
            located = true;
        } else if (hasKey) {
            Object raw = section.get("key");
            String text = raw == null ? "" : String.valueOf(raw);
            if (text.length() != 1) {
                w.accept("key '" + text + "' must be one character; it is not shown");
            } else if (text.charAt(0) == GuiMask.EMPTY) {
                w.accept("key ' ': " + SPACE + "; it is not shown");
            } else if (layout == null) {
                w.accept("key '" + text + "' needs a layout; it is not shown");
            } else {
                slots = layout.slotsOf(text.charAt(0));
                located = true;
            }
        } else if (!template) {
            w.accept("has no 'slots' or 'key'; it is not shown");
        }
        String source = file + " -> " + path;
        ItemAppearance appearance = ItemAppearance.parse(section, source, resolver, w);
        ItemAppearance navDisabled = null;
        ConfigurationSection disabled = section.getConfigurationSection("nav-disabled");
        if (disabled != null) {
            navDisabled = ItemAppearance.parse(disabled, source + ".nav-disabled", resolver,
                    at(path + ".nav-disabled"));
        }
        int updateInterval = ticks(section, "update-interval", w);
        boolean input = bool(section, "input", false, w);
        Requirement view = actions.requirements(section, "view-requirements", file);
        GuiItemDef.Clicks generic = clicks(section, path, "click-actions", "click-requirements", "deny-actions");
        GuiItemDef.Prefix[] prefixes = GuiItemDef.Prefix.values();
        GuiItemDef.Clicks[] byPrefix = new GuiItemDef.Clicks[prefixes.length];
        boolean previous = mentions(generic, NAV_PREVIOUS);
        boolean next = mentions(generic, NAV_NEXT);
        for (GuiItemDef.Prefix prefix : prefixes) {
            String actionsKey = prefix.key() + "-actions";
            String requirementsKey = prefix.key() + "-requirements";
            String denyKey = prefix.key() + "-deny-actions";
            if (section.contains(actionsKey) || section.contains(requirementsKey) || section.contains(denyKey)) {
                GuiItemDef.Clicks clicks = clicks(section, path, actionsKey, requirementsKey, denyKey);
                byPrefix[prefix.ordinal()] = clicks;
                previous |= mentions(clicks, NAV_PREVIOUS);
                next |= mentions(clicks, NAV_NEXT);
            }
        }
        return new GuiItemDef(key, path, template, slots, located, appearance, navDisabled, updateInterval, input,
                view, generic, byPrefix, !template && previous, !template && next);
    }

    private GuiItemDef.Clicks clicks(ConfigurationSection section, String path, String actionsKey,
                                     String requirementsKey, String denyKey) {
        ActionList run = actions.compile(lines(section, actionsKey), file + ", " + path + "." + actionsKey);
        Requirement requirement = actions.requirements(section, requirementsKey, file);
        ActionList deny = actions.compile(lines(section, denyKey), file + ", " + path + "." + denyKey);
        return new GuiItemDef.Clicks(run, requirement, deny);
    }

    /** Whether any line of {@code clicks} holds {@code tag}, ignoring case: navigation arrows are found this way. */
    private static boolean mentions(GuiItemDef.Clicks clicks, String tag) {
        return mentions(clicks.actions().lines(), tag) || mentions(clicks.deny().lines(), tag);
    }

    private static boolean mentions(List<String> lines, String tag) {
        for (String line : lines) {
            if (line != null && line.toLowerCase(Locale.ROOT).contains(tag)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ values

    /** A text value; numbers and booleans are used as text. */
    private static String text(ConfigurationSection section, String key, String def) {
        Object raw = section.get(key);
        if (raw == null || raw instanceof ConfigurationSection || raw instanceof List<?> || raw instanceof Map<?, ?>) {
            return def;
        }
        return String.valueOf(raw);
    }

    /** The text entries of a list (other entries were reported by YamlChecks); empty when it is not a list. */
    static List<String> lines(ConfigurationSection section, String key) {
        Object raw = section.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof String s) {
                out.add(s);
            } else if (element instanceof Number || element instanceof Boolean) {
                out.add(String.valueOf(element));
            }
        }
        return out;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean def, Consumer<String> w) {
        if (!section.contains(key)) {
            return def;
        }
        Object raw = section.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        String text = String.valueOf(raw).trim();
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        w.accept("'" + key + "' must be true or false, got '" + raw + "'; using " + def);
        return def;
    }

    /** A tick count of at least 0 (YamlChecks reported a value that is not a number). */
    private static int ticks(ConfigurationSection section, String key, Consumer<String> w) {
        Object raw = section.get(key);
        if (!(raw instanceof Number n)) {
            return 0;
        }
        if (n.intValue() < 0) {
            w.accept("'" + key + "' cannot be negative, got " + raw + "; using 0");
            return 0;
        }
        return n.intValue();
    }
}
