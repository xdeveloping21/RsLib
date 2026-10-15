package com.rs.lib.gui;

import java.util.function.Consumer;

/**
 * The fill order of a set of cells drawn with arrows ({@code paged-flow}, {@code regions.<id>.flow}).
 *
 * <p>Every ordered cell holds {@code >}, {@code <}, {@code ^}, {@code v}/{@code V} or a space
 * (the path ends there). An arrow points to the first ordered cell in its direction, skipping
 * the cells that are not ordered, so gaps and filler never break a path. A turn (an arrow on
 * the other axis than the one travelled, like the {@code v} at the end of a row) enters the
 * next line where that line's arrows start: it lands on the cell it points to, walks back
 * along the arrows of the travelled axis that lead there, up to the first cell not visited
 * yet, and carries on from there. That gives "every row right to left, top to bottom" as well
 * as the snake.</p>
 *
 * <p>The path starts at a cell no arrow points to, and exactly one such start must visit every
 * cell once. Anything else (a loop, a cell off the path, two complete paths, a character that
 * is not an arrow) is reported once, naming the first slot in trouble, and the default order
 * is kept. An arrow on a cell that is not ordered is reported too (the grid is usually shifted
 * against the layout). Pure; slots are {@code row*9+column}.</p>
 */
final class FlowOrder {

    private static final int CELLS = Layout.MAX_ROWS * Layout.COLUMNS;
    private static final byte NONE = 0;
    private static final byte RIGHT = 1;
    private static final byte LEFT = 2;
    private static final byte UP = 3;
    private static final byte DOWN = 4;

    private final boolean[] ordered = new boolean[CELLS];
    private final byte[] dirs = new byte[CELLS];
    private final int count;

    private FlowOrder(int[] cells) {
        int n = 0;
        for (int cell : cells) {
            if (cell >= 0 && cell < CELLS && !ordered[cell]) {
                ordered[cell] = true;
                n++;
            }
        }
        this.count = n;
    }

    /**
     * {@code cells} (in their default order) ordered by the arrows of {@code grid}, or null
     * (reported through {@code warn}) when the arrows do not draw one complete path.
     */
    static int[] order(int[] cells, Layout grid, String key, Consumer<String> warn) {
        if (cells.length == 0 || grid == null) {
            return null;
        }
        FlowOrder flow = new FlowOrder(cells);
        if (flow.count != cells.length) {
            warn.accept("'" + key + "' cannot order slots beyond the six rows of a chest; the default order is kept");
            return null;
        }
        for (int slot = 0; slot < CELLS; slot++) {
            char c = grid.at(slot);
            if (c != ' ' && !flow.ordered[slot]) {
                warn.accept("'" + key + "' has '" + c + "' on slot " + slot + ", which is not one of the cells it orders;"
                        + " is the grid shifted against the layout?");
                break;
            }
        }
        for (int cell : cells) {
            char c = grid.at(cell);
            byte dir = switch (c) {
                case ' ' -> NONE;
                case '>' -> RIGHT;
                case '<' -> LEFT;
                case '^' -> UP;
                case 'v', 'V' -> DOWN;
                default -> (byte) -1;
            };
            if (dir < 0) {
                warn.accept("'" + key + "' has '" + c + "' on slot " + cell + ", which is not an arrow"
                        + " (> < ^ v or a space); the default order is kept");
                return null;
            }
            flow.dirs[cell] = dir;
        }
        return flow.solve(cells, key, warn);
    }

    private int[] solve(int[] cells, String key, Consumer<String> warn) {
        boolean[] incoming = new boolean[CELLS];
        for (int cell : cells) {
            int target = target(cell, dirs[cell]);
            if (target >= 0) {
                incoming[target] = true;
            }
        }
        int[] complete = null;
        int completeStart = -1;
        int[] firstWalk = null;
        int firstCycle = -1;
        for (int start : cells) {
            if (incoming[start]) {
                continue;
            }
            int[] cycle = new int[1];
            int[] walk = walk(start, cycle);
            if (firstWalk == null) {
                firstWalk = walk;
                firstCycle = cycle[0];
            }
            if (cycle[0] < 0 && walk.length == count) {
                if (complete != null) {
                    warn.accept("'" + key + "' draws two complete paths, from slot " + completeStart + " and from slot "
                            + start + "; the default order is kept");
                    return null;
                }
                complete = walk;
                completeStart = start;
            }
        }
        if (complete != null) {
            return complete;
        }
        if (firstWalk == null) {
            warn.accept("'" + key + "' is a loop: every cell is pointed at, so there is no first cell (slot " + cells[0]
                    + "); the default order is kept");
            return null;
        }
        if (firstCycle >= 0) {
            warn.accept("'" + key + "' runs in a loop at slot " + firstCycle + "; the default order is kept");
            return null;
        }
        boolean[] seen = new boolean[CELLS];
        for (int cell : firstWalk) {
            seen[cell] = true;
        }
        int missing = cells[0];
        for (int cell : cells) {
            if (!seen[cell]) {
                missing = cell;
                break;
            }
        }
        warn.accept("'" + key + "' leaves slot " + missing + " off the path; the default order is kept");
        return null;
    }

    /** The cells visited from {@code start}; {@code cycle[0]} is the slot met twice, or -1. */
    private int[] walk(int start, int[] cycle) {
        boolean[] visited = new boolean[CELLS];
        int[] out = new int[count];
        int n = 0;
        int cur = start;
        int travel = -1;
        cycle[0] = -1;
        while (true) {
            if (visited[cur] || n == count) {
                cycle[0] = cur;
                break;
            }
            visited[cur] = true;
            out[n++] = cur;
            byte dir = dirs[cur];
            int next = target(cur, dir);
            if (next < 0) {
                break;
            }
            int axis = axis(dir);
            if (travel >= 0 && axis != travel && !visited[next]) {
                int entry = next;
                int back = predecessor(entry, travel, visited);
                while (back >= 0) {
                    entry = back;
                    back = predecessor(entry, travel, visited);
                }
                if (entry == next) {
                    travel = axis;
                }
                next = entry;
            } else {
                travel = axis;
            }
            cur = next;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** The unvisited cell on {@code axis} whose arrow points at {@code cell}, or -1. */
    private int predecessor(int cell, int axis, boolean[] visited) {
        int first;
        int second;
        if (axis == 0) {
            first = scan(cell, LEFT);
            second = scan(cell, RIGHT);
            first = first >= 0 && dirs[first] == RIGHT && !visited[first] ? first : -1;
            second = second >= 0 && dirs[second] == LEFT && !visited[second] ? second : -1;
            if (first >= 0 && second >= 0) {
                return dirs[cell] == LEFT ? second : first;
            }
        } else {
            first = scan(cell, UP);
            second = scan(cell, DOWN);
            first = first >= 0 && dirs[first] == DOWN && !visited[first] ? first : -1;
            second = second >= 0 && dirs[second] == UP && !visited[second] ? second : -1;
            if (first >= 0 && second >= 0) {
                return dirs[cell] == UP ? second : first;
            }
        }
        return first >= 0 ? first : second;
    }

    /** The first ordered cell from {@code cell} towards {@code dir}, or -1. */
    private int target(int cell, byte dir) {
        return dir == NONE ? -1 : scan(cell, dir);
    }

    private int scan(int cell, byte dir) {
        int row = cell / Layout.COLUMNS;
        int col = cell % Layout.COLUMNS;
        int dr = dir == UP ? -1 : dir == DOWN ? 1 : 0;
        int dc = dir == LEFT ? -1 : dir == RIGHT ? 1 : 0;
        row += dr;
        col += dc;
        while (row >= 0 && row < Layout.MAX_ROWS && col >= 0 && col < Layout.COLUMNS) {
            int slot = row * Layout.COLUMNS + col;
            if (ordered[slot]) {
                return slot;
            }
            row += dr;
            col += dc;
        }
        return -1;
    }

    /** 0 for left and right, 1 for up and down. */
    private static int axis(byte dir) {
        return dir == LEFT || dir == RIGHT ? 0 : 1;
    }
}
