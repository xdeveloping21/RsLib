package com.rs.lib.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Giving items without ever losing any. Call on the player's thread. */
public final class InvUtil {

    /** What happens to the items that do not fit in the player's inventory. */
    public enum Overflow {
        /** Dropped at the player's feet, split into normal stacks. What {@link #giveItems(Player, ItemStack...)} does. */
        DROP,
        /**
         * All or nothing: unless every item fits, nothing is given and the inventory is left
         * exactly as it was.
         */
        FAIL,
        /** What fits is given; the rest is handed back to the caller, who decides where it goes. */
        RETURN
    }

    private InvUtil() {
    }

    /**
     * Adds {@code items} to the player's inventory and drops at the player's feet whatever does
     * not fit, split into normal stacks. Null, air and empty stacks are skipped. The given
     * stacks are not modified.
     */
    public static void giveItems(Player player, ItemStack... items) {
        giveItems(player, Overflow.DROP, items);
    }

    /**
     * Adds {@code items} to the player's inventory; {@code overflow} says what happens to what
     * does not fit. Null, air and empty stacks are skipped and the given stacks are not modified.
     *
     * @return the items that were neither placed nor dropped, as new stacks: always empty with
     *         {@link Overflow#DROP}; with {@link Overflow#FAIL} empty when everything was given,
     *         otherwise a copy of every item (nothing was given); with {@link Overflow#RETURN}
     *         what did not fit (a stack may be larger than its material's stack size)
     */
    public static List<ItemStack> giveItems(Player player, Overflow overflow, ItemStack... items) {
        Objects.requireNonNull(overflow, "overflow");
        if (items == null || items.length == 0) {
            return new ArrayList<>(0);
        }
        List<ItemStack> copies = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            if (!empty(item)) {
                copies.add(item.clone());
            }
        }
        if (copies.isEmpty()) {
            return new ArrayList<>(0);
        }
        PlayerInventory inventory = player.getInventory();
        // addItem changes the amounts of the stacks it receives and getStorageContents may
        // return live views, so the inventory is copied deeply before and the caller's
        // items are copied again for anything handed back.
        ItemStack[] before = overflow == Overflow.FAIL ? deepCopy(inventory.getStorageContents()) : null;
        List<ItemStack> originals = overflow == Overflow.FAIL ? cloneAll(copies) : null;
        Map<Integer, ItemStack> leftover = inventory.addItem(copies.toArray(new ItemStack[0]));
        if (leftover == null || leftover.isEmpty()) {
            return new ArrayList<>(0);
        }
        switch (overflow) {
            case FAIL -> {
                inventory.setStorageContents(before);
                return originals;
            }
            case RETURN -> {
                List<ItemStack> rest = new ArrayList<>(leftover.size());
                for (ItemStack stack : leftover.values()) {
                    if (stack != null && stack.getAmount() > 0) {
                        rest.add(stack.clone());
                    }
                }
                return rest;
            }
            default -> {
                drop(player, leftover.values());
                return new ArrayList<>(0);
            }
        }
    }

    /** Drops {@code stacks} at the player's feet, each split into normal stacks. */
    private static void drop(Player player, Iterable<ItemStack> stacks) {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        for (ItemStack rest : stacks) {
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

    /** Null, air or no amount. Compares the three airs directly: {@code isAir()} goes through the registries. */
    private static boolean empty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }
        Material type = item.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private static ItemStack[] deepCopy(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static List<ItemStack> cloneAll(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            out.add(stack.clone());
        }
        return out;
    }
}
