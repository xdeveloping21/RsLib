package com.rs.lib.text.internal;

import java.util.function.LongSupplier;

/** Lets tests of other packages drive the tick {@link SharedPlaceholders} shares values within. */
public final class SharedTicks {

    private SharedTicks() {
    }

    /** Uses {@code ticks} as the server's tick; null restores the real clock. */
    public static void use(LongSupplier ticks) {
        SharedPlaceholders.clock(ticks);
    }

    /** Values reused on this thread so far. */
    public static long hits() {
        return SharedPlaceholders.hits();
    }
}
