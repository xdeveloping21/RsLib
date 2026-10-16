package com.rs.lib.item;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

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

    private final ItemValues v;
    private volatile Requirement interactCheck;

    private ItemDef(Builder b) {
        this.v = b.values.frozen();
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ appearance

    /** The stack for {@code viewer}, before the registry stamps it; a YAML appearance is resolved per viewer. */
    ItemStack render(Player viewer, Function<String, String> locals) {
        return v.render(viewer, locals);
    }

    ItemAppearance appearance() {
        return v.appearance;
    }

    /** What this definition holds, with the rules RsLib's listeners read from it. */
    ItemValues values() {
        return v;
    }

    // ------------------------------------------------------------------ readers

    public boolean droppable() {
        return v.droppable;
    }

    public boolean moveable() {
        return v.moveable;
    }

    public boolean placeable() {
        return v.placeable;
    }

    public boolean tradeable() {
        return v.tradeable;
    }

    public boolean despawnable() {
        return v.despawnable;
    }

    public boolean keepOnDeath() {
        return v.keepOnDeath;
    }

    public int cooldownTicks() {
        return v.cooldownTicks;
    }

    public boolean locked() {
        return v.locked;
    }

    public boolean noDrop() {
        return v.noDrop;
    }

    /** Whether the item may not be worn by hand; true for {@link ObtainMode#COMMAND_ONLY} too. */
    public boolean noManualEquip() {
        return v.noManualEquip || v.obtainVia == ObtainMode.COMMAND_ONLY;
    }

    public ObtainMode obtainVia() {
        return v.obtainVia;
    }

    /** 0 when custom durability is off. */
    public int durabilityMax() {
        return v.durabilityMax;
    }

    public int durabilityDamagePerUse() {
        return v.durabilityDamagePerUse;
    }

    public String durabilityLoreFormat() {
        return v.durabilityLoreFormat;
    }

    public List<String> breakActions() {
        return v.breakActions;
    }

    public List<String> rightClickActions() {
        return v.useActions.get(ItemUse.RIGHT_CLICK);
    }

    public List<String> leftClickActions() {
        return v.useActions.get(ItemUse.LEFT_CLICK);
    }

    public List<String> shiftRightClickActions() {
        return v.useActions.get(ItemUse.SHIFT_RIGHT_CLICK);
    }

    public List<String> shiftLeftClickActions() {
        return v.useActions.get(ItemUse.SHIFT_LEFT_CLICK);
    }

    public List<String> rightClickBlockActions() {
        return v.useActions.get(ItemUse.RIGHT_CLICK_BLOCK);
    }

    public List<String> rightClickAirActions() {
        return v.useActions.get(ItemUse.RIGHT_CLICK_AIR);
    }

    public List<String> leftClickBlockActions() {
        return v.useActions.get(ItemUse.LEFT_CLICK_BLOCK);
    }

    public List<String> leftClickAirActions() {
        return v.useActions.get(ItemUse.LEFT_CLICK_AIR);
    }

    public List<String> shiftRightClickBlockActions() {
        return v.useActions.get(ItemUse.SHIFT_RIGHT_CLICK_BLOCK);
    }

    public List<String> shiftRightClickAirActions() {
        return v.useActions.get(ItemUse.SHIFT_RIGHT_CLICK_AIR);
    }

    public List<String> shiftLeftClickBlockActions() {
        return v.useActions.get(ItemUse.SHIFT_LEFT_CLICK_BLOCK);
    }

    public List<String> shiftLeftClickAirActions() {
        return v.useActions.get(ItemUse.SHIFT_LEFT_CLICK_AIR);
    }

    public boolean shiftOverridesGeneric() {
        return v.shiftOverridesGeneric;
    }

    public List<String> interactRequirements() {
        return v.interactRequirements;
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
        return v.interactRequirements.isEmpty() ? Requirement.ALWAYS : Requirement.NEVER;
    }

    void interactCheck(Requirement check) {
        this.interactCheck = check;
    }

    public List<String> denyActions() {
        return v.denyActions;
    }

    public List<String> pickupActions() {
        return v.pickupActions;
    }

    public List<String> dropActions() {
        return v.dropActions;
    }

    public List<String> heldEffectsMainhand() {
        return v.heldEffectsMainhand;
    }

    public List<String> heldEffectsOffhand() {
        return v.heldEffectsOffhand;
    }

    public List<String> heldEffectsArmor() {
        return v.heldEffectsArmor;
    }

    /** The equipment slot as written, or {@code ""}. */
    public String equipmentSlotName() {
        return v.equipmentSlot;
    }

    /** The equipment slot this item may only be worn in, or null for any. */
    public EquipmentSlot equipmentSlot() {
        return ItemParts.equipmentSlot(v.equipmentSlot);
    }

    public Recipe recipe() {
        return v.recipe;
    }

    public BiConsumer<Player, ItemStack> onRightClick() {
        return v.useCallbacks.get(ItemUse.RIGHT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onLeftClick() {
        return v.useCallbacks.get(ItemUse.LEFT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onShiftRightClick() {
        return v.useCallbacks.get(ItemUse.SHIFT_RIGHT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onShiftLeftClick() {
        return v.useCallbacks.get(ItemUse.SHIFT_LEFT_CLICK);
    }

    public BiConsumer<Player, ItemStack> onRightClickBlock() {
        return v.useCallbacks.get(ItemUse.RIGHT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onRightClickAir() {
        return v.useCallbacks.get(ItemUse.RIGHT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onLeftClickBlock() {
        return v.useCallbacks.get(ItemUse.LEFT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onLeftClickAir() {
        return v.useCallbacks.get(ItemUse.LEFT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onShiftRightClickBlock() {
        return v.useCallbacks.get(ItemUse.SHIFT_RIGHT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onShiftRightClickAir() {
        return v.useCallbacks.get(ItemUse.SHIFT_RIGHT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onShiftLeftClickBlock() {
        return v.useCallbacks.get(ItemUse.SHIFT_LEFT_CLICK_BLOCK);
    }

    public BiConsumer<Player, ItemStack> onShiftLeftClickAir() {
        return v.useCallbacks.get(ItemUse.SHIFT_LEFT_CLICK_AIR);
    }

    public BiConsumer<Player, ItemStack> onApply() {
        return v.onApply;
    }

    public BiConsumer<Player, ItemStack> onRemove() {
        return v.onRemove;
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {

        /** What the definition is built from; the YAML reader fills it directly. */
        final ItemValues values = new ItemValues();

        private Builder() {
        }

        /** The appearance, built again on every creation. */
        public Builder item(RsItem item) {
            values.builderItem = item;
            values.template = null;
            values.appearance = null;
            return this;
        }

        /** The appearance as a stack, copied on every creation. */
        public Builder item(ItemStack item) {
            values.template = item;
            values.builderItem = null;
            values.appearance = null;
            return this;
        }

        /** A YAML appearance, rendered per player on every creation. */
        public Builder appearance(ItemAppearance value) {
            values.appearance = value;
            values.builderItem = null;
            values.template = null;
            return this;
        }

        public Builder droppable(boolean value) {
            values.droppable = value;
            return this;
        }

        public Builder moveable(boolean value) {
            values.moveable = value;
            return this;
        }

        public Builder placeable(boolean value) {
            values.placeable = value;
            return this;
        }

        public Builder tradeable(boolean value) {
            values.tradeable = value;
            return this;
        }

        public Builder despawnable(boolean value) {
            values.despawnable = value;
            return this;
        }

        public Builder keepOnDeath() {
            return keepOnDeath(true);
        }

        public Builder keepOnDeath(boolean value) {
            values.keepOnDeath = value;
            return this;
        }

        public Builder locked() {
            return locked(true);
        }

        public Builder locked(boolean value) {
            values.locked = value;
            return this;
        }

        public Builder noDrop() {
            return noDrop(true);
        }

        public Builder noDrop(boolean value) {
            values.noDrop = value;
            return this;
        }

        public Builder noManualEquip() {
            return noManualEquip(true);
        }

        public Builder noManualEquip(boolean value) {
            values.noManualEquip = value;
            return this;
        }

        public Builder obtainVia(ObtainMode mode) {
            values.obtainVia = mode == null ? ObtainMode.UNRESTRICTED : mode;
            return this;
        }

        public Builder cooldownTicks(int ticks) {
            values.cooldownTicks = ticks;
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
            values.durabilityMax = max;
            values.durabilityDamagePerUse = damagePerUse;
            values.durabilityLoreFormat = loreFormat == null ? "" : loreFormat;
            values.breakActions = breakActions == null ? List.of() : breakActions;
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
            values.useActions.put(use, actions == null ? List.of() : actions);
            return this;
        }

        /** Sneaking runs the shift list instead of the plain one (true, the default) or before it (false). */
        public Builder shiftOverridesGeneric(boolean value) {
            values.shiftOverridesGeneric = value;
            return this;
        }

        public Builder interactRequirements(List<String> lines) {
            values.interactRequirements = lines == null ? List.of() : lines;
            return this;
        }

        public Builder denyActions(List<String> actions) {
            values.denyActions = actions == null ? List.of() : actions;
            return this;
        }

        public Builder pickupActions(List<String> actions) {
            values.pickupActions = actions == null ? List.of() : actions;
            return this;
        }

        public Builder dropActions(List<String> actions) {
            values.dropActions = actions == null ? List.of() : actions;
            return this;
        }

        /** Effects while held in the main hand: {@code "EFFECT amplifier"}. */
        public Builder heldEffectsMainhand(List<String> effects) {
            values.heldEffectsMainhand = effects == null ? List.of() : effects;
            return this;
        }

        public Builder heldEffectsOffhand(List<String> effects) {
            values.heldEffectsOffhand = effects == null ? List.of() : effects;
            return this;
        }

        public Builder heldEffectsArmor(List<String> effects) {
            values.heldEffectsArmor = effects == null ? List.of() : effects;
            return this;
        }

        /** The only equipment slot the item may be worn in: MAINHAND, OFFHAND, HEAD, CHEST, LEGS or FEET. */
        public Builder equipmentSlot(String slot) {
            values.equipmentSlot = slot;
            return this;
        }

        public Builder recipe(Recipe value) {
            values.recipe = value;
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
            values.onApply = callback;
            return this;
        }

        /** Runs after {@link ItemRegistry#unapply} takes the item off. */
        public Builder onRemove(BiConsumer<Player, ItemStack> callback) {
            values.onRemove = callback;
            return this;
        }

        private Builder callback(ItemUse use, BiConsumer<Player, ItemStack> callback) {
            if (callback == null) {
                values.useCallbacks.remove(use);
            } else {
                values.useCallbacks.put(use, callback);
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

        @Override
        public String toString() {
            return "Recipe[" + type.toLowerCase(Locale.ROOT) + "]";
        }
    }
}
