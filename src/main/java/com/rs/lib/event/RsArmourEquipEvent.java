package com.rs.lib.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A player's armour piece changed: put on, taken off or swapped. Fired by RsLib on the thread
 * of the source event.
 *
 * <ul>
 *   <li>{@link EquipMethod#PICK_DROP}: any change the player made by hand (from Paper's
 *   armour change event); {@link EquipMethod#BROKE} when the piece that left had used up its
 *   durability. Cancelling these changes nothing: the change already happened.</li>
 *   <li>{@link EquipMethod#DISPENSER}: a dispenser is about to put the piece on the player.
 *   Cancelling this one stops the dispenser. Registered items that refuse manual equipping
 *   were already stopped by RsLib's item module, so they never get here.</li>
 *   <li>{@link EquipMethod#DEATH}: one event per piece worn when the player dies without
 *   keep-inventory. Cancelling changes nothing.</li>
 * </ul>
 *
 * <p>Air is reported as null, and a change from nothing to nothing is never reported. The
 * setters only change what later listeners read, never the player's inventory.</p>
 */
public final class RsArmourEquipEvent extends RsPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EquipMethod method;
    private final EquipmentSlot slot;
    private ItemStack oldPiece;
    private ItemStack newPiece;

    public RsArmourEquipEvent(Player player, EquipMethod method, EquipmentSlot slot,
                              @Nullable ItemStack oldPiece, @Nullable ItemStack newPiece) {
        super(player);
        this.method = method;
        this.slot = slot;
        this.oldPiece = oldPiece;
        this.newPiece = newPiece;
    }

    public EquipMethod getMethod() {
        return method;
    }

    /** {@code HEAD}, {@code CHEST}, {@code LEGS} or {@code FEET}. */
    public EquipmentSlot getSlot() {
        return slot;
    }

    /** The piece that was in the slot, or null. */
    public @Nullable ItemStack getOldPiece() {
        return oldPiece;
    }

    public void setOldPiece(@Nullable ItemStack oldPiece) {
        this.oldPiece = oldPiece;
    }

    /** The piece that goes in the slot, or null. */
    public @Nullable ItemStack getNewPiece() {
        return newPiece;
    }

    public void setNewPiece(@Nullable ItemStack newPiece) {
        this.newPiece = newPiece;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
