package com.rs.lib.item;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import com.rs.lib.item.internal.DeathStash;

/**
 * The one listener RsLib registers for the registered items of every plugin. An item is found
 * by its id stamp under the namespace of a plugin that has an {@link ItemRegistry}; stacks of
 * any other plugin are left alone.
 *
 * <p>Inventory views are reached only through the event's own methods ({@code getInventory()}
 * is the top inventory): {@code InventoryView} changed from a class to an interface between the
 * supported versions.</p>
 */
final class ItemListener implements Listener {

    /** Player inventory slots of the armour and the off hand. */
    static final int FEET = 36;
    static final int LEGS = 37;
    static final int CHEST = 38;
    static final int HEAD = 39;
    static final int OFF_HAND = 40;

    private final RedeemStamp redeemed = new RedeemStamp();

    // ------------------------------------------------------------------ finding items

    /** A registered item found on a stack: its registry, id, definition (null once unregistered) and stamps. */
    record Found(ItemRegistry registry, String id, ItemDef def, PersistentDataContainer pdc) {

        boolean locked() {
            return registry.stamped(pdc, ItemRegistry.TAG_LOCKED) || def != null && def.locked();
        }

        boolean refusesDrop() {
            return locked() || registry.stamped(pdc, ItemRegistry.TAG_NO_DROP) || def != null && def.refusesDrop();
        }

        /** Manual equipping is refused: no-manual-equip, COMMAND_ONLY or locked. */
        boolean noManualEquip() {
            return locked() || registry.stamped(pdc, ItemRegistry.TAG_NO_MANUAL_EQUIP)
                    || registry.stamped(pdc, ItemRegistry.TAG_OBTAIN_VIA) || def != null && def.noManualEquip();
        }

        boolean moveable() {
            return !locked() && (def == null || def.moveable());
        }

        boolean tradeable() {
            return def == null || def.tradeable();
        }

        EquipmentSlot declaredSlot() {
            return def == null ? null : def.equipmentSlot();
        }

        /** The death-stash kind when kept through death, or null. */
        String keptKind() {
            if (locked()) {
                return DeathStash.LOCKED;
            }
            if (registry.stamped(pdc, ItemRegistry.TAG_KEEP_ON_DEATH) || def != null && def.keepOnDeath()) {
                return DeathStash.KEEP_ON_DEATH;
            }
            return null;
        }
    }

    static Found find(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (ItemRegistry registry : ItemRegistry.all()) {
            String id = pdc.get(registry.keyId(), org.bukkit.persistence.PersistentDataType.STRING);
            if (id != null) {
                return new Found(registry, id, registry.def(id), pdc);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ pure rules

    /** What the redeem handler receives: an immutable snapshot of the stacks taken. */
    static List<ItemStack> snapshot(List<ItemStack> taken) {
        return List.copyOf(taken);
    }

    /**
     * Whether putting an item into the equipment slot {@code target} by hand is refused:
     * never for the main hand or no slot; always when manual equipping is refused; otherwise
     * when the item declares another slot.
     */
    static boolean equipRefused(EquipmentSlot target, EquipmentSlot declared, boolean noManualEquip) {
        if (target == null || target == EquipmentSlot.HAND) {
            return false;
        }
        if (noManualEquip) {
            return true;
        }
        return declared != null && declared != target;
    }

    /** The equipment slot of a player inventory slot index (36-40), or null. */
    static EquipmentSlot playerSlot(int slot) {
        return switch (slot) {
            case FEET -> EquipmentSlot.FEET;
            case LEGS -> EquipmentSlot.LEGS;
            case CHEST -> EquipmentSlot.CHEST;
            case HEAD -> EquipmentSlot.HEAD;
            case OFF_HAND -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    /** The equipment slot of a raw slot of the player's own inventory view (crafting on top), or null. */
    static EquipmentSlot craftingViewSlot(int raw) {
        return switch (raw) {
            case 5 -> EquipmentSlot.HEAD;
            case 6 -> EquipmentSlot.CHEST;
            case 7 -> EquipmentSlot.LEGS;
            case 8 -> EquipmentSlot.FEET;
            case 45 -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    /** Remembers the tick a player redeemed an id, so the second hand of the same click does not redeem again. */
    static final class RedeemStamp {

        private static final int MAX = 1024;
        private final Map<String, Integer> last = new HashMap<>();

        /** True the first time in {@code tick} for this player and id. */
        boolean first(UUID player, String id, int tick) {
            if (last.size() >= MAX) {
                last.clear();
            }
            Integer previous = last.put(player + ":" + id, tick);
            return previous == null || previous != tick;
        }
    }

    @SuppressWarnings("deprecation")
    private static EquipmentSlot vanillaSlot(ItemStack stack) {
        try {
            return stack.getType().getEquipmentSlot();
        } catch (RuntimeException unknown) {
            return EquipmentSlot.HAND;
        }
    }

    private static boolean isArmour(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST || slot == EquipmentSlot.LEGS
                || slot == EquipmentSlot.FEET;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (ItemRegistry.none() || event.getAction() == Action.PHYSICAL) {
            return;
        }
        ItemStack item = event.getItem();
        EquipmentSlot hand = event.getHand();
        if (item == null || hand == null) {
            return;
        }
        Found found = find(item);
        if (found == null) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean block = action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK;
        // (0) right-click auto-equip into a slot the item may not go to.
        if (right) {
            EquipmentSlot target = vanillaSlot(item);
            if (isArmour(target) && equipRefused(target, found.declaredSlot(), found.noManualEquip())) {
                event.setUseItemInHand(Event.Result.DENY);
                return;
            }
        }
        ItemDef def = found.def();
        if (def == null) {
            return;
        }
        ItemRegistry registry = found.registry();
        ClickType click = right ? (player.isSneaking() ? ClickType.SHIFT_RIGHT : ClickType.RIGHT)
                : (player.isSneaking() ? ClickType.SHIFT_LEFT : ClickType.LEFT);
        // (1) cooldown, silent.
        if (def.cooldownTicks() > 0 && !registry.context().cooldowns()
                .tryUseTicks(player.getUniqueId(), "item:" + found.id(), def.cooldownTicks())) {
            return;
        }
        // (2) requirements.
        if (!def.interactRequirement().test(player, registry.requirementResolver(player))) {
            registry.runActions(player, def.denyActions(), click, block);
            return;
        }
        // (2.5) redeem.
        if (right && redeem(event, player, hand, found)) {
            return;
        }
        // (3) the uses.
        boolean ran = false;
        for (ItemUse use : ItemUse.dispatch(right, block, player.isSneaking(), def.shiftOverridesGeneric(), def::active)) {
            List<String> list = def.actions(use);
            if (!list.isEmpty()) {
                registry.runActions(player, list, click, block);
                ran = true;
            }
            BiConsumer<Player, ItemStack> callback = def.callback(use);
            if (callback != null) {
                ran = true;
                try {
                    callback.accept(player, item);
                } catch (RuntimeException e) {
                    registry.logger().log(Level.WARNING, "Item '" + found.id() + "' " + use + " callback failed", e);
                }
            }
        }
        // (4) durability, for a use that did something.
        if (ran) {
            registry.used(player, hand, def, click, block);
        }
    }

    /** Redeems when the item is redeemable; true when the click is used up by it. */
    private boolean redeem(PlayerInteractEvent event, Player player, EquipmentSlot hand, Found found) {
        ItemRegistry.RedeemEntry entry = found.registry().redeemEntry(found.id());
        if (entry == null || event.useItemInHand() == Event.Result.DENY) {
            return false;
        }
        Block clicked = event.getClickedBlock();
        if (clicked != null && entry.spec().blockedOn().contains(clicked.getType())) {
            return false;
        }
        event.setCancelled(true);
        if (!redeemed.first(player.getUniqueId(), found.id(), Bukkit.getCurrentTick())) {
            return true;
        }
        List<ItemStack> taken = found.registry().consume(player, found.id(), hand, entry.spec());
        int total = 0;
        for (ItemStack stack : taken) {
            total += stack.getAmount();
        }
        if (total > 0) {
            try {
                entry.handler().redeem(player, total, snapshot(taken));
            } catch (RuntimeException e) {
                found.registry().logger().log(Level.WARNING, "Redeeming '" + found.id() + "' failed for "
                        + player.getName(), e);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ dropping, placing, swapping

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (ItemRegistry.none()) {
            return;
        }
        Found found = find(event.getItemDrop().getItemStack());
        if (found == null) {
            return;
        }
        if (found.refusesDrop()) {
            event.setCancelled(true);
        } else if (found.def() != null) {
            found.registry().runActions(event.getPlayer(), found.def().dropActions(), null, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (ItemRegistry.none()) {
            return;
        }
        Found found = find(event.getItemInHand());
        if (found != null && found.def() != null && !found.def().placeable()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (ItemRegistry.none()) {
            return;
        }
        // The item going to the off hand was in the main hand, and the other way round.
        Found toOffHand = find(event.getOffHandItem());
        Found toMainHand = find(event.getMainHandItem());
        if (toOffHand != null && (toOffHand.locked()
                || equipRefused(EquipmentSlot.OFF_HAND, toOffHand.declaredSlot(), toOffHand.noManualEquip()))) {
            event.setCancelled(true);
        } else if (toMainHand != null && toMainHand.locked()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (ItemRegistry.none() || !event.getItem().hasItemMeta()) {
            return;
        }
        Found found = find(event.getItem());
        if (found != null && found.locked()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseArmour(BlockDispenseArmorEvent event) {
        if (ItemRegistry.none()) {
            return;
        }
        ItemStack item = event.getItem();
        Found found = find(item);
        if (found != null && equipRefused(vanillaSlot(item), found.declaredSlot(), found.noManualEquip())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ inventories

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (ItemRegistry.none() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType click = event.getClick();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        ItemStack hotbar = click == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                ? player.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offHand = click == ClickType.SWAP_OFFHAND ? player.getInventory().getItemInOffHand() : null;
        Found fCurrent = find(current);
        Found fCursor = find(cursor);
        Found fHotbar = find(hotbar);
        Found fOffHand = find(offHand);
        if (fCurrent == null && fCursor == null && fHotbar == null && fOffHand == null) {
            return;
        }
        // Moving at all.
        if (!moveable(fCurrent) || !moveable(fCursor) || !moveable(fHotbar) || !moveable(fOffHand)) {
            event.setCancelled(true);
            return;
        }
        Inventory top = event.getInventory();
        Inventory clicked = event.getClickedInventory();
        boolean intoTop = clicked != null && clicked == top;
        // Trading: nothing untradeable goes into a villager.
        if (top.getType() == InventoryType.MERCHANT) {
            boolean shiftUp = click.isShiftClick() && clicked != null && clicked != top;
            if (intoTop && (!tradeable(fCursor) || !tradeable(fHotbar) || !tradeable(fOffHand))
                    || shiftUp && !tradeable(fCurrent)) {
                event.setCancelled(true);
                return;
            }
        }
        // Equipment slots of the player's own inventory.
        if (clicked instanceof PlayerInventory) {
            EquipmentSlot target = playerSlot(event.getSlot());
            if (target != null) {
                Found placed = click == ClickType.NUMBER_KEY ? fHotbar
                        : click == ClickType.SWAP_OFFHAND ? fOffHand : fCursor;
                if (placed != null && equipRefused(target, placed.declaredSlot(), placed.noManualEquip())) {
                    event.setCancelled(true);
                    return;
                }
            }
            if (click == ClickType.SWAP_OFFHAND && event.getSlot() != OFF_HAND && fCurrent != null
                    && equipRefused(EquipmentSlot.OFF_HAND, fCurrent.declaredSlot(), fCurrent.noManualEquip())) {
                event.setCancelled(true);
                return;
            }
            // Shift-click auto-equip from the player's own inventory view.
            if (click.isShiftClick() && top.getType() == InventoryType.CRAFTING && fCurrent != null && target == null) {
                EquipmentSlot auto = vanillaSlot(current);
                if (auto != EquipmentSlot.HAND && empty(player.getInventory().getItem(auto))
                        && equipRefused(auto, fCurrent.declaredSlot(), fCurrent.noManualEquip())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private static boolean moveable(Found found) {
        return found == null || found.moveable();
    }

    private static boolean tradeable(Found found) {
        return found == null || found.tradeable();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (ItemRegistry.none()) {
            return;
        }
        Found found = find(event.getOldCursor());
        if (found == null) {
            return;
        }
        if (!found.moveable()) {
            event.setCancelled(true);
            return;
        }
        Inventory top = event.getInventory();
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && top.getType() == InventoryType.MERCHANT && !found.tradeable()) {
                event.setCancelled(true);
                return;
            }
            EquipmentSlot target = top.getType() == InventoryType.CRAFTING ? craftingViewSlot(raw) : null;
            if (target != null && equipRefused(target, found.declaredSlot(), found.noManualEquip())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ ground items

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(ItemSpawnEvent event) {
        if (ItemRegistry.none()) {
            return;
        }
        Item entity = event.getEntity();
        Found found = find(entity.getItemStack());
        if (found != null && found.def() != null && !found.def().despawnable()) {
            entity.setUnlimitedLifetime(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (ItemRegistry.none()) {
            return;
        }
        Found found = find(event.getItem().getItemStack());
        if (found == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (found.def() != null) {
            found.registry().runActions(player, found.def().pickupActions(), null, null);
        }
    }

    // ------------------------------------------------------------------ death, respawn, join

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (ItemRegistry.none() || event.getKeepInventory()) {
            return;
        }
        Player player = event.getEntity();
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> drops = event.getDrops();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (empty(stack)) {
                continue;
            }
            Found found = find(stack);
            String kind = found == null ? null : found.keptKind();
            if (kind != null && removeOne(drops, stack)) {
                DeathStash.keep(player.getUniqueId(), slot, stack, kind);
            }
        }
    }

    /** Removes the first drop equal to {@code stack}; false when none is (vanished or taken by another plugin). */
    private static boolean removeOne(List<ItemStack> drops, ItemStack stack) {
        for (Iterator<ItemStack> it = drops.iterator(); it.hasNext(); ) {
            if (stack.equals(it.next())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        DeathStash.giveBackLater(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.isDead()) {
            DeathStash.giveBackLater(player);
        }
        for (ItemRegistry registry : ItemRegistry.all()) {
            try {
                registry.handleJoin(player);
            } catch (RuntimeException e) {
                registry.logger().log(Level.WARNING, "Could not give back the equipment of " + player.getName(), e);
            }
        }
    }
}
