package com.rs.lib.teleport;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

/**
 * How one teleport behaves: the wait, an optional cooldown, its messages and what runs after.
 *
 * <pre>
 * TeleportOptions.builder()
 *         .warmupSeconds(5)
 *         .cooldown("home", 60)
 *         .warmupKey("messages.home-warmup")
 *         .onComplete(p -&gt; rs().lang().send(p, "messages.home-teleported"))
 *         .build();
 * </pre>
 *
 * <p>Message keys are looked up in the plugin's language files ({@code {time}} in the wait
 * message is the wait, such as {@code 5s}); without a language module RsLib's English texts
 * are used. Immutable.</p>
 */
public final class TeleportOptions {

    /** The wait a {@link #builder()} starts with. */
    public static final int DEFAULT_WARMUP_SECONDS = 3;
    public static final String WARMUP_KEY = "rslib.teleport.warmup";
    public static final String CANCELLED_MOVE_KEY = "rslib.teleport.cancelled-move";
    public static final String CANCELLED_DAMAGE_KEY = "rslib.teleport.cancelled-damage";

    private static final TeleportOptions INSTANT = builder().warmupSeconds(0).build();

    private final int warmupSeconds;
    private final String cooldownCategory;
    private final int cooldownSeconds;
    private final String warmupKey;
    private final String cancelledMoveKey;
    private final String cancelledDamageKey;
    private final boolean silent;
    private final Consumer<Player> onComplete;

    private TeleportOptions(Builder b) {
        this.warmupSeconds = b.warmupSeconds;
        this.cooldownCategory = b.cooldownCategory;
        this.cooldownSeconds = b.cooldownSeconds;
        this.warmupKey = b.warmupKey;
        this.cancelledMoveKey = b.cancelledMoveKey;
        this.cancelledDamageKey = b.cancelledDamageKey;
        this.silent = b.silent;
        this.onComplete = b.onComplete;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** No wait, no cooldown. */
    public static TeleportOptions instant() {
        return INSTANT;
    }

    /** A wait of {@code seconds}, nothing else. */
    public static TeleportOptions warmup(int seconds) {
        return builder().warmupSeconds(seconds).build();
    }

    public int warmupSeconds() {
        return warmupSeconds;
    }

    /** The wait in ticks (20 per second). */
    public long warmupTicks() {
        return warmupSeconds * 20L;
    }

    /** The cooldown category shared with {@code rs().cooldowns()}, or null. */
    public String cooldownCategory() {
        return cooldownCategory;
    }

    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    public String warmupKey() {
        return warmupKey;
    }

    public String cancelledMoveKey() {
        return cancelledMoveKey;
    }

    public String cancelledDamageKey() {
        return cancelledDamageKey;
    }

    /** True when no message is sent (neither the wait nor the cancellations). */
    public boolean silent() {
        return silent;
    }

    /** Runs on the player's thread after a teleport that really happened, or null. */
    public Consumer<Player> onComplete() {
        return onComplete;
    }

    public static final class Builder {

        private int warmupSeconds = DEFAULT_WARMUP_SECONDS;
        private String cooldownCategory;
        private int cooldownSeconds;
        private String warmupKey = WARMUP_KEY;
        private String cancelledMoveKey = CANCELLED_MOVE_KEY;
        private String cancelledDamageKey = CANCELLED_DAMAGE_KEY;
        private boolean silent;
        private Consumer<Player> onComplete;

        private Builder() {
        }

        /** The wait; 0 or less teleports at once. */
        public Builder warmupSeconds(int seconds) {
            this.warmupSeconds = Math.max(0, seconds);
            return this;
        }

        /**
         * A cooldown in {@code category} of {@code rs().cooldowns()}: a teleport is refused
         * while it runs, and it starts when a teleport completes (not when it is asked for).
         * 0 seconds only checks the category.
         */
        public Builder cooldown(String category, int seconds) {
            this.cooldownCategory = category == null || category.isBlank() ? null : category;
            this.cooldownSeconds = Math.max(0, seconds);
            return this;
        }

        public Builder warmupKey(String key) {
            this.warmupKey = key == null ? WARMUP_KEY : key;
            return this;
        }

        public Builder cancelledMoveKey(String key) {
            this.cancelledMoveKey = key == null ? CANCELLED_MOVE_KEY : key;
            return this;
        }

        public Builder cancelledDamageKey(String key) {
            this.cancelledDamageKey = key == null ? CANCELLED_DAMAGE_KEY : key;
            return this;
        }

        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        public Builder onComplete(Consumer<Player> onComplete) {
            this.onComplete = onComplete;
            return this;
        }

        public TeleportOptions build() {
            return new TeleportOptions(this);
        }
    }
}
