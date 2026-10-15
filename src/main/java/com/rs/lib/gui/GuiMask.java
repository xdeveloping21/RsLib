package com.rs.lib.gui;

import java.util.Arrays;
import java.util.List;

/** Slots of a character in an ASCII mask, the way {@code layout:} reads: cell (row i, column j) is slot {@code i*9+j}. */
public final class GuiMask {

    private GuiMask() {
    }

    /** Every slot where {@code rows} holds {@code key}, row by row; extra rows and columns are ignored. */
    public static int[] slots(char key, String... rows) {
        return rows == null ? new int[0] : slots(key, Arrays.asList(rows));
    }

    /** Same as {@link #slots(char, String...)}. */
    public static int[] slots(char key, List<String> rows) {
        if (rows == null) {
            return new int[0];
        }
        int[] out = new int[Layout.MAX_ROWS * Layout.COLUMNS];
        int n = 0;
        for (int row = 0; row < Math.min(rows.size(), Layout.MAX_ROWS); row++) {
            String line = rows.get(row);
            if (line == null) {
                continue;
            }
            for (int col = 0; col < Math.min(line.length(), Layout.COLUMNS); col++) {
                if (line.charAt(col) == key) {
                    out[n++] = row * Layout.COLUMNS + col;
                }
            }
        }
        return Arrays.copyOf(out, n);
    }
}
