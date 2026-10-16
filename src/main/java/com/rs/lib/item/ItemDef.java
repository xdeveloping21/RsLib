package com.rs.lib.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.action.Requirement;

/**
 * A registered physical item: its appearance and its behaviour. Immutable; build one with
 * {@link #builder()} or write it in YAML (one top-level section per item):
 *
 * <pre>
 * miner-pickaxe:
 *   material: DIAMOND_PICKAXE           # every appearance key (see ItemAppearance)
 *   droppable: true                     # false: cannot be dropped
 *   moveable: true                      # false: cannot be moved in inventories
 *   placeable: true                     # false: cannot be placed as a block
 *   tradeable: true                     # false: cannot be put in a villager trade
 *   despawnable: true                   # false: never despawns on the ground
 *   keep-on-death: false                # kept on death and given back on respawn
 *   cooldown: 0                         # ticks between uses
 *   locked: false                       # fixed in its slot: no click, drag, equip, swap, drop, death, hopper
 *   no-drop: false                      # cannot be dropped, whatever droppable says
 *   no-manual-equip: false              # cannot be worn by hand
 *   obtain-via: ""                      # "" or COMMAND_ONLY (only worn through apply)
 *   custom-durability:
 *     max: 0                            # 0 = off
 *     damage-per-use: 1
 *     lore-format: "&amp;7Durability: %durability%/%max_durability%"
 *     break-actions: []
 *   right-click-actions: []             # also left-, shift-right-, shift-left-click-actions
 *   right-click-block-actions: []       # and -air-; run in addition to the generic list
 *   shift-overrides-generic: true       # sneaking runs the shift list instead of the plain one
 *   interact-requirements: []
 *   deny-actions: []
 *   pickup-actions: []
 *   drop-actions: []
 *   held-effects:
 *     mainhand: ["SPEED 1"]             # "EFFECT amplifier"; also offhand and armor
 *   recipe:
 *     type: SHAPED                      # SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE, STONECUTTING
 *     shape: ["ddd", " s ", " s "]
 *     ingredients: {d: DIAMOND, s: STICK}
 * </pre>
 */
public final class ItemDef {

    private final ItemAppearance appearance;
    private final RsItem builderItem;
    private final ItemStack template;
    private final boolean droppable;
    private final boolean moveable;
    private final boolean placeable;
    private final boolean tradeable;
    private final boolean despawnable;
    private final boolean keepOnDeath;
    private final boolean locked;
    private final boolean noDrop;
    private final boolean noManualEquip;
    private final ObtainMode obtainVia;
    private final int cooldownTicks;
    private final int durabilityMax;
    private final int durabilityDamagePerUse;
    private final String durabilityLoreFormat;
    private final List<String> breakActions;
    private final Map<ItemUse, List<String>> useActions;
    private final Map<ItemUse, BiConsumer<Player, ItemStack>> useCallbacks;
    private final boolean shiftOverridesGeneric;
    private final List<String> interactRequirements;
    private final List<String> denyActions;
    private final List<String> pickupActions;
    private final List<String> dropActions;
    private final List<String> heldEffectsMainhand;
    private final List<String> heldEffectsOffhand;
    private final List<String> heldEffectsArmor;
    private final String equipmentSlotName;
    private final Recipe recipe;
    private final BiConsumer<Player, ItemStack> onApply;
    private final BiConsumer<Player, ItemStack> onRemove;
    private volatile Requirement interactCheck;

    private ItemDef(Builder b) {
        this.appearance = b.appearance;
        this.builderItem = b.builderItem == null ? null : b.builderItem.copy();
        this.template = b.template == null ? null : b.template.clone();
        this.droppable = b.droppable;
        this.moveable = b.moveable;
        this.placeable = b.placeable;
        this.tradeable = b.tradeable;
        this.despawnable = b.despawnable;
        this.keepOnDeath = b.keepOnDeath;
        this.locked = b.locked;
        this.noDrop = b.noDrop;
        this.noManualEquip = b.noManualEquip;
        this.obtainVia = b.obtainVia;
        this.cooldownTicks = Math.max(0, b.cooldownTicks);
        this.durabilityMax = Math.max(0, b.durabilityMax);
        this.durabilityDamagePerUse = Math.max(1, b.durabilityDamagePerUse);
        this.durabilityLoreFormat = b.durabilityLoreFormat == null ? "" : b.durabilityLoreFormat;
        this.breakActions = copy(b.breakActions);
        Map<ItemUse, List<String>> actions = new EnumMap<>(ItemUse.class);
        for (ItemUse use : ItemUse.values()) {
            actions.put(use, copy(b.useActions.get(use)));
        }
        this.useActions = Collections.unmodifiableMap(actions);
        this.useCallbacks = Collections.unmodifiableMap(new EnumMap<>(b.useCallbacks));
        this.shiftOverridesGeneric = b.shiftOverridesGeneric;
        this.interactRequirements = copy(b.interactRequirements);
        this.denyActions = copy(b.denyActions);
        this.pickupActions = copy(b.pickupActions);
        this.dropActions = copy(b.dropActions);
        this.heldEffectsMainhand = copy(b.heldEffectsMainhand);
        this.heldEffectsOffhand = copy(b.heldEffectsOffhand);
        this.heldEffectsArmor = copy(b.heldEffectsArmor);
        String slot = b.equipmentSlot;
        if (slot == null && appearance != null) {
            slot = appearance.equipmentSlot();
        }
        if (slot == null && builderItem != null) {
            slot = builderItem.equipmentSlotName();
        }
        this.equipmentSlotName = slot == null || slot.isBlank() ? "" : slot.trim();
        this.recipe = b.recipe;
        this.onApply = b.onApply;
        this.onRemove = b.onRemove;
    }

    private static List<String> copy(List<String> list) {
        return list == null || list.isEmpty() ? List.of() : List.copyOf(list);
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ appearance

    /** The stack for {@code viewer}, before the registry stamps it; a YAML appearance is resolved per viewer. */
    ItemStack render(Player viewer, Function<String, String> locals) {
        if (appearance != null) {
            return appearance.render(viewer, locals);
        }
        if (builderItem != null) {
            return builderItem.build();
        }
        if (template != null) {
            return template.clone();
        }
        return new ItemStack(Material.STONE);
    }

    ItemAppearance appearance() {
        return appearance;
    }

    // ------------------------------------------------------------------ readers

    public boolean droppable() {
        return droppable;
    }

    public boolean moveable() {
        return moveable;
    }

    public boolean placeable() {
        return placeable;
    }

    public boolean tradeable() {
        return tradeable;
    }

    public boolean despawnable() {
        return despawnable;
    }

    public boolean keepOnDeath() {
        return keepOnDeath;
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public boolean locked() {
        return locked;
    }

    public boolean noDrop() {
        return noDrop;
    }

    /** Whether the item may not be worn by hand; true for {@link ObtainMode#COMMAND_ONLY} too. */
    public boolean noManualEquip() {
        return noManualEquip || obtainVia == ObtainMode.COMMAND_ONLY;
    }

    public ObtainMode obtainVia() {
        return obtainVia;
    }

    /** 0 when custom durability is off. */
    public int durabilityMax() {
        return durabilityMax;
    }

    public int durabilityDamagePerUse() {
        return durabilityDamagePerUse;
    }

    public String durabilityLoreFormat() {
        return durabilityLoreFormat;
    }

    public List<String> breakActions() {
        return breakActions;
    }

    public List<String> rightClickActions() {
        return useActions.get(ItemUse.RIGHT_CLICK);
    }

    public List<String> leftClickActions() {
        return useActions.get(ItemUse.LEFT_CLICK);
    }

    public List<String> shiftRightClickActions() {
        return useActions.get(ItemUse.SHIFT_RIGHT_CLICK);
    }

    public List<String> shiftLeftClickActions() {
        return useActions.get(ItemUse.SHIFT_LEFT_CLICK);
    }

    public List<String> rightClickBlockActions() {
        return useActions.get(ItemUse.RIGHT_CLICK_BLOCK);
    }

    public List<String> rightClickAirActions() {
        return useActions.get(ItemUse.RIGHT_CLICK_AIR);
    }

    public List<String> leftClickBlockActions() {
        return useActions.get(ItemUse.LEFT_CLICK_BLOCK);
    }

    public List<String> leftClickAirActions() {
        return useActions.get(ItemUse.LEFT_CLICK_AIR);
    }

    public List<String> shiftRightClickBlockActions() {
        return useActions.get(ItemUse.SHIFT_RIGHT_CLICK_BLOCK);
    }

    public List<String> shiftRightClickAirActions() {
        return useActions.get(ItemUse.SHIFT_RIGHT_CLICK_AIR);
    }

    public List<String> shiftLeftClickBlockActions() {
        return useActions.get(ItemUse.SHIFT_LEFT_CLICK_BLOCK);
    }

    public List<String> shiftLeftClickAirActions() {
        return useActions.get(ItemUse.SHIFT_LEFT_CLICK_AIR);
    }

    public boolean shiftOverridesGeneric() {
        return shiftOverridesGeneric;
    }

    public List<String> interactRequirements() {
        return interactRequirements;
    }

    /**
     * The interact requirements, parsed once by the action engine of the plugin the item is
     * registered with. Before it is registered: true when there are none, false otherwise.
     */
    public Requirement interactRequirement() {
        Requirement check = interactCheck;
        if (check != null) {
            return check;
        }
        return interactRequirements.isEmpty() ? Requirement.ALWAYS : Requirement.NEVER;
    }

    void interactCheck(Requirement check) {
        this.interactCheck = check;
    }

    public List<String> denyActions() {
        return denyActions;
    }

    public List<String> pickupActions() {
        return pickupActions;
    }

    public List<String> dropActions() {
        return dropActions;
    }

    public List<String> heldEffectsMainhand() {
        return heldEffectsMainhand;
    }

    public List<String> heldEffectsOffhand() {
        return heldEffectsOffhand;
    }

    public List<String> heldEffectsArmor() {
        return heldEffectsArmor;
    }

    /** The equipment slot as written, or {@code ""}. */
    public String equipmentSlotName() {
        return equipmentSlotName;
    }

    /** The equipment slot this item may only be worn in, or null for any. */
    public EquipmentSlot equipmentSlot() {
        return ItemParts.equipmentSlot(equipmentSlotName);
    }

    public Recipe recipe() {
        return recipe;
    }

    public BiConsumer<Player, ItemStack> onRightClick() {
        return useCallbacks.get(ItemUse.RIGHT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onLeftClick() {
        return useCallbacks.get(ItemUse.LEFT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onShiftRightClick() {
        return useCallbacks.get(ItemUse.SHIFT_RIGHT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onShiftLeftClick() {
        return useCallbacks.get(ItemUse.SHIFT_LEFT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onRightClickBlock() {
        return useCallbacks.get(ItemUse.RIGHT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onRightClickAir() {
        return useCallbacks.get(ItemUse.RIGHT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onLeftClickBlock() {
        return useCallbacks.get(ItemUse.LEFT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onLeftClickAir() {
        return useCallbacks.get(ItemUse.LEFT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onShiftRightClickBlock() {
        return useCallbacks.get(ItemUse.SHIFT_RIGHT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onShiftRightClickAir() {
        return useCallbacks.get(ItemUse.SHIFT_RIGHT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onShiftLeftClickBlock() {
        return useCallbacks.get(ItemUse.SHIFT_LEFT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onShiftLeftClickAir() {
        return useCallbacks.get(ItemUse.SHIFT_LEFT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onApply() {
        return onApply;
    }

    public BiConsumer<Player, ItemStack> onRemove() {
        return onRemove;
    }

    // ------------------------------------------------------------------ package helpers

    List<String> actions(ItemUse use) {
        return useActions.get(use);
    }

    BiConsumer<Player, ItemStack> callback(ItemUse use) {
        return useCallbacks.get(use);
    }

    /** A use does something: its list is not empty or it has a callback. */
    boolean active(ItemUse use) {
        return !useActions.get(use).isEmpty() || useCallbacks.get(use) != null;
    }

    /** Whether any held effect is declared. */
    boolean hasHeldEffects() {
        return !heldEffectsMainhand.isEmpty() || !heldEffectsOffhand.isEmpty() || !heldEffectsArmor.isEmpty();
    }

    /** Kept on death: locked items and keep-on-death items. */
    boolean keptOnDeath() {
        return keepOnDeath || locked;
    }

    /** Dropping is refused: locked, no-drop or not droppable. */
    boolean refusesDrop() {
        return locked || noDrop || !droppable;
    }

    // ------------------------------------------------------------------ YAML

    /**
     * The item written in {@code section}. Bad values are reported through {@code warn} and keep
     * their defaults; this never throws for content.
     */
    static ItemDef fromSection(ConfigurationSection section, ItemAppearance appearance, Consumer<String> warn) {
        Builder b = builder();
        b.appearance = appearance;
        b.droppable = bool(section, "droppable", true, warn);
        b.moveable = bool(section, "moveable", true, warn);
        b.placeable = bool(section, "placeable", true, warn);
        b.tradeable = bool(section, "tradeable", true, warn);
        b.despawnable = bool(section, "despawnable", true, warn);
        b.keepOnDeath = bool(section, "keep-on-death", false, warn);
        b.locked = bool(section, "locked", false, warn);
        b.noDrop = bool(section, "no-drop", false, warn);
        b.noManualEquip = bool(section, "no-manual-equip", false, warn);
        b.cooldownTicks = integer(section, "cooldown", 0, warn);
        String obtain = section.getString("obtain-via", "");
        if (obtain != null && !obtain.isBlank()) {
            if (ItemParts.constant(obtain).equals("COMMAND_ONLY")) {
                b.obtainVia = ObtainMode.COMMAND_ONLY;
            } else if (!ItemParts.constant(obtain).equals("UNRESTRICTED")) {
                warn.accept("Unknown obtain-via '" + obtain + "' (\"\" or COMMAND_ONLY); using \"\".");
            }
        }
        ConfigurationSection durability = section.getConfigurationSection("custom-durability");
        if (durability != null) {
            b.durabilityMax = integer(durability, "max", 0, warn);
            b.durabilityDamagePerUse = Math.max(1, integer(durability, "damage-per-use", 1, warn));
            b.durabilityLoreFormat = durability.getString("lore-format", "");
            b.breakActions = list(durability, "break-actions", warn);
        } else if (section.contains("custom-durability")) {
            warn.accept("'custom-durability' must be a section (max, damage-per-use, lore-format, break-actions); ignored.");
        }
        for (ItemUse use : ItemUse.values()) {
            b.useActions.put(use, list(section, use.key(), warn));
        }
        b.shiftOverridesGeneric = bool(section, "shift-overrides-generic", true, warn);
        b.interactRequirements = list(section, "interact-requirements", warn);
        b.denyActions = list(section, "deny-actions", warn);
        b.pickupActions = list(section, "pickup-actions", warn);
        b.dropActions = list(section, "drop-actions", warn);
        ConfigurationSection held = section.getConfigurationSection("held-effects");
        if (held != null) {
            b.heldEffectsMainhand = list(held, "mainhand", warn);
            b.heldEffectsOffhand = list(held, "offhand", warn);
            b.heldEffectsArmor = list(held, "armor", warn);
        }
        ConfigurationSection recipe = section.getConfigurationSection("recipe");
        if (recipe != null) {
            b.recipe = Recipe.fromSection(recipe, warn);
        }
        return b.build();
    }

    private static boolean bool(ConfigurationSection section, String key, boolean def, Consumer<String> warn) {
        Object value = section.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(text);
        }
        warn.accept("'" + key + "' must be true or false, got '" + value + "'; using " + def + ".");
        return def;
    }

    private static int integer(ConfigurationSection section, String key, int def, Consumer<String> warn) {
        Object value = section.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        Integer parsed = ItemParts.intOrNull(String.valueOf(value).trim());
        if (parsed != null) {
            return parsed;
        }
        warn.accept("'" + key + "' must be a whole number, got '" + value + "'; using " + def + ".");
        return def;
    }

    /** A list of texts; nested lists or maps (an unquoted {@code - [close]}) are reported and skipped. */
    static List<String> list(ConfigurationSection section, String key, Consumer<String> warn) {
        Object value = section.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return List.of(String.valueOf(value));
        }
        if (!(value instanceof List<?> entries)) {
            warn.accept("'" + key + "' must be a list (one '- ' line per entry), got '" + value + "'; ignored.");
            return List.of();
        }
        List<String> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (entry instanceof List<?> || entry instanceof Map<?, ?> || entry instanceof ConfigurationSection) {
                warn.accept("'" + key + "' entry " + (i + 1) + " is not text (" + entry
                        + "); write it in quotes, for example - '[close]'; skipped.");
            } else if (entry != null) {
                out.add(String.valueOf(entry));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {

        private ItemAppearance appearance;
        private RsItem builderItem;
        private ItemStack template;
        private boolean droppable = true;
        private boolean moveable = true;
        private boolean placeable = true;
        private boolean tradeable = true;
        private boolean despawnable = true;
        private boolean keepOnDeath;
        private boolean locked;
        private boolean noDrop;
        private boolean noManualEquip;
        private ObtainMode obtainVia = ObtainMode.UNRESTRICTED;
        private int cooldownTicks;
        private int durabilityMax;
        private int durabilityDamagePerUse = 1;
        private String durabilityLoreFormat = "";
        private List<String> breakActions = List.of();
        private final Map<ItemUse, List<String>> useActions = new EnumMap<>(ItemUse.class);
        private final Map<ItemUse, BiConsumer<Player, ItemStack>> useCallbacks = new EnumMap<>(ItemUse.class);
        private boolean shiftOverridesGeneric = true;
        private List<String> interactRequirements = List.of();
        private List<String> denyActions = List.of();
        private List<String> pickupActions = List.of();
        private List<String> dropActions = List.of();
        private List<String> heldEffectsMainhand = List.of();
        private List<String> heldEffectsOffhand = List.of();
        private List<String> heldEffectsArmor = List.of();
        private String equipmentSlot;
        private Recipe recipe;
        private BiConsumer<Player, ItemStack> onApply;
        private BiConsumer<Player, ItemStack> onRemove;

        private Builder() {
        }

        /** The appearance, built again on every creation. */
        public Builder item(RsItem item) {
            this.builderItem = item;
            this.template = null;
            this.appearance = null;
            return this;
        }

        /** The appearance as a stack, copied on every creation. */
        public Builder item(ItemStack item) {
            this.template = item;
            this.builderItem = null;
            this.appearance = null;
            return this;
        }

        /** A YAML appearance, rendered per player on every creation. */
        public Builder appearance(ItemAppearance value) {
            this.appearance = value;
            this.builderItem = null;
            this.template = null;
            return this;
        }

        public Builder droppable(boolean value) {
            this.droppable = value;
            return this;
        }

        public Builder moveable(boolean value) {
            this.moveable = value;
            return this;
        }

        public Builder placeable(boolean value) {
            this.placeable = value;
            return this;
        }

        public Builder tradeable(boolean value) {
            this.tradeable = value;
            return this;
        }

        public Builder despawnable(boolean value) {
            this.despawnable = value;
            return this;
        }

        public Builder keepOnDeath() {
            return keepOnDeath(true);
        }

        public Builder keepOnDeath(boolean value) {
            this.keepOnDeath = value;
            return this;
        }

        public Builder locked() {
            return locked(true);
        }

        public Builder locked(boolean value) {
            this.locked = value;
            return this;
        }

        public Builder noDrop() {
            return noDrop(true);
        }

        public Builder noDrop(boolean value) {
            this.noDrop = value;
            return this;
        }

        public Builder noManualEquip() {
            return noManualEquip(true);
        }

        public Builder noManualEquip(boolean value) {
            this.noManualEquip = value;
            return this;
        }

        public Builder obtainVia(ObtainMode mode) {
            this.obtainVia = mode == null ? ObtainMode.UNRESTRICTED : mode;
            return this;
        }

        public Builder cooldownTicks(int ticks) {
            this.cooldownTicks = ticks;
            return this;
        }

        /** {@link #customDurability(int, int, String, List)} without break actions. */
        public Builder customDurability(int max, int damagePerUse, String loreFormat) {
            return customDurability(max, damagePerUse, loreFormat, List.of());
        }

        /**
         * Durability of its own: {@code max} uses' worth (0 turns it off), {@code damagePerUse}
         * per successful use (at least 1), a lore line with {@code %durability%} and
         * {@code %max_durability%} kept up to date, and actions run when it breaks.
         */
        public Builder customDurability(int max, int damagePerUse, String loreFormat, List<String> breakActions) {
            this.durabilityMax = max;
            this.durabilityDamagePerUse = damagePerUse;
            this.durabilityLoreFormat = loreFormat == null ? "" : loreFormat;
            this.breakActions = breakActions == null ? List.of() : breakActions;
            return this;
        }

        public Builder rightClickActions(List<String> actions) {
            return use(ItemUse.RIGHT_CLICK, actions);
        }

        public Builder leftClickActions(List<String> actions) {
            return use(ItemUse.LEFT_CLICK, actions);
        }

        public Builder shiftRightClickActions(List<String> actions) {
            return use(ItemUse.SHIFT_RIGHT_CLICK, actions);
        }

        public Builder shiftLeftClickActions(List<String> actions) {
            return use(ItemUse.SHIFT_LEFT_CLICK, actions);
        }

        public Builder rightClickBlockActions(List<String> actions) {
            return use(ItemUse.RIGHT_CLICK_BLOCK, actions);
        }

        public Builder rightClickAirActions(List<String> actions) {
            return use(ItemUse.RIGHT_CLICK_AIR, actions);
        }

        public Builder leftClickBlockActions(List<String> actions) {
            return use(ItemUse.LEFT_CLICK_BLOCK, actions);
        }

        public Builder leftClickAirActions(List<String> actions) {
            return use(ItemUse.LEFT_CLICK_AIR, actions);
        }

        public Builder shiftRightClickBlockActions(List<String> actions) {
            return use(ItemUse.SHIFT_RIGHT_CLICK_BLOCK, actions);
        }

        public Builder shiftRightClickAirActions(List<String> actions) {
            return use(ItemUse.SHIFT_RIGHT_CLICK_AIR, actions);
        }

        public Builder shiftLeftClickBlockActions(List<String> actions) {
            return use(ItemUse.SHIFT_LEFT_CLICK_BLOCK, actions);
        }

        public Builder shiftLeftClickAirActions(List<String> actions) {
            return use(ItemUse.SHIFT_LEFT_CLICK_AIR, actions);
        }

        private Builder use(ItemUse use, List<String> actions) {
            useActions.put(use, actions == null ? List.of() : actions);
            return this;
        }

        /** Sneaking runs the shift list instead of the plain one (true, the default) or before it (false). */
        public Builder shiftOverridesGeneric(boolean value) {
            this.shiftOverridesGeneric = value;
            return this;
        }

        public Builder interactRequirements(List<String> lines) {
            this.interactRequirements = lines == null ? List.of() : lines;
            return this;
        }

        public Builder denyActions(List<String> actions) {
            this.denyActions = actions == null ? List.of() : actions;
            return this;
        }

        public Builder pickupActions(List<String> actions) {
            this.pickupActions = actions == null ? List.of() : actions;
            return this;
        }

        public Builder dropActions(List<String> actions) {
            this.dropActions = actions == null ? List.of() : actions;
            return this;
        }

        /** Effects while held in the main hand: {@code "EFFECT amplifier"}. */
        public Builder heldEffectsMainhand(List<String> effects) {
            this.heldEffectsMainhand = effects == null ? List.of() : effects;
            return this;
        }

        public Builder heldEffectsOffhand(List<String> effects) {
            this.heldEffectsOffhand = effects == null ? List.of() : effects;
            return this;
        }

        public Builder heldEffectsArmor(List<String> effects) {
            this.heldEffectsArmor = effects == null ? List.of() : effects;
            return this;
        }

        /** The only equipment slot the item may be worn in: MAINHAND, OFFHAND, HEAD, CHEST, LEGS or FEET. */
        public Builder equipmentSlot(String slot) {
            this.equipmentSlot = slot;
            return this;
        }

        public Builder recipe(Recipe value) {
            this.recipe = value;
            return this;
        }

        public Builder onRightClick(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.RIGHT_CLICK, callback);
        }

        public Builder onLeftClick(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.LEFT_CLICK, callback);
        }

        public Builder onShiftRightClick(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.SHIFT_RIGHT_CLICK, callback);
        }

        public Builder onShiftLeftClick(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.SHIFT_LEFT_CLICK, callback);
        }

        public Builder onRightClickBlock(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.RIGHT_CLICK_BLOCK, callback);
        }

        public Builder onRightClickAir(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.RIGHT_CLICK_AIR, callback);
        }

        public Builder onLeftClickBlock(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.LEFT_CLICK_BLOCK, callback);
        }

        public Builder onLeftClickAir(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.LEFT_CLICK_AIR, callback);
        }

        public Builder onShiftRightClickBlock(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.SHIFT_RIGHT_CLICK_BLOCK, callback);
        }

        public Builder onShiftRightClickAir(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.SHIFT_RIGHT_CLICK_AIR, callback);
        }

        public Builder onShiftLeftClickBlock(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.SHIFT_LEFT_CLICK_BLOCK, callback);
        }

        public Builder onShiftLeftClickAir(BiConsumer<Player, ItemStack> callback) {
            return callback(ItemUse.SHIFT_LEFT_CLICK_AIR, callback);
        }

        /** Runs after {@link ItemRegistry#apply} puts the item on. */
        public Builder onApply(BiConsumer<Player, ItemStack> callback) {
            this.onApply = callback;
            return this;
        }

        /** Runs after {@link ItemRegistry#unapply} takes the item off. */
        public Builder onRemove(BiConsumer<Player, ItemStack> callback) {
            this.onRemove = callback;
            return this;
        }

        private Builder callback(ItemUse use, BiConsumer<Player, ItemStack> callback) {
            if (callback == null) {
                useCallbacks.remove(use);
            } else {
                useCallbacks.put(use, callback);
            }
            return this;
        }

        public ItemDef build() {
            return new ItemDef(this);
        }
    }

    // ------------------------------------------------------------------ recipes

    /** A crafting recipe whose result is the registered item. Immutable. */
    public static final class Recipe {

        static final Set<String> COOKING = Set.of("FURNACE", "SMOKING", "BLASTING", "CAMPFIRE");
        static final int DEFAULT_COOKING_TICKS = 200;

        private final String type;
        private final List<String> shape;
        private final Map<Character, String> ingredients;
        private final List<String> shapelessIngredients;
        private final String input;
        private final double experience;
        private final int cookingTime;

        private Recipe(String type, List<String> shape, Map<Character, String> ingredients, List<String> shapeless,
                       String input, double experience, int cookingTime) {
            this.type = type;
            this.shape = shape == null ? List.of() : List.copyOf(shape);
            this.ingredients = ingredients == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ingredients));
            this.shapelessIngredients = shapeless == null ? List.of() : List.copyOf(shapeless);
            this.input = input == null ? "" : input;
            this.experience = experience;
            this.cookingTime = cookingTime;
        }

        /** A shaped recipe: up to three rows of up to three characters and what each character stands for. */
        public static Recipe shaped(List<String> shape, Map<Character, String> ingredients) {
            return new Recipe("SHAPED", Objects.requireNonNull(shape, "shape"), ingredients, null, null, 0, 0);
        }

        /** A shapeless recipe from materials (repeat one to need several). */
        public static Recipe shapeless(List<String> ingredients) {
            return new Recipe("SHAPELESS", null, null, Objects.requireNonNull(ingredients, "ingredients"), null, 0, 0);
        }

        /** {@link #cooking(String, String, double, int)} taking 200 ticks. */
        public static Recipe cooking(String type, String input, double experience) {
            return cooking(type, input, experience, DEFAULT_COOKING_TICKS);
        }

        /** A FURNACE, SMOKING, BLASTING or CAMPFIRE recipe. */
        public static Recipe cooking(String type, String input, double experience, int ticks) {
            String kind = ItemParts.constant(Objects.requireNonNull(type, "type"));
            if (!COOKING.contains(kind)) {
                throw new IllegalArgumentException("Not a cooking recipe type: " + type
                        + " (FURNACE, SMOKING, BLASTING or CAMPFIRE)");
            }
            return new Recipe(kind, null, null, null, input, experience, Math.max(1, ticks));
        }

        public static Recipe stonecutting(String input) {
            return new Recipe("STONECUTTING", null, null, null, input, 0, 0);
        }

        /** SHAPED, SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE or STONECUTTING. */
        public String type() {
            return type;
        }

        public List<String> shape() {
            return shape;
        }

        public Map<Character, String> ingredients() {
            return ingredients;
        }

        public List<String> shapelessIngredients() {
            return shapelessIngredients;
        }

        public String input() {
            return input;
        }

        public double experience() {
            return experience;
        }

        public int cookingTime() {
            return cookingTime;
        }

        static Recipe fromSection(ConfigurationSection section, Consumer<String> warn) {
            String type = ItemParts.constant(section.getString("type", ""));
            switch (type) {
                case "SHAPED" -> {
                    List<String> shape = list(section, "shape", warn);
                    ConfigurationSection map = section.getConfigurationSection("ingredients");
                    if (shape.isEmpty() || map == null) {
                        warn.accept("A SHAPED recipe needs 'shape' and an 'ingredients' section (letter: MATERIAL); ignored.");
                        return null;
                    }
                    Map<Character, String> ingredients = new LinkedHashMap<>();
                    for (String key : map.getKeys(false)) {
                        if (key.length() != 1) {
                            warn.accept("Recipe ingredient key '" + key + "' must be one character; skipped.");
                            continue;
                        }
                        ingredients.put(key.charAt(0), map.getString(key, ""));
                    }
                    return shaped(shape, ingredients);
                }
                case "SHAPELESS" -> {
                    List<String> ingredients = list(section, "ingredients", warn);
                    if (ingredients.isEmpty()) {
                        warn.accept("A SHAPELESS recipe needs an 'ingredients' list; ignored.");
                        return null;
                    }
                    return shapeless(ingredients);
                }
                case "FURNACE", "SMOKING", "BLASTING", "CAMPFIRE" -> {
                    String input = section.getString("input", "");
                    if (input == null || input.isBlank()) {
                        warn.accept("A " + type + " recipe needs 'input'; ignored.");
                        return null;
                    }
                    return cooking(type, input, section.getDouble("experience", 0),
                            integer(section, "cooking-time", DEFAULT_COOKING_TICKS, warn));
                }
                case "STONECUTTING" -> {
                    String input = section.getString("input", "");
                    if (input == null || input.isBlank()) {
                        warn.accept("A STONECUTTING recipe needs 'input'; ignored.");
                        return null;
                    }
                    return stonecutting(input);
                }
                default -> {
                    warn.accept("Unknown recipe type '" + section.getString("type", "") + "' (SHAPED, SHAPELESS, FURNACE,"
                            + " SMOKING, BLASTING, CAMPFIRE, STONECUTTING); ignored.");
                    return null;
                }
            }
        }

        @Override
        public String toString() {
            return "Recipe[" + type.toLowerCase(Locale.ROOT) + "]";
        }
    }
}
