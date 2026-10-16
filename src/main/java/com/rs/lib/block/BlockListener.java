package com.rs.lib.block;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clicks on and protection of the clickable blocks of every plugin. Shared and registered once
 * by RsLib. Each handler leaves after one emptiness check when no block is registered, and
 * after one map lookup when the event's block is not one.
 */
final class BlockListener implements Listener {

    private final BlockIndex index;

    BlockListener(BlockIndex index) {
        this.index = index;
    }

    /**
     * Lowest priority and even when cancelled: the click is the block's, whatever protection
     * plugins said, and every later listener sees it cancelled.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        RsBlock block = index.at(clicked);
        if (block == null) {
            return;
        }
        event.setCancelled(true);
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_BLOCK;
        if (!left && action != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (index.repeated(player.getUniqueId(), block.pos(), left)) {
            return;
        }
        block.click(new ClickedBlock(player, clicked, left));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (index.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        index.spare(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        index.spare(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (index.anyOf(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (index.anyOf(event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (index.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * An entity changing the block: an enderman taking it, a falling block landing on it (or the
     * block itself starting to fall), a silverfish, a sheep eating it, a ravager or a wither.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (index.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Water or lava flowing into the block (which would wash a torch or a button away). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (index.at(event.getToBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Ice or snow melting, coral dying, farmland drying, fire going out. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (index.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * The block turning into another: water freezing, concrete powder setting, lava meeting water,
     * frost walker or a snow golem's trail.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (index.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /** Grass, mycelium, vines, fire or sculk spreading onto the block. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (index.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (index.at(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        index.forget(event.getPlayer().getUniqueId());
    }
}
