package com.rs.lib.item;

import static com.rs.lib.item.ItemUse.LEFT_CLICK;
import static com.rs.lib.item.ItemUse.LEFT_CLICK_AIR;
import static com.rs.lib.item.ItemUse.RIGHT_CLICK;
import static com.rs.lib.item.ItemUse.RIGHT_CLICK_AIR;
import static com.rs.lib.item.ItemUse.RIGHT_CLICK_BLOCK;
import static com.rs.lib.item.ItemUse.SHIFT_LEFT_CLICK_AIR;
import static com.rs.lib.item.ItemUse.SHIFT_RIGHT_CLICK;
import static com.rs.lib.item.ItemUse.SHIFT_RIGHT_CLICK_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Trampa 5: which of the twelve uses one click runs, and in what order. */
class ItemUseTest {

    private static List<ItemUse> run(boolean right, boolean block, boolean sneaking, boolean overrides, Set<ItemUse> active) {
        return ItemUse.dispatch(right, block, sneaking, overrides, active::contains);
    }

    @Test
    void positionalUseRunsInAdditionToTheGenericOne() {
        assertEquals(List.of(RIGHT_CLICK, RIGHT_CLICK_BLOCK), run(true, true, false, true, Set.of()));
        assertEquals(List.of(RIGHT_CLICK, RIGHT_CLICK_AIR), run(true, false, false, true, Set.of()));
        assertEquals(List.of(LEFT_CLICK, LEFT_CLICK_AIR), run(false, false, false, true, Set.of()));
    }

    @Test
    void withoutSneakingShiftUsesNeverRun() {
        Set<ItemUse> all = EnumSet.allOf(ItemUse.class);
        assertEquals(List.of(RIGHT_CLICK, RIGHT_CLICK_BLOCK), run(true, true, false, true, all));
    }

    @Test
    void shiftUseWithBehaviourReplacesTheBaseByDefault() {
        Set<ItemUse> active = EnumSet.of(SHIFT_RIGHT_CLICK, SHIFT_RIGHT_CLICK_BLOCK);
        assertEquals(List.of(SHIFT_RIGHT_CLICK, SHIFT_RIGHT_CLICK_BLOCK), run(true, true, true, true, active));
    }

    @Test
    void shiftOverridesFalseRunsBothShiftFirst() {
        Set<ItemUse> active = EnumSet.of(SHIFT_RIGHT_CLICK, SHIFT_RIGHT_CLICK_BLOCK);
        assertEquals(List.of(SHIFT_RIGHT_CLICK, RIGHT_CLICK, SHIFT_RIGHT_CLICK_BLOCK, RIGHT_CLICK_BLOCK),
                run(true, true, true, false, active));
    }

    @Test
    void sneakingWithEmptyShiftUseRunsTheBase() {
        assertEquals(List.of(RIGHT_CLICK, RIGHT_CLICK_BLOCK), run(true, true, true, true, Set.of()));
        // Each pair decides on its own.
        assertEquals(List.of(LEFT_CLICK, SHIFT_LEFT_CLICK_AIR), run(false, false, true, true, EnumSet.of(SHIFT_LEFT_CLICK_AIR)));
    }

    @Test
    void yamlKeys() {
        assertEquals("right-click-actions", RIGHT_CLICK.key());
        assertEquals("shift-right-click-block-actions", SHIFT_RIGHT_CLICK_BLOCK.key());
        assertEquals("shift-left-click-air-actions", SHIFT_LEFT_CLICK_AIR.key());
        assertEquals(12, ItemUse.values().length);
    }
}
