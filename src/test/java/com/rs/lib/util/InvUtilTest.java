package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import com.rs.lib.reward.RewardKit;
import com.rs.lib.reward.RewardKit.FakePlayer;

class InvUtilTest {

    /** The old method keeps its behaviour: what does not fit is dropped at the feet in normal stacks. */
    @Test
    void defaultGiveDropsTheRestInNormalStacks() {
        FakePlayer p = new FakePlayer();
        p.fill(1);
        ItemStack gems = RewardKit.stack("gem", 200);
        InvUtil.giveItems(p.player, gems);
        assertEquals(64, p.count("gem"));
        assertEquals(136, p.droppedCount("gem"));
        assertEquals(List.of(64, 64, 8), p.dropped.stream().map(ItemStack::getAmount).toList());
        assertEquals(200, gems.getAmount(), "the caller's stack is not changed");
    }

    @Test
    void dropPolicyIsTheOldBehaviourAndReturnsNothing() {
        FakePlayer p = new FakePlayer();
        p.fill(0);
        List<ItemStack> rest = InvUtil.giveItems(p.player, InvUtil.Overflow.DROP, RewardKit.stack("gem", 10));
        assertTrue(rest.isEmpty());
        assertEquals(10, p.droppedCount("gem"));
    }

    /** FAIL: all or nothing; a partial stack that addItem had already topped up is restored. */
    @Test
    void failPolicyLeavesTheInventoryExactlyAsItWas() {
        FakePlayer p = new FakePlayer();
        p.fill(1);
        p.slots[0] = RewardKit.stack("gem", 60);
        ItemStack[] before = new ItemStack[p.slots.length];
        for (int i = 0; i < before.length; i++) {
            before[i] = p.slots[i] == null ? null : p.slots[i].clone();
        }
        ItemStack gems = RewardKit.stack("gem", 100);
        List<ItemStack> rest = InvUtil.giveItems(p.player, InvUtil.Overflow.FAIL, gems);
        assertArrayEquals(before, p.slots);
        assertEquals(List.of(RewardKit.stack("gem", 100)), rest);
        assertTrue(p.dropped.isEmpty());
        assertEquals(100, gems.getAmount());
    }

    @Test
    void failPolicyGivesEverythingWhenItFits() {
        FakePlayer p = new FakePlayer();
        p.fill(2);
        List<ItemStack> rest = InvUtil.giveItems(p.player, InvUtil.Overflow.FAIL, RewardKit.stack("gem", 64),
                RewardKit.stack("rod", 1));
        assertTrue(rest.isEmpty());
        assertEquals(64, p.count("gem"));
        assertEquals(1, p.count("rod"));
    }

    @Test
    void returnPolicyHandsBackWhatDidNotFit() {
        FakePlayer p = new FakePlayer();
        p.fill(1);
        List<ItemStack> rest = InvUtil.giveItems(p.player, InvUtil.Overflow.RETURN, RewardKit.stack("gem", 100));
        assertEquals(64, p.count("gem"));
        assertEquals(List.of(RewardKit.stack("gem", 36)), rest);
        assertTrue(p.dropped.isEmpty());
    }

    @Test
    void nothingToGiveIsAnEmptyResult() {
        FakePlayer p = new FakePlayer();
        assertTrue(InvUtil.giveItems(p.player, InvUtil.Overflow.FAIL).isEmpty());
        assertTrue(InvUtil.giveItems(p.player, InvUtil.Overflow.FAIL, (ItemStack) null).isEmpty());
        assertTrue(InvUtil.giveItems(p.player, InvUtil.Overflow.RETURN, RewardKit.stack("gem", 0)).isEmpty());
    }
}
