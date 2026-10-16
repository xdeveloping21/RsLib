package com.rs.lib.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.inventory.ItemStack;

/**
 * One reward after it was decided: either won, with everything it hands over fixed (items
 * rendered for the player, placeholders resolved, amounts set), or not, with the reason.
 * Immutable; the item getters return copies.
 */
public final class ResolvedReward {

    private final String id;
    private final RewardType type;
    private final RewardOutcome skipped;
    private final List<ItemStack> items;
    private final String text;
    private final double amount;
    private final String effect;
    private final int ticks;
    private final int amplifier;

    private ResolvedReward(String id, RewardType type, RewardOutcome skipped, List<ItemStack> items, String text,
                           double amount, String effect, int ticks, int amplifier) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.skipped = skipped;
        this.items = items;
        this.text = text;
        this.amount = amount;
        this.effect = effect;
        this.ticks = ticks;
        this.amplifier = amplifier;
    }

    /** A reward that will not be handed over, and why. */
    static ResolvedReward skipped(String id, RewardType type, RewardOutcome why) {
        return new ResolvedReward(id, type, why, List.of(), null, 0, null, 0, 0);
    }

    static ResolvedReward items(String id, List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            copies.add(stack.clone());
        }
        return new ResolvedReward(id, RewardType.ITEM, null, List.copyOf(copies), null, 0, null, 0, 0);
    }

    static ResolvedReward text(String id, RewardType type, String text) {
        return new ResolvedReward(id, type, null, List.of(), Objects.requireNonNull(text, "text"), 0, null, 0, 0);
    }

    static ResolvedReward amount(String id, RewardType type, double amount) {
        return new ResolvedReward(id, type, null, List.of(), null, amount, null, 0, 0);
    }

    static ResolvedReward potion(String id, String effect, int ticks, int amplifier) {
        return new ResolvedReward(id, RewardType.POTION, null, List.of(), null, 0, effect, ticks, amplifier);
    }

    public String id() {
        return id;
    }

    public RewardType type() {
        return type;
    }

    /** True when this reward will be handed over. */
    public boolean won() {
        return skipped == null;
    }

    /** Why it will not be handed over ({@link RewardOutcome#LOST_ROLL}, ...), or null when it was won. */
    public RewardOutcome skipped() {
        return skipped;
    }

    /** Copies of the stacks of a won item reward; empty otherwise. */
    public List<ItemStack> items() {
        List<ItemStack> out = new ArrayList<>(items.size());
        for (ItemStack stack : items) {
            out.add(stack.clone());
        }
        return out;
    }

    /** The resolved command or message, or null. */
    public String text() {
        return text;
    }

    /** The money or experience points; 0 for other types. */
    public double amount() {
        return amount;
    }

    /** The potion effect's name, or null. */
    public String effect() {
        return effect;
    }

    /** The potion effect's duration in ticks; 0 for other types. */
    public int ticks() {
        return ticks;
    }

    /** The potion effect's amplifier; 0 for other types. */
    public int amplifier() {
        return amplifier;
    }

    /** The stacks themselves, for the module (never handed out). */
    List<ItemStack> stacks() {
        return items;
    }

    @Override
    public String toString() {
        return "ResolvedReward[" + id + ", " + type + ", " + (won() ? "won" : skipped) + "]";
    }
}
