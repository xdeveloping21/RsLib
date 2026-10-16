package com.rs.lib.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import com.rs.lib.action.Requirement;

class RewardListTest {

    private final List<String> warnings = new ArrayList<>();

    private RewardList read(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new AssertionError(e);
        }
        return RewardList.read(config.getConfigurationSection("rewards"), "crates.yml -> rewards", null, warnings::add);
    }

    @Test
    void readsEveryType() {
        RewardList list = read("""
                rewards:
                  stored:
                    type: item
                    item: "AAAA"
                    chance: 25
                    weight: 10
                    permission: crates.vip
                    requirements: ["%player_level% >= 10"]
                  coins:
                    type: MONEY
                    amount: 500.5
                  rank:
                    type: command
                    command: "/lp user {player} parent add vip"
                  xp:
                    type: experience
                    amount: 250
                  speed:
                    type: potion
                    potion: "speed 30 1"
                  note:
                    type: message
                    message: ["&aYou won!", "&7Well done"]
                """);
        assertEquals(List.of("stored", "coins", "rank", "xp", "speed", "note"),
                list.rewards().stream().map(Reward::id).toList());
        assertTrue(warnings.isEmpty(), warnings.toString());
        Reward stored = list.get("stored");
        assertEquals(RewardType.ITEM, stored.type());
        assertEquals("AAAA", stored.serializedItem());
        assertEquals(25, stored.chance());
        assertEquals(10, stored.weight());
        assertEquals("crates.vip", stored.permission());
        assertTrue(stored.requirement() != Requirement.ALWAYS);
        assertEquals(500.5, list.get("coins").amount());
        assertEquals(Reward.ALWAYS, list.get("coins").chance());
        assertEquals(1, list.get("coins").weight());
        assertNull(list.get("coins").permission());
        assertSame(Requirement.ALWAYS, list.get("coins").requirement());
        assertEquals("lp user {player} parent add vip", list.get("rank").text());
        assertEquals(250, list.get("xp").amount());
        Reward speed = list.get("speed");
        assertEquals("speed", speed.effect());
        assertEquals(600, speed.ticks());
        assertEquals(1, speed.amplifier());
        assertEquals("&aYou won!\n&7Well done", list.get("note").text());
    }

    @Test
    void anItemWrittenWithKeysKeepsItsAppearance() {
        RewardList list = read("""
                rewards:
                  gems:
                    type: ITEM
                    material: DIAMOND
                    amount: 4
                """);
        assertNotNull(list.get("gems").appearance());
        assertNull(list.get("gems").serializedItem());
    }

    @Test
    void potionDefaultsMatchThePotionAction() {
        Reward speed = read("""
                rewards:
                  speed:
                    type: POTION
                    potion: speed
                """).get("speed");
        assertEquals(200, speed.ticks());
        assertEquals(0, speed.amplifier());
    }

    /** A broken entry is reported with the file and the key, and skipped; the rest still load. */
    @Test
    void brokenEntriesAreSkippedAndReported() {
        RewardList list = read("""
                rewards:
                  notype:
                    amount: 5
                  wrongtype:
                    type: SWORD
                  nomoney:
                    type: MONEY
                    amount: -3
                  fractionxp:
                    type: EXPERIENCE
                    amount: 2.5
                  noitem:
                    type: ITEM
                  slash:
                    type: COMMAND
                    command: "/"
                  flat: "not a section"
                  good:
                    type: MONEY
                    amount: 1
                """);
        assertEquals(List.of("good"), list.rewards().stream().map(Reward::id).toList());
        assertEquals(7, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("crates.yml -> rewards -> notype: "), warnings.get(0));
    }

    @Test
    void chanceOutsideTheRangeIsClampedAndWeightBelowZeroIsZero() {
        RewardList list = read("""
                rewards:
                  a:
                    type: MONEY
                    amount: 1
                    chance: 150
                    weight: -2
                  b:
                    type: MONEY
                    amount: 1
                    chance: "lots"
                """);
        assertEquals(100, list.get("a").chance());
        assertEquals(0, list.get("a").weight());
        assertEquals(100, list.get("b").chance());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    /** Fail closed: a requirement list that cannot be read as written never lets the reward through. */
    @Test
    void requirementsThatAreNotAListNeverHold() {
        RewardList list = read("""
                rewards:
                  a:
                    type: MONEY
                    amount: 1
                    requirements: "%player_level% >= 10"
                  b:
                    type: MONEY
                    amount: 1
                    requirements: ["%player_level% >="]
                """);
        assertSame(Requirement.NEVER, list.get("a").requirement());
        assertFalse(list.get("b").requirement().test(null, s -> "50"));
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void aMissingSectionIsTheEmptyList() {
        assertSame(RewardList.EMPTY, RewardList.read(null, "x", null, warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void builtListsRefuseDuplicateIdsAndBadValues() {
        assertThrows(IllegalArgumentException.class, () -> RewardList.of(Reward.money("a", 1).build(),
                Reward.money("a", 2).build()));
        assertThrows(IllegalArgumentException.class, () -> Reward.money("a", 0));
        assertThrows(IllegalArgumentException.class, () -> Reward.money("a", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Reward.experience("a", 0));
        assertThrows(IllegalArgumentException.class, () -> Reward.money("a", 1).chance(101));
        assertThrows(IllegalArgumentException.class, () -> Reward.money("a", 1).weight(-1));
        assertThrows(IllegalArgumentException.class, () -> Reward.command(" ", "say hi"));
        assertThrows(IllegalArgumentException.class, () -> Reward.potion("a", "speed", 0, 0));
    }

    @Test
    void anItemRewardKeepsItsOwnCopy() {
        RewardKit.Stack stack = RewardKit.stack("gem", 3);
        Reward reward = Reward.item("gem", stack).build();
        stack.setAmount(50);
        assertEquals(3, reward.item().getAmount());
        reward.item().setAmount(9);
        assertEquals(3, reward.item().getAmount());
    }
}
