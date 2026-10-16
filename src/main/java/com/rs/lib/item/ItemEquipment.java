package com.rs.lib.item;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import com.rs.lib.util.InvUtil;

/**
 * Items one plugin put on players with {@link ItemRegistry#apply}: what was in the slot is kept
 * in {@code data/equipment-backup.yml} and given back on {@link ItemRegistry#unapply}, when the
 * player leaves, when the plugin stops, or on the next join after a crash.
 */
final class ItemEquipment {

    private final ItemRegistry registry;
    private final EquipmentBackup backup;
    private final Logger logger;

    ItemEquipment(ItemRegistry registry, EquipmentBackup backup, Logger logger) {
        this.registry = registry;
        this.backup = backup;
        this.logger = logger;
    }

    /** See {@link ItemRegistry#apply}. */
    boolean apply(Player player, String id, EquipmentSlot slot) {
        if (player == null || !player.isOnline() || slot == null) {
            return false;
        }
        ItemDef def = registry.def(id);
        ItemStack stack = registry.create(id, player);
        if (def == null || stack == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        UUID uuid = player.getUniqueId();
        if (backup.get(uuid, slot) == null) {
            ItemStack real = inventory.getItem(slot);
            backup.put(uuid, slot, id.trim(), empty(real) ? null : ItemSerializer.serializeBase64(real));
        } else {
            EquipmentBackup.Saved saved = backup.get(uuid, slot);
            backup.put(uuid, slot, id.trim(), saved.item());
        }
        inventory.setItem(slot, stack);
        BiConsumer<Player, ItemStack> hook = def.onApply();
        if (hook != null) {
            hook.accept(player, stack);
        }
        return true;
    }

    /** See {@link ItemRegistry#unapply}. */
    boolean unapply(Player player, String id) {
        if (player == null || id == null) {
            return false;
        }
        boolean any = false;
        for (Map.Entry<EquipmentSlot, EquipmentBackup.Saved> entry : backup.all(player.getUniqueId()).entrySet()) {
            if (entry.getValue().id().equals(id.trim())) {
                restore(player, entry.getKey(), entry.getValue());
                any = true;
            }
        }
        return any;
    }

    /** Gives back every backed-up slot of {@code player} (leaving, plugin stopping, join after a crash). */
    void restoreAll(Player player) {
        for (Map.Entry<EquipmentSlot, EquipmentBackup.Saved> entry : backup.all(player.getUniqueId()).entrySet()) {
            restore(player, entry.getKey(), entry.getValue());
        }
    }

    private void restore(Player player, EquipmentSlot slot, EquipmentBackup.Saved saved) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(slot);
        ItemStack real = null;
        if (saved.item() != null) {
            try {
                real = ItemSerializer.deserializeBase64(saved.item());
            } catch (RuntimeException unreadable) {
                logger.warning("The backed-up " + slot + " item of " + player.getName() + " could not be read: " + unreadable);
                backup.remove(player.getUniqueId(), slot);
                return;
            }
        }
        boolean own = saved.id().equals(registry.idOf(current)) || locked(current);
        if (EquipmentBackup.target(empty(current), own) == EquipmentBackup.Target.SLOT) {
            inventory.setItem(slot, real);
        } else if (real != null) {
            InvUtil.giveItems(player, real);
        }
        backup.remove(player.getUniqueId(), slot);
        ItemDef def = registry.def(saved.id());
        if (def != null && def.onRemove() != null && own) {
            def.onRemove().accept(player, current);
        }
    }

    void handleQuit(Player player) {
        try {
            restoreAll(player);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not give back the equipment of " + player.getName(), e);
        }
    }

    void handleJoin(Player player) {
        if (!backup.all(player.getUniqueId()).isEmpty()) {
            handleQuit(player);
        }
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    /** Whether {@code stack} carries this plugin's locked stamp. */
    private boolean locked(ItemStack stack) {
        if (empty(stack) || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && registry.stamped(meta.getPersistentDataContainer(), ItemRegistry.TAG_LOCKED);
    }
}
