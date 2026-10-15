package com.rs.lib.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The twelve ways a registered item can be used, each with its YAML action list and Java
 * callback: four generic ones and eight that also name the surface (block or air).
 */
enum ItemUse {
    RIGHT_CLICK("right-click-actions"),
    LEFT_CLICK("left-click-actions"),
    SHIFT_RIGHT_CLICK("shift-right-click-actions"),
    SHIFT_LEFT_CLICK("shift-left-click-actions"),
    RIGHT_CLICK_BLOCK("right-click-block-actions"),
    RIGHT_CLICK_AIR("right-click-air-actions"),
    LEFT_CLICK_BLOCK("left-click-block-actions"),
    LEFT_CLICK_AIR("left-click-air-actions"),
    SHIFT_RIGHT_CLICK_BLOCK("shift-right-click-block-actions"),
    SHIFT_RIGHT_CLICK_AIR("shift-right-click-air-actions"),
    SHIFT_LEFT_CLICK_BLOCK("shift-left-click-block-actions"),
    SHIFT_LEFT_CLICK_AIR("shift-left-click-air-actions");

    private final String key;

    ItemUse(String key) {
        this.key = key;
    }

    /** The YAML key of this use's action list. */
    String key() {
        return key;
    }

    /**
     * What one click runs, in order. First the generic pair (right or left), then, in addition,
     * the pair for the surface clicked. In each pair: without sneaking only the plain use runs;
     * sneaking with a shift use that does something ({@code active}) runs the shift use alone
     * when {@code shiftOverrides}, or the shift use then the plain one otherwise; sneaking with
     * an empty shift use runs the plain one.
     */
    static List<ItemUse> dispatch(boolean right, boolean block, boolean sneaking, boolean shiftOverrides,
                                  Predicate<ItemUse> active) {
        List<ItemUse> out = new ArrayList<>(4);
        pair(out, right ? RIGHT_CLICK : LEFT_CLICK, right ? SHIFT_RIGHT_CLICK : SHIFT_LEFT_CLICK,
                sneaking, shiftOverrides, active);
        ItemUse base = right ? (block ? RIGHT_CLICK_BLOCK : RIGHT_CLICK_AIR) : (block ? LEFT_CLICK_BLOCK : LEFT_CLICK_AIR);
        ItemUse shift = right ? (block ? SHIFT_RIGHT_CLICK_BLOCK : SHIFT_RIGHT_CLICK_AIR)
                : (block ? SHIFT_LEFT_CLICK_BLOCK : SHIFT_LEFT_CLICK_AIR);
        pair(out, base, shift, sneaking, shiftOverrides, active);
        return out;
    }

    private static void pair(List<ItemUse> out, ItemUse base, ItemUse shift, boolean sneaking, boolean shiftOverrides,
                             Predicate<ItemUse> active) {
        if (sneaking && active.test(shift)) {
            out.add(shift);
            if (!shiftOverrides) {
                out.add(base);
            }
            return;
        }
        out.add(base);
    }
}
