package com.rs.lib.item;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.rs.lib.Ph;
import com.rs.lib.text.RsText;

import net.kyori.adventure.text.Component;

/**
 * The {@code custom-durability} of one plugin's items: what is left is kept in the stack's
 * persistent data ({@code snlib_durability}), with the index of its lore line
 * ({@code snlib_durability_line}) kept up to date, and the break actions run by the call that
 * takes it to 0.
 */
final class ItemDurability {

    private final ItemRegistry registry;
    private final NamespacedKey keyDurability;
    private final NamespacedKey keyDurabilityLine;

    ItemDurability(ItemRegistry registry, NamespacedKey keyDurability, NamespacedKey keyDurabilityLine) {
        this.registry = registry;
        this.keyDurability = keyDurability;
        this.keyDurabilityLine = keyDurabilityLine;
    }

    /** Stamps a new stack of {@code def} with its full durability and adds its lore line to {@code meta}. */
    void stamp(ItemMeta meta, PersistentDataContainer pdc, ItemDef def) {
        if (def.durabilityMax() <= 0) {
            return;
        }
        pdc.set(keyDurability, PersistentDataType.INTEGER, def.durabilityMax());
        if (!def.durabilityLoreFormat().isEmpty()) {
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            pdc.set(keyDurabilityLine, PersistentDataType.INTEGER, lore.size());
            lore.add(line(def, def.durabilityMax()));
            meta.lore(lore);
        }
    }

    private static Component line(ItemDef def, int left) {
        String text = RsText.applyLocals(def.durabilityLoreFormat(), Ph.of("durability", left),
                Ph.of("max_durability", def.durabilityMax()));
        return RsItem.plain(RsText.colorUncached(text));
    }

    /** See {@link ItemRegistry#durability}. */
    int left(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return -1;
        }
        ItemMeta meta = stack.getItemMeta();
        Integer left = meta == null ? null : meta.getPersistentDataContainer().get(keyDurability, PersistentDataType.INTEGER);
        return left == null ? -1 : left;
    }

    /** See {@link ItemRegistry#damage(ItemStack, int)}. */
    int damage(ItemStack stack, int amount) {
        int left = left(stack);
        if (left < 0) {
            return -1;
        }
        left = Math.max(0, left - Math.max(0, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(keyDurability, PersistentDataType.INTEGER, left);
        ItemDef def = registry.def(registry.idOf(stack));
        Integer line = meta.getPersistentDataContainer().get(keyDurabilityLine, PersistentDataType.INTEGER);
        if (def != null && line != null && !def.durabilityLoreFormat().isEmpty() && meta.hasLore()) {
            List<Component> lore = new ArrayList<>(meta.lore());
            if (line >= 0 && line < lore.size()) {
                lore.set(line, line(def, left));
                meta.lore(lore);
            }
        }
        stack.setItemMeta(meta);
        return left;
    }

    /** See {@link ItemRegistry#damage(Player, ItemStack, int)}. */
    int damage(Player player, ItemStack stack, int amount) {
        if (player == null || stack == null || registry.idOf(stack) == null) {
            return -1;
        }
        PlayerInventory inventory = player.getInventory();
        int found = slotOf(inventory, stack);
        ItemStack working = found < 0 ? stack : inventory.getItem(found);
        int before = left(working);
        int left = damage(working, amount);
        if (left < 0) {
            return -1;
        }
        boolean broke = breaks(before, left);
        if (found >= 0) {
            inventory.setItem(found, broke ? null : working);
        }
        if (broke) {
            ItemDef def = registry.def(registry.idOf(working));
            if (def != null) {
                registry.runActions(player, def.breakActions(), null, null);
            }
        }
        return left;
    }

    /** The inventory slot holding {@code stack}: the same object first, then an equal stack; -1 when none. */
    static int slotOf(Inventory inventory, ItemStack stack) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == stack) {
                return slot;
            }
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (stack.equals(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** Whether a damage call took durability from above 0 to 0: only that call runs the break flow. */
    static boolean breaks(int before, int after) {
        return before > 0 && after == 0;
    }

    /**
     * Applies one successful use to the item in {@code hand}: durability goes down and, when this
     * use breaks it, the break actions run and the hand is emptied.
     */
    void used(Player player, EquipmentSlot hand, ItemDef def, ClickType click, Boolean onBlock) {
        if (def.durabilityMax() <= 0) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = inventory.getItem(hand);
        int before = left(stack);
        int left = damage(stack, def.durabilityDamagePerUse());
        if (left < 0) {
            return;
        }
        if (breaks(before, left)) {
            inventory.setItem(hand, null);
            registry.runActions(player, def.breakActions(), click, onBlock);
        } else {
            inventory.setItem(hand, stack);
        }
    }
}
