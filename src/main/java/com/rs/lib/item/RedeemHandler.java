package com.rs.lib.item;

import java.util.List;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** What happens when a player redeems a registered item (see {@link ItemRegistry#redeemable}). */
@FunctionalInterface
public interface RedeemHandler {

    /**
     * Runs on the main thread after the items were taken.
     *
     * @param amount   how many units were consumed
     * @param consumed copies of the stacks taken, with the amounts taken
     */
    void redeem(Player player, int amount, List<ItemStack> consumed);
}
