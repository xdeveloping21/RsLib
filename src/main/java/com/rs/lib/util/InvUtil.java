package com.rs.lib.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Giving items without ever losing any. Call on the player's thread. */
public final class InvUtil {

    private InvUtil() {
    }

    /**
     * Adds {@code items} to the player's inventory and drops at the player's feet whatever does
     * not fit, split into normal stacks. Null, air and empty stacks are skipped. The given
     * stacks are not modified.
     */
    public static void giveItems(Player player, ItemStack... items) {
        if (items == null || items.length == 0) {
            return;
        }
        List<ItemStack> copies = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                copies.add(item.clone());
            }
        }
        if (copies.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(copies.toArray(new ItemStack[0]));
        if (leftover.isEmpty()) {
            return;
        }
        Location feet = player.getLocation();
        World world = feet.getWorld();
        for (ItemStack rest : leftover.values()) {
            int max = Math.max(1, rest.getMaxStackSize());
            int left = rest.getAmount();
            while (left > 0) {
                ItemStack drop = rest.clone();
                drop.setAmount(Math.min(left, max));
                left -= drop.getAmount();
                world.dropItem(feet, drop);
            }
        }
    }
}
