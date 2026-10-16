package com.rs.lib.item;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * What an {@link ItemDef} holds: filled by its {@link ItemDef.Builder} (or the YAML reader) and
 * copied, normalised, into the definition by {@link #frozen()}. The copy is never changed nor
 * handed out, so a definition stays immutable.
 */
final class ItemValues {

    ItemAppearance appearance;
    RsItem builderItem;
    ItemStack template;
    boolean droppable = true;
    boolean moveable = true;
    boolean placeable = true;
    boolean tradeable = true;
    boolean despawnable = true;
    boolean keepOnDeath;
    boolean locked;
    boolean noDrop;
    boolean noManualEquip;
    ObtainMode obtainVia = ObtainMode.UNRESTRICTED;
    int cooldownTicks;
    int durabilityMax;
    int durabilityDamagePerUse = 1;
    String durabilityLoreFormat = "";
    List<String> breakActions = List.of();
    Map<ItemUse, List<String>> useActions = new EnumMap<>(ItemUse.class);
    Map<ItemUse, BiConsumer<Player, ItemStack>> useCallbacks = new EnumMap<>(ItemUse.class);
    boolean shiftOverridesGeneric = true;
    List<String> interactRequirements = List.of();
    List<String> denyActions = List.of();
    List<String> pickupActions = List.of();
    List<String> dropActions = List.of();
    List<String> heldEffectsMainhand = List.of();
    List<String> heldEffectsOffhand = List.of();
    List<String> heldEffectsArmor = List.of();
    /** As given to the builder; once frozen, the slot actually used ({@code ""} for any). */
    String equipmentSlot;
    ItemDef.Recipe recipe;
    BiConsumer<Player, ItemStack> onApply;
    BiConsumer<Player, ItemStack> onRemove;

    /** A normalised copy for a definition: lists and stacks copied, numbers brought into range. */
    ItemValues frozen() {
        ItemValues v = new ItemValues();
        v.appearance = appearance;
        v.builderItem = builderItem == null ? null : builderItem.copy();
        v.template = template == null ? null : template.clone();
        v.droppable = droppable;
        v.moveable = moveable;
        v.placeable = placeable;
        v.tradeable = tradeable;
        v.despawnable = despawnable;
        v.keepOnDeath = keepOnDeath;
        v.locked = locked;
        v.noDrop = noDrop;
        v.noManualEquip = noManualEquip;
        v.obtainVia = obtainVia;
        v.cooldownTicks = Math.max(0, cooldownTicks);
        v.durabilityMax = Math.max(0, durabilityMax);
        v.durabilityDamagePerUse = Math.max(1, durabilityDamagePerUse);
        v.durabilityLoreFormat = durabilityLoreFormat == null ? "" : durabilityLoreFormat;
        v.breakActions = copy(breakActions);
        Map<ItemUse, List<String>> actions = new EnumMap<>(ItemUse.class);
        for (ItemUse use : ItemUse.values()) {
            actions.put(use, copy(useActions.get(use)));
        }
        v.useActions = Collections.unmodifiableMap(actions);
        v.useCallbacks = Collections.unmodifiableMap(new EnumMap<>(useCallbacks));
        v.shiftOverridesGeneric = shiftOverridesGeneric;
        v.interactRequirements = copy(interactRequirements);
        v.denyActions = copy(denyActions);
        v.pickupActions = copy(pickupActions);
        v.dropActions = copy(dropActions);
        v.heldEffectsMainhand = copy(heldEffectsMainhand);
        v.heldEffectsOffhand = copy(heldEffectsOffhand);
        v.heldEffectsArmor = copy(heldEffectsArmor);
        String slot = equipmentSlot;
        if (slot == null && v.appearance != null) {
            slot = v.appearance.equipmentSlot();
        }
        if (slot == null && v.builderItem != null) {
            slot = v.builderItem.equipmentSlotName();
        }
        v.equipmentSlot = slot == null || slot.isBlank() ? "" : slot.trim();
        v.recipe = recipe;
        v.onApply = onApply;
        v.onRemove = onRemove;
        return v;
    }

    private static List<String> copy(List<String> list) {
        return list == null || list.isEmpty() ? List.of() : List.copyOf(list);
    }

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
}
