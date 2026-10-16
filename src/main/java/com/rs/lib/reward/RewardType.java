package com.rs.lib.reward;

import java.util.Locale;

/** What a {@link Reward} hands over. Written in YAML as {@code type: ITEM} (any case). */
public enum RewardType {
    /** One or more item stacks. */
    ITEM,
    /** A command run by the console. */
    COMMAND,
    /** Money, through {@code rs().economy()}. */
    MONEY,
    /** Experience points. */
    EXPERIENCE,
    /** A potion effect. */
    POTION,
    /** A message to the player. */
    MESSAGE;

    /** The type called {@code name} (any case, {@code -} or {@code _}), or null. */
    public static RewardType parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
