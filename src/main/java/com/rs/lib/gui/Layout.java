package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A character grid over the menu, as {@code layout:} and the flow grids write it: cell
 * (row i, column j) is slot {@code i*9+j}, at most 6 rows of 9. A space is an empty cell.
 */
final class Layout {

    static final int COLUMNS = 9;
    static final int MAX_ROWS = 6;

    private final String[] rows;

    private Layout(String[] rows) {
        this.rows = rows;
    }

    /**
     * Reads a grid written as a list of texts; extra rows and characters are cut with one
     * warning each. Null when {@code raw} is not a list or holds no row.
     */
    static Layout parse(Object raw, String key, Consumer<String> warn) {
        if (!(raw instanceof List<?> list)) {
            if (raw != null) {
                warn.accept("'" + key + "' must be a list of rows, got '" + raw + "'; ignored");
            }
            return null;
        }
        List<String> out = new ArrayList<>(MAX_ROWS);
        for (Object element : list) {
            if (out.size() == MAX_ROWS) {
                warn.accept("'" + key + "' has " + list.size() + " rows; only the first " + MAX_ROWS + " are used");
                break;
            }
            String line = element == null ? "" : String.valueOf(element);
            if (line.length() > COLUMNS) {
                warn.accept("'" + key + "' row " + (out.size() + 1) + " has " + line.length()
                        + " characters; only the first " + COLUMNS + " are used");
                line = line.substring(0, COLUMNS);
            }
            out.add(line);
        }
        return out.isEmpty() ? null : new Layout(out.toArray(String[]::new));
    }

    static Layout of(String... rows) {
        return new Layout(rows.clone());
    }

    int rows() {
        return rows.length;
    }

    /** The character of {@code slot}, a space when the grid has none there. */
    char at(int slot) {
        if (slot < 0) {
            return ' ';
        }
        int row = slot / COLUMNS;
        int col = slot % COLUMNS;
        if (row >= rows.length || col >= rows[row].length()) {
            return ' ';
        }
        return rows[row].charAt(col);
    }

    /** Every slot holding {@code key}, row by row. */
    int[] slotsOf(char key) {
        return GuiMask.slots(key, rows);
    }

    boolean contains(char key) {
        for (String row : rows) {
            if (row.indexOf(key) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** The rows as written (after cutting). */
    List<String> lines() {
        return List.of(rows);
    }
}
