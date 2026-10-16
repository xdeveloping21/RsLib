package com.rs.lib.item;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Taking a plugin's registered items back from a player: {@link ItemRegistry#take} and redeem clicks. */
final class ItemTaking {

    private ItemTaking() {
    }

    /** See {@link ItemRegistry#take}. */
    static int take(ItemRegistry registry, Player player, String id, int amount) {
        if (player == null || id == null || amount <= 0) {
            return 0;
        }
        PlayerInventory inventory = player.getInventory();
        int taken = 0;
        for (int slot = 0; slot < inventory.getSize() && taken < amount; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (registry.is(stack, id)) {
                int use = Math.min(stack.getAmount(), amount - taken);
                taken += use;
                inventory.setItem(slot, reduced(stack, use));
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (taken < amount && registry.is(cursor, id)) {
            int use = Math.min(cursor.getAmount(), amount - taken);
            taken += use;
            player.setItemOnCursor(reduced(cursor, use));
        }
        return taken;
    }

    /**
     * Takes what one redeem click consumes and returns copies of it: one unit or the hand stack
     * from {@code hand}, or every {@code id} in the inventory then the cursor up to the limit.
     */
    static List<ItemStack> consume(ItemRegistry registry, Player player, String id, EquipmentSlot hand, RedeemSpec spec) {
        List<ItemStack> taken = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        switch (spec.mode()) {
            case SINGLE, HAND_STACK -> {
                ItemStack stack = inventory.getItem(hand);
                if (!registry.is(stack, id)) {
                    return taken;
                }
                int use = spec.mode() == RedeemSpec.Mode.SINGLE ? 1 : stack.getAmount();
                ItemStack copy = stack.clone();
                copy.setAmount(use);
                taken.add(copy);
                inventory.setItem(hand, reduced(stack, use));
            }
            case ALL_MATCHING -> {
                int limit = spec.limit() <= 0 ? Integer.MAX_VALUE : spec.limit();
                int count = 0;
                for (int slot = 0; slot < inventory.getSize() && count < limit; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (registry.is(stack, id)) {
                        int use = Math.min(stack.getAmount(), limit - count);
                        ItemStack copy = stack.clone();
                        copy.setAmount(use);
                        taken.add(copy);
                        count += use;
                        inventory.setItem(slot, reduced(stack, use));
                    }
                }
                ItemStack cursor = player.getItemOnCursor();
                if (count < limit && registry.is(cursor, id)) {
                    int use = Math.min(cursor.getAmount(), limit - count);
                    ItemStack copy = cursor.clone();
                    copy.setAmount(use);
                    taken.add(copy);
                    player.setItemOnCursor(reduced(cursor, use));
                }
            }
        }
        return taken;
    }

    private static ItemStack reduced(ItemStack stack, int by) {
        if (stack.getAmount() <= by) {
            return null;
        }
        ItemStack rest = stack.clone();
        rest.setAmount(stack.getAmount() - by);
        return rest;
    }
}
