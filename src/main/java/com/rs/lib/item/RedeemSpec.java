package com.rs.lib.item;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.bukkit.Material;

/**
 * How much a right click redeems: one unit, the stack in the hand, or every matching item the
 * player carries (inventory and cursor, up to a limit). Clicking a block whose material is in
 * {@link #blockedOn()} uses the block instead (a voucher clicked on a chest opens the chest).
 * Immutable.
 */
public final class RedeemSpec {

    public enum Mode { SINGLE, HAND_STACK, ALL_MATCHING }

    private final Mode mode;
    private final int limit;
    private final Set<Material> blockedOn;

    private RedeemSpec(Mode mode, int limit, Set<Material> blockedOn) {
        this.mode = mode;
        this.limit = limit;
        this.blockedOn = blockedOn;
    }

    /** One unit per click. */
    public static RedeemSpec single() {
        return new RedeemSpec(Mode.SINGLE, 1, Set.of());
    }

    /** The whole stack in the hand used. */
    public static RedeemSpec handStack() {
        return new RedeemSpec(Mode.HAND_STACK, 0, Set.of());
    }

    /** Every matching item in the inventory and on the cursor. */
    public static RedeemSpec allMatching() {
        return new RedeemSpec(Mode.ALL_MATCHING, 0, Set.of());
    }

    /** Every matching item, at most {@code limit} units (0 or less: no limit). */
    public static RedeemSpec allMatching(int limit) {
        return new RedeemSpec(Mode.ALL_MATCHING, Math.max(0, limit), Set.of());
    }

    /** A copy where clicking a block of one of {@code materials} uses the block instead of redeeming. */
    public RedeemSpec blockedOn(Collection<Material> materials) {
        Set<Material> set = materials == null || materials.isEmpty() ? Set.of()
                : Set.copyOf(EnumSet.copyOf(materials));
        return new RedeemSpec(mode, limit, set);
    }

    public Mode mode() {
        return mode;
    }

    /** The most units one click takes; 0 means no limit. 1 for {@link Mode#SINGLE}. */
    public int limit() {
        return limit;
    }

    public Set<Material> blockedOn() {
        return blockedOn;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RedeemSpec that && mode == that.mode && limit == that.limit
                && blockedOn.equals(that.blockedOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, limit, blockedOn);
    }

    @Override
    public String toString() {
        return "RedeemSpec[" + mode + (limit > 0 ? ", limit " + limit : "")
                + (blockedOn.isEmpty() ? "" : ", blocked on " + blockedOn) + "]";
    }
}
