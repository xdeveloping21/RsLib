package com.rs.lib.region;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

/**
 * Wand clicks of every plugin. Shared and registered once by RsLib.
 *
 * <p>Runs at LOWEST and also on clicks already cancelled, so protection plugins cannot stop a
 * wand and a wand never breaks or uses a block. Leaves early in layers: no plugin with a
 * registered spec, not a click on a block with the main hand, no item meta; only then is the
 * item's data read, once per plugin with a registered spec.</p>
 */
final class SelectionWandListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (SelectionManager.noneArmed()) {
            return;
        }
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_BLOCK;
        if ((!left && action != Action.RIGHT_CLICK_BLOCK) || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        Block block = event.getClickedBlock();
        if (item == null || block == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (SelectionManager manager : SelectionManager.armed()) {
            String specId = manager.specIdIn(pdc);
            if (specId != null) {
                event.setCancelled(true);
                manager.handleWandClick(event.getPlayer(), specId, left, block.getLocation());
                return;
            }
        }
    }
}
