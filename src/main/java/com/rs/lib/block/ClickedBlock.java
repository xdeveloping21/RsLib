package com.rs.lib.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * One click on a clickable block, handed to its handler on the player's thread.
 *
 * @param player who clicked
 * @param block  the block clicked
 * @param left   true for a left click, false for a right click
 */
public record ClickedBlock(Player player, Block block, boolean left) {

    /** True for a right click. */
    public boolean right() {
        return !left;
    }

    /** True when the player was sneaking, so one block can offer a second action behind shift. */
    public boolean sneaking() {
        return player.isSneaking();
    }
}
