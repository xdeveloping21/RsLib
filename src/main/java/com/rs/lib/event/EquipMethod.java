package com.rs.lib.event;

/**
 * How an armour piece was put on or taken off ({@link RsArmourEquipEvent#getMethod()}).
 *
 * <p>RsLib reports {@link #PICK_DROP} for every change the player makes by hand (Paper does
 * not say which click it was), {@link #BROKE} for a piece that broke, {@link #DISPENSER} and
 * {@link #DEATH}. {@link #SHIFT_CLICK}, {@link #DRAG}, {@link #HOTBAR} and
 * {@link #HOTBAR_SWAP} exist so code written for them compiles, but are not reported today.</p>
 */
public enum EquipMethod {
    SHIFT_CLICK,
    DRAG,
    PICK_DROP,
    HOTBAR,
    HOTBAR_SWAP,
    DISPENSER,
    BROKE,
    DEATH
}
