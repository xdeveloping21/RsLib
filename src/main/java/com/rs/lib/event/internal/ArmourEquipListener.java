package com.rs.lib.event.internal;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.rs.lib.compat.RsVersion;
import com.rs.lib.event.EquipMethod;
import com.rs.lib.event.RsArmourEquipEvent;
import com.rs.lib.util.ArmourUtil;

/**
 * Fires {@link RsArmourEquipEvent}. Shared by every plugin and registered once by RsLib.
 *
 * <ul>
 *   <li>Paper's {@code PlayerArmorChangeEvent} (MONITOR) gives every change, without saying
 *   how: it is reported as {@link EquipMethod#PICK_DROP}, or {@link EquipMethod#BROKE} when the
 *   piece left with its durability used up. Changes of a dead player are skipped (the death
 *   reports them).</li>
 *   <li>{@code BlockDispenseArmorEvent} (HIGHEST, ignoring cancelled ones) gives
 *   {@link EquipMethod#DISPENSER}, and cancelling the RsLib event cancels the dispenser. It runs
 *   after RsLib's item module (HIGH), which already stopped registered items that refuse manual
 *   equipping, so those are never reported. A dispenser that really fires (seen at MONITOR)
 *   leaves a mark for its slot; Paper's change event for that slot in the same or the next
 *   tick is the same change and is dropped.</li>
 *   <li>{@code PlayerDeathEvent} (MONITOR) gives one {@link EquipMethod#DEATH} per piece worn,
 *   unless the player keeps the inventory.</li>
 * </ul>
 *
 * <p>Nothing is built while no plugin listens to {@link RsArmourEquipEvent}. Paper's change
 * event is obsolete from 1.21.4 on but still fired; its replacement does not exist on the
 * oldest supported versions, so it is used on purpose, through the slot-type getter every
 * version has.</p>
 */
public final class ArmourEquipListener implements Listener {

    private static final ArmourEquipListener SHARED = new ArmourEquipListener(EventSink.SERVER, Bukkit::getCurrentTick);

    /** The four armour slots, in the order of a mark's entries. */
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int NO_MARK = Integer.MIN_VALUE;

    private final EventSink sink;
    private final IntSupplier tick;
    /** Per player, the tick a dispenser put a piece in each armour slot ({@link #NO_MARK} for none). */
    private final Map<UUID, int[]> dispensed = new ConcurrentHashMap<>();

    ArmourEquipListener(EventSink sink, IntSupplier tick) {
        this.sink = sink;
        this.tick = tick;
    }

    /** The instance RsLib registers. */
    public static Listener shared() {
        return SHARED;
    }

    // ------------------------------------------------------------------ Paper's change event

    @SuppressWarnings("deprecation") // getSlotType: the only slot getter Paper 1.20 has
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmourChange(PlayerArmorChangeEvent event) {
        if (sink.listened(RsArmourEquipEvent.getHandlerList())) {
            changed(event.getPlayer(), event.getSlotType().name(), event.getOldItem(), event.getNewItem());
        }
    }

    /** A change Paper reported in the slot named {@code slotName} ({@code HEAD} ... {@code FEET}). */
    void changed(Player player, String slotName, ItemStack oldItem, ItemStack newItem) {
        ItemStack old = present(oldItem);
        ItemStack now = present(newItem);
        int index = indexOf(slotName);
        if ((old == null && now == null) || index < 0 || consumeMark(player.getUniqueId(), index)
                || player.isDead()) {
            return;
        }
        EquipMethod method = now == null && brokeOut(old) ? EquipMethod.BROKE : EquipMethod.PICK_DROP;
        sink.call(new RsArmourEquipEvent(player, method, SLOTS[index], old, now));
    }

    // ------------------------------------------------------------------ dispensers

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity() instanceof Player player && sink.listened(RsArmourEquipEvent.getHandlerList())
                && !dispensing(player, event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** The dispenser really fires: later listeners did not cancel it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispensed(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity() instanceof Player player && sink.listened(RsArmourEquipEvent.getHandlerList())) {
            dispensed(player, event.getItem());
        }
    }

    /** Fires the DISPENSER event; false when a listener cancelled it. */
    boolean dispensing(Player player, ItemStack item) {
        ItemStack piece = present(item);
        EquipmentSlot slot = piece == null ? null : ArmourUtil.slotOf(piece);
        if (indexOf(slot) < 0) {
            return true;
        }
        RsArmourEquipEvent event = new RsArmourEquipEvent(player, EquipMethod.DISPENSER, slot,
                copy(player.getInventory().getItem(slot)), piece);
        sink.call(event);
        return !event.isCancelled();
    }

    /** Marks the slot the dispensed piece goes in, for this tick. */
    void dispensed(Player player, ItemStack item) {
        ItemStack piece = present(item);
        int index = piece == null ? -1 : indexOf(ArmourUtil.slotOf(piece));
        if (index < 0) {
            return;
        }
        dispensed.computeIfAbsent(player.getUniqueId(), id -> {
            int[] marks = new int[SLOTS.length];
            Arrays.fill(marks, NO_MARK);
            return marks;
        })[index] = tick.getAsInt();
    }

    /** True when a dispenser filled that slot in this tick or the previous one; the mark is used up either way. */
    private boolean consumeMark(UUID player, int index) {
        int[] marks = dispensed.get(player);
        if (marks == null || marks[index] == NO_MARK) {
            return false;
        }
        int age = tick.getAsInt() - marks[index];
        marks[index] = NO_MARK;
        return age >= 0 && age <= 1;
    }

    // ------------------------------------------------------------------ death and quit

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (sink.listened(RsArmourEquipEvent.getHandlerList())) {
            died(event.getEntity(), event.getKeepInventory());
        }
    }

    /** One DEATH event per piece worn, unless the inventory is kept. */
    void died(Player player, boolean keepInventory) {
        if (keepInventory) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack piece = copy(inventory.getItem(slot));
            if (piece != null) {
                sink.call(new RsArmourEquipEvent(player, EquipMethod.DEATH, slot, piece, null));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    void forget(UUID player) {
        dispensed.remove(player);
    }

    /** Players with dispenser marks; for tests. */
    int marked() {
        return dispensed.size();
    }

    // ------------------------------------------------------------------ items

    /** {@code item}, or null for null, air or an empty stack. */
    static ItemStack present(ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return null;
        }
        // Compared by constant: Material.isAir() needs the server's registries on newer versions.
        Material type = item.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR ? null : item;
    }

    /** A copy of a stack read from an inventory (so listeners cannot change the inventory), or null. */
    private static ItemStack copy(ItemStack item) {
        ItemStack piece = present(item);
        return piece == null ? null : piece.clone();
    }

    /** True when {@code piece} left with its durability used up. */
    static boolean brokeOut(ItemStack piece) {
        if (!piece.hasItemMeta() || !(piece.getItemMeta() instanceof Damageable meta) || !meta.hasDamage()) {
            return false;
        }
        // A custom maximum (1.20.5+) wins over the material's.
        int max = RsVersion.supports(20, 5) && meta.hasMaxDamage() ? meta.getMaxDamage() : piece.getType().getMaxDurability();
        return max > 0 && meta.getDamage() >= max;
    }

    private static int indexOf(String slotName) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i].name().equals(slotName)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(EquipmentSlot slot) {
        for (int i = 0; i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }
}
