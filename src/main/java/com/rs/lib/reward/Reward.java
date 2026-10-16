package com.rs.lib.reward;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.action.Requirement;
import com.rs.lib.item.ItemAppearance;

/**
 * One configured reward: what it hands over, the odds of getting it and who may get it.
 * Immutable; read from YAML with {@link RewardList#read} or built in code:
 *
 * <pre>
 * Reward coins = Reward.money("coins", 500).chance(50).permission("crates.vip").build();
 * </pre>
 *
 * <ul>
 *   <li>{@code chance} is a percentage from 0 to 100 and asks <i>does this one happen</i>; 100
 *   (the default) never rolls and 0 never happens.</li>
 *   <li>{@code weight} asks <i>which of these happens</i> and only matters to
 *   {@link Rewards#pick}; default 1, and 0 is never picked. A picked reward still faces its own
 *   chance.</li>
 *   <li>{@code permission} and {@code requirement} decide who may get it; they are checked
 *   before the roll.</li>
 * </ul>
 */
public final class Reward {

    /** The chance of a reward that always happens. */
    public static final double ALWAYS = 100.0;

    private final String id;
    private final RewardType type;
    private final ItemStack item;
    private final ItemAppearance appearance;
    private final String serializedItem;
    private final String text;
    private final double amount;
    private final String effect;
    private final int ticks;
    private final int amplifier;
    private final double chance;
    private final double weight;
    private final String permission;
    private final Requirement requirement;

    private Reward(Builder b) {
        this.id = b.id;
        this.type = b.type;
        this.item = b.item == null ? null : b.item.clone();
        this.appearance = b.appearance;
        this.serializedItem = b.serializedItem;
        this.text = b.text;
        this.amount = b.amount;
        this.effect = b.effect;
        this.ticks = b.ticks;
        this.amplifier = b.amplifier;
        this.chance = b.chance;
        this.weight = b.weight;
        this.permission = b.permission;
        this.requirement = b.requirement;
    }

    // ------------------------------------------------------------------ factories

    /** A reward of {@code stack} (copied), at its own amount. */
    public static Builder item(String id, ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (empty(stack)) {
            throw new IllegalArgumentException("Reward " + id + " has no item");
        }
        Builder b = new Builder(id, RewardType.ITEM);
        b.item = stack.clone();
        return b;
    }

    /** A reward of the item {@code appearance} renders for the player when it is decided. */
    public static Builder item(String id, ItemAppearance appearance) {
        Builder b = new Builder(id, RewardType.ITEM);
        b.appearance = Objects.requireNonNull(appearance, "appearance");
        return b;
    }

    /** A reward of the item stored as {@code base64} by {@link com.rs.lib.item.ItemSerializer}. */
    static Builder serializedItem(String id, String base64) {
        Builder b = new Builder(id, RewardType.ITEM);
        b.serializedItem = Objects.requireNonNull(base64, "base64").trim();
        return b;
    }

    /**
     * A console command, with or without a leading {@code /}. It may use {@code {player}},
     * {@code {uuid}}, the caller's placeholders and PlaceholderAPI.
     */
    public static Builder command(String id, String command) {
        String line = requireText(id, command, "command");
        Builder b = new Builder(id, RewardType.COMMAND);
        b.text = line.startsWith("/") ? line.substring(1).trim() : line;
        if (b.text.isEmpty()) {
            throw new IllegalArgumentException("Reward " + id + " has no command");
        }
        return b;
    }

    /** {@code amount} of money (a finite number above 0) through {@code rs().economy()}. */
    public static Builder money(String id, double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            throw new IllegalArgumentException("Reward " + id + ": money must be a number above 0, got " + amount);
        }
        Builder b = new Builder(id, RewardType.MONEY);
        b.amount = amount;
        return b;
    }

    /** {@code points} experience points (above 0). */
    public static Builder experience(String id, int points) {
        if (points <= 0) {
            throw new IllegalArgumentException("Reward " + id + ": experience must be above 0, got " + points);
        }
        Builder b = new Builder(id, RewardType.EXPERIENCE);
        b.amount = points;
        return b;
    }

    /**
     * The potion effect {@code effect} (a registry key such as {@code speed} or an old Bukkit
     * name) for {@code ticks} ticks (at least 1) at {@code amplifier} (0 to 255).
     */
    public static Builder potion(String id, String effect, int ticks, int amplifier) {
        String name = requireText(id, effect, "effect");
        if (ticks <= 0 || amplifier < 0 || amplifier > 255) {
            throw new IllegalArgumentException("Reward " + id + ": " + ticks + " ticks at amplifier " + amplifier
                    + " is not a potion effect");
        }
        Builder b = new Builder(id, RewardType.POTION);
        b.effect = name;
        b.ticks = ticks;
        b.amplifier = amplifier;
        return b;
    }

    /**
     * A message, through the text pipeline; a line break starts a new message. It may use
     * {@code {player}}, {@code {uuid}}, the caller's placeholders and PlaceholderAPI.
     */
    public static Builder message(String id, String text) {
        Builder b = new Builder(id, RewardType.MESSAGE);
        b.text = requireText(id, text, "message");
        return b;
    }

    /** Null, air or no amount. Compares the three airs directly: {@code isAir()} goes through the registries. */
    static boolean empty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }
        Material type = item.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private static String requireText(String id, String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Reward " + id + " has no " + what);
        }
        return value.trim();
    }

    // ------------------------------------------------------------------ reading

    /** The reward's id: its key in the YAML list. */
    public String id() {
        return id;
    }

    public RewardType type() {
        return type;
    }

    /** The fixed item of an item reward made from a stack (a copy), or null. */
    public ItemStack item() {
        return item == null ? null : item.clone();
    }

    /** The appearance of an item reward written with item keys, or null. */
    public ItemAppearance appearance() {
        return appearance;
    }

    /** The Base64 item of an item reward written with {@code item:}, or null. */
    public String serializedItem() {
        return serializedItem;
    }

    /** The command or message as written (placeholders unresolved), or null. */
    public String text() {
        return text;
    }

    /** The money, or the experience points; 0 for other types. */
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

    /** Percentage from 0 to 100; {@link #ALWAYS} never rolls. */
    public double chance() {
        return chance;
    }

    /** Weight in a {@link Rewards#pick}; 0 is never picked. */
    public double weight() {
        return weight;
    }

    /** The permission the player needs, or null. */
    public String permission() {
        return permission;
    }

    /** What must hold for the player; {@link Requirement#ALWAYS} when there is none. */
    public Requirement requirement() {
        return requirement;
    }

    @Override
    public String toString() {
        return "Reward[" + id + ", " + type + "]";
    }

    /** Sets the odds and the checks of a reward. */
    public static final class Builder {

        private final String id;
        private final RewardType type;
        private ItemStack item;
        private ItemAppearance appearance;
        private String serializedItem;
        private String text;
        private double amount;
        private String effect;
        private int ticks;
        private int amplifier;
        private double chance = ALWAYS;
        private double weight = 1;
        private String permission;
        private Requirement requirement = Requirement.ALWAYS;

        private Builder(String id, RewardType type) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A reward needs an id");
            }
            this.id = id.trim();
            this.type = type;
        }

        /** Percentage from 0 to 100 (default 100). */
        public Builder chance(double percent) {
            if (!(percent >= 0 && percent <= ALWAYS)) {
                throw new IllegalArgumentException("Reward " + id + ": chance must be 0 to 100, got " + percent);
            }
            this.chance = percent;
            return this;
        }

        /** Weight in a {@link Rewards#pick}: a finite number, 0 or more (default 1). */
        public Builder weight(double value) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Reward " + id + ": weight must be 0 or more, got " + value);
            }
            this.weight = value;
            return this;
        }

        /** The permission the player needs; null or blank for none. */
        public Builder permission(String node) {
            this.permission = node == null || node.isBlank() ? null : node.trim();
            return this;
        }

        /** What must hold for the player; null for nothing. */
        public Builder requirement(Requirement value) {
            this.requirement = value == null ? Requirement.ALWAYS : value;
            return this;
        }

        public Reward build() {
            return new Reward(this);
        }
    }
}
