package com.rs.lib.snapshot;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a {@link PlayerSnapshot} holds. The names are written to the snapshot files
 * ({@code parts: [INVENTORY, HEALTH, ...]}), so they never change.
 */
public enum SnapshotPart {

    /** Every inventory slot (armour and off hand included), the cursor and the held slot. */
    INVENTORY,
    /** The ender chest. */
    ENDER_CHEST,
    /** Health, food, saturation, exhaustion, air and fire ticks. */
    HEALTH,
    /** Level, progress bar and total experience. */
    EXPERIENCE,
    /** Active potion effects. */
    EFFECTS,
    /** Game mode, whether flight is allowed and whether the player is flying. */
    GAMEMODE,
    /** Where the player stands; restored only when its world is loaded. */
    LOCATION;

    /** Everything but the ender chest and the location. */
    public static final Set<SnapshotPart> DEFAULT = Collections.unmodifiableSet(
            EnumSet.of(INVENTORY, HEALTH, EXPERIENCE, EFFECTS, GAMEMODE));

    /** Every part. */
    public static final Set<SnapshotPart> ALL = Collections.unmodifiableSet(EnumSet.allOf(SnapshotPart.class));

    /** An unmodifiable copy of {@code parts}; throws when it is null, empty or holds null. */
    static Set<SnapshotPart> copyOf(Set<SnapshotPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("A snapshot needs at least one part");
        }
        for (SnapshotPart part : parts) {
            if (part == null) {
                throw new IllegalArgumentException("A snapshot part is null");
            }
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(parts));
    }
}
