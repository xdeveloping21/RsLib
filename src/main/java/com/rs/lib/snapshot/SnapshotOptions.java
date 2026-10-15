package com.rs.lib.snapshot;

import java.util.Set;

/**
 * How {@link Snapshots#save(org.bukkit.entity.Player, String, SnapshotOptions)} saves.
 *
 * @param parts     what is captured (at least one part)
 * @param clear     whether the captured parts are cleared from the player in the same tick
 *                  as the capture
 * @param overwrite whether an existing snapshot with the same key is replaced; without it the
 *                  save completes with false and leaves both the player and the file alone
 */
public record SnapshotOptions(Set<SnapshotPart> parts, boolean clear, boolean overwrite) {

    private static final SnapshotOptions DEFAULTS = new SnapshotOptions(SnapshotPart.DEFAULT, false, false);

    public SnapshotOptions {
        parts = SnapshotPart.copyOf(parts);
    }

    /** {@link SnapshotPart#DEFAULT}, no clearing, no overwriting. */
    public static SnapshotOptions defaults() {
        return DEFAULTS;
    }

    public SnapshotOptions withParts(Set<SnapshotPart> parts) {
        return new SnapshotOptions(parts, clear, overwrite);
    }

    public SnapshotOptions withClear(boolean clear) {
        return new SnapshotOptions(parts, clear, overwrite);
    }

    public SnapshotOptions withOverwrite(boolean overwrite) {
        return new SnapshotOptions(parts, clear, overwrite);
    }
}
