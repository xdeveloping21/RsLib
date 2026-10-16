package com.rs.lib.util;

import java.util.function.LongSupplier;

/** Lets tests outside this package move the clock relative times are measured against. */
public final class FormatsClock {

    private FormatsClock() {
    }

    /** {@code null} restores the real clock. */
    public static void set(LongSupplier now) {
        Formats.clock(now);
    }
}
