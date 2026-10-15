package com.rs.lib.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Anti-theft for menu stacks, registered once by RsLib for the whole server. A stack stamped
 * with {@value GuiManager#ITEM_TAG} (or SnLib's {@value GuiManager#LEGACY_ITEM_TAG}), under any
 * namespace, only belongs in the cells of a menu; anywhere else it is deleted:
 * <ol>
 *     <li>clicked outside a menu's cells, held on the cursor, pulled with a number key or the
 *     off-hand key;</li>
 *     <li>dragged (the drag is cancelled);</li>
 *     <li>used from the hand;</li>
 *     <li>swapped between hands;</li>
 *     <li>dropped (the dropped entity is removed, the drop is not cancelled, so it does not come
 *     back);</li>
 *     <li>in the drops of a death;</li>
 *     <li>spawned as an item entity in the world by any other way.</li>
 * </ol>
 */
final class GuiProtection implements Listener {

    /** Holder class name of SnLib's menus, which keep their own stacks. */
    private static final String LEGACY_HOLDER = "SnGuiHolder";

    /** True when {@code stack} carries a menu stamp. */
    static boolean marked(ItemStack stack) {
        if (GuiListener.empty(stack) || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if (isStampName(key.getKey())) {
                return true;
            }
        }
        return false;
    }

    /** True for the key names of a menu stamp. */
    static boolean isStampName(String keyName) {
        return GuiManager.ITEM_TAG.equals(keyName) || GuiManager.LEGACY_ITEM_TAG.equals(keyName);
    }

    /** True when {@code inventory} is a menu (RsLib's, or SnLib's by its holder's class name). */
    static boolean isMenu(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder(false);
        return holder instanceof RsGuiHolder || (holder != null && isLegacyHolder(holder.getClass()));
    }

    static boolean isLegacyHolder(Class<?> type) {
        return LEGACY_HOLDER.equals(type.getSimpleName());
    }

    // ------------------------------------------------------------------ 1: clicks, number key, F

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        HumanEntity who = event.getWhoClicked();
        boolean taken = false;
        Inventory clicked = event.getClickedInventory();
        ItemStack current = event.getCurrentItem();
        if (marked(current) && !isMenu(clicked)) {
            event.setCurrentItem(null);
            taken = true;
        }
        if (marked(event.getCursor())) {
            who.setItemOnCursor(null);
            taken = true;
        }
        PlayerInventory own = who.getInventory();
        if (event.getClick() == ClickType.NUMBER_KEY) {
            int button = event.getHotbarButton();
            if (button >= 0 && marked(own.getItem(button))) {
                own.setItem(button, null);
                taken = true;
            }
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND && marked(own.getItemInOffHand())) {
            own.setItemInOffHand(null);
            taken = true;
        }
        if (taken) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ 2: drags

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (marked(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ 3: use

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL || !marked(event.getItem())) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        Player player = event.getPlayer();
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(null);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    // ------------------------------------------------------------------ 4: swap hands

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        boolean fromOffHand = marked(event.getMainHandItem());
        boolean fromMainHand = marked(event.getOffHandItem());
        if (!fromOffHand && !fromMainHand) {
            return;
        }
        event.setCancelled(true);
        PlayerInventory own = event.getPlayer().getInventory();
        if (fromOffHand) {
            own.setItemInOffHand(null);
        }
        if (fromMainHand) {
            own.setItemInMainHand(null);
        }
    }

    // ------------------------------------------------------------------ 5: drop

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        Item dropped = event.getItemDrop();
        if (marked(dropped.getItemStack())) {
            dropped.remove();
        }
    }

    // ------------------------------------------------------------------ 6: death

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(GuiProtection::marked);
    }

    // ------------------------------------------------------------------ 7: item entities

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(ItemSpawnEvent event) {
        if (marked(event.getEntity().getItemStack())) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }
}
