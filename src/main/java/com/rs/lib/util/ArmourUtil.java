package com.rs.lib.util;

import java.util.Locale;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Which armour slot an item goes in, read from its material name so materials added by newer
 * Minecraft versions are understood without a list.
 *
 * <ul>
 *   <li>Head: names ending in {@code _HELMET}, {@code _HEAD} or {@code _SKULL}, and
 *       {@code CARVED_PUMPKIN}.</li>
 *   <li>Chest: names ending in {@code _CHESTPLATE}, and {@code ELYTRA}.</li>
 *   <li>Legs: names ending in {@code _LEGGINGS}.</li>
 *   <li>Feet: names ending in {@code _BOOTS}.</li>
 * </ul>
 *
 * <p>Pure apart from reading the items; the {@link Player} methods belong on the player's
 * thread.</p>
 */
public final class ArmourUtil {

    private ArmourUtil() {
    }

    /** The armour slot of {@code item}, or null when it is null or not armour. */
    public static EquipmentSlot slotOf(ItemStack item) {
        return item == null ? null : slotOfName(item.getType().name());
    }

    /** True when {@code item} goes in an armour slot. */
    public static boolean isArmour(ItemStack item) {
        return slotOf(item) != null;
    }

    /** True when each of the four armour slots holds an item made for that slot (any material). */
    public static boolean isWearingFullSet(Player player) {
        return isWearingFullSet(player, "");
    }

    /**
     * True when each of the four armour slots holds an item made for that slot whose material
     * name starts with {@code materialPrefix}, in any case ({@code "diamond"} matches
     * {@code DIAMOND_HELMET} and so on). An empty prefix accepts any material.
     */
    public static boolean isWearingFullSet(Player player, String materialPrefix) {
        String prefix = materialPrefix == null ? "" : materialPrefix.toUpperCase(Locale.ROOT);
        PlayerInventory inventory = player.getInventory();
        return wears(inventory.getHelmet(), EquipmentSlot.HEAD, prefix)
                && wears(inventory.getChestplate(), EquipmentSlot.CHEST, prefix)
                && wears(inventory.getLeggings(), EquipmentSlot.LEGS, prefix)
                && wears(inventory.getBoots(), EquipmentSlot.FEET, prefix);
    }

    private static boolean wears(ItemStack item, EquipmentSlot slot, String prefix) {
        return item != null && slotOf(item) == slot && item.getType().name().startsWith(prefix);
    }

    /** The armour slot for a material name, or null. */
    static EquipmentSlot slotOfName(String name) {
        if (name.endsWith("_HELMET") || name.endsWith("_HEAD") || name.endsWith("_SKULL")
                || name.equals("CARVED_PUMPKIN")) {
            return EquipmentSlot.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return EquipmentSlot.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }
}
