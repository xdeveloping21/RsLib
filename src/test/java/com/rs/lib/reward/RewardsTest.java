package com.rs.lib.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.action.RequirementEngine;
import com.rs.lib.reward.RewardKit.FakeHost;
import com.rs.lib.reward.RewardKit.FakePlayer;

/** Deciding (seeded rolls, check order, picks by weight) and delivering under each overflow policy. */
class RewardsTest {

    private final FakeHost host = new FakeHost();
    private final Rewards rewards = new Rewards(host, null, "Test");
    private final FakePlayer steve = new FakePlayer();

    @AfterEach
    void stop() {
        rewards.shutdown();
    }

    /** A Random that counts its draws. */
    private static final class Counting extends Random {
        int draws;

        Counting(long seed) {
            super(seed);
        }

        @Override
        public double nextDouble() {
            draws++;
            return super.nextDouble();
        }
    }

    private RewardReport deliver(ResolvedRewards resolved, OverflowPolicy overflow) {
        CompletableFuture<RewardReport>[] future = holder();
        host.asMain(() -> future[0] = rewards.deliver(steve.player, resolved, overflow));
        return host.await(future[0]);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<RewardReport>[] holder() {
        return new CompletableFuture[1];
    }

    private static List<RewardOutcome> outcomes(RewardReport report) {
        return report.results().stream().map(RewardResult::outcome).toList();
    }

    // ------------------------------------------------------------------ deciding

    /** Same seed, same draws: each reward whose chance is between 0 and 100 draws once, in order. */
    @Test
    void seededRollsAreReproducible() {
        RewardList list = RewardList.of(
                Reward.money("a", 1).chance(50).build(),
                Reward.money("b", 1).chance(50).build(),
                Reward.money("c", 1).chance(50).build(),
                Reward.money("d", 1).chance(50).build());
        Random expected = new Random(42);
        List<Boolean> wins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            wins.add(expected.nextDouble() * 100 < 50);
        }
        ResolvedRewards resolved = rewards.resolve(steve.player, list, new Random(42));
        assertEquals(wins, resolved.entries().stream().map(ResolvedReward::won).toList());
        for (ResolvedReward entry : resolved.entries()) {
            assertEquals(entry.won() ? null : RewardOutcome.LOST_ROLL, entry.skipped());
        }
        assertEquals(steve.id, resolved.player());
    }

    @Test
    void certainAndImpossibleChancesDoNotDraw() {
        Counting random = new Counting(1);
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.money("always", 1).build(),
                Reward.money("never", 1).chance(0).build()), random);
        assertEquals(0, random.draws);
        assertTrue(resolved.entries().get(0).won());
        assertEquals(RewardOutcome.LOST_ROLL, resolved.entries().get(1).skipped());
    }

    /** Permission, then requirement, then roll; a refused reward does not draw. */
    @Test
    void permissionIsCheckedBeforeTheRequirementAndTheRoll() {
        RewardList list = RewardList.of(
                Reward.money("vip", 1).permission("crates.vip").requirement(RequirementEngine.parse(List.of("1 = 2")))
                        .chance(0).build(),
                Reward.money("level", 1).requirement(RequirementEngine.parse(List.of("{level} >= 10"))).chance(0).build(),
                Reward.money("ok", 1).permission("crates.vip").build());
        Counting random = new Counting(7);
        ResolvedRewards resolved = rewards.resolve(steve.player, list, random, Ph.of("level", 5));
        assertEquals(RewardOutcome.NO_PERMISSION, resolved.entries().get(0).skipped());
        assertEquals(RewardOutcome.REQUIREMENT_FAILED, resolved.entries().get(1).skipped());
        assertEquals(RewardOutcome.NO_PERMISSION, resolved.entries().get(2).skipped());
        assertEquals(0, random.draws);

        steve.permissions.add("crates.vip");
        resolved = rewards.resolve(steve.player, list, random, Ph.of("level", 50));
        assertEquals(RewardOutcome.REQUIREMENT_FAILED, resolved.entries().get(0).skipped());
        assertEquals(RewardOutcome.LOST_ROLL, resolved.entries().get(1).skipped());
        assertTrue(resolved.entries().get(2).won());
    }

    @Test
    void withoutAPlayerCheckedRewardsAreNotGiven() {
        ResolvedRewards resolved = rewards.resolve(null, RewardList.of(
                Reward.money("vip", 1).permission("crates.vip").build(),
                Reward.money("level", 1).requirement((player, resolver) -> true).build(),
                Reward.money("plain", 1).build()));
        assertNull(resolved.player());
        assertEquals(RewardOutcome.NO_PERMISSION, resolved.entries().get(0).skipped());
        assertEquals(RewardOutcome.REQUIREMENT_FAILED, resolved.entries().get(1).skipped());
        assertTrue(resolved.entries().get(2).won());
    }

    /** Decide now: the command and message are fixed for the player when decided. */
    @Test
    void wonRewardsAreFixedWhenDecided() {
        RewardKit.Stack gem = RewardKit.stack("gem", 5);
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", gem).build(),
                Reward.command("rank", "/lp user {player} parent add {group}").build(),
                Reward.message("note", "Hi {player}").build()), Ph.of("group", "vip"));
        assertEquals("lp user Steve parent add vip", resolved.entries().get(1).text());
        assertEquals("Hi Steve", resolved.entries().get(2).text());
        List<ItemStack> items = resolved.entries().get(0).items();
        items.get(0).setAmount(64);
        assertEquals(5, resolved.entries().get(0).items().get(0).getAmount(), "items() hands out copies");
        assertEquals(3, resolved.won().size());
    }

    @Test
    void anItemThatCannotBeReadFailsWhenDecided() {
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.serializedItem("broken", "BAD").build()));
        assertEquals(RewardOutcome.FAILED, resolved.entries().get(0).skipped());
        assertEquals(1, host.log.warnings().size());
    }

    // ------------------------------------------------------------------ picks

    @Test
    void picksFollowTheWeightsWithASeed() {
        RewardList list = RewardList.of(
                Reward.money("common", 1).weight(3).build(),
                Reward.money("rare", 1).weight(1).build(),
                Reward.money("off", 1).weight(0).build());
        int rare = 0;
        Random random = new Random(1234);
        for (int i = 0; i < 20_000; i++) {
            ResolvedRewards one = rewards.pick(steve.player, list, 1, random);
            assertEquals(1, one.won().size());
            assertFalse(one.entries().get(2).won(), "weight 0 is never picked");
            if (one.entries().get(1).won()) {
                rare++;
            }
        }
        assertEquals(0.25, rare / 20_000.0, 0.015);
    }

    @Test
    void aPickIsReproducibleAndDrawsDifferentRewards() {
        RewardList list = RewardList.of(
                Reward.money("a", 1).weight(1).build(),
                Reward.money("b", 1).weight(2).build(),
                Reward.money("c", 1).weight(3).build(),
                Reward.money("d", 1).weight(4).build());
        List<Boolean> first = rewards.pick(steve.player, list, 2, new Random(99)).entries().stream()
                .map(ResolvedReward::won).toList();
        List<Boolean> second = rewards.pick(steve.player, list, 2, new Random(99)).entries().stream()
                .map(ResolvedReward::won).toList();
        assertEquals(first, second);
        assertEquals(2, first.stream().filter(won -> won).count());
        assertEquals(4, rewards.pick(steve.player, list, 10, new Random(5)).won().size(), "at most every reward");
    }

    /** A picked reward still faces its own chance; refused rewards are never drawn. */
    @Test
    void aPickedRewardStillRollsItsChance() {
        RewardList list = RewardList.of(
                Reward.money("vip", 1).weight(1000).permission("crates.vip").build(),
                Reward.money("unlucky", 1).chance(0).build());
        ResolvedRewards resolved = rewards.pick(steve.player, list, 1, new Random(3));
        assertEquals(RewardOutcome.NO_PERMISSION, resolved.entries().get(0).skipped());
        assertEquals(RewardOutcome.LOST_ROLL, resolved.entries().get(1).skipped());
    }

    // ------------------------------------------------------------------ delivering

    @Test
    void everyTypeIsHandedOver() {
        steve.permissions.add("crates.vip");
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 5)).build(),
                Reward.money("coins", 250).build(),
                Reward.command("rank", "say {player}").build(),
                Reward.experience("xp", 30).build(),
                Reward.potion("speed", "speed", 200, 1).build(),
                Reward.message("note", "Well done").build(),
                Reward.money("lost", 1).chance(0).build()));
        RewardReport report = deliver(resolved, OverflowPolicy.DROP);
        assertEquals(List.of(RewardOutcome.GIVEN, RewardOutcome.GIVEN, RewardOutcome.GIVEN, RewardOutcome.GIVEN,
                RewardOutcome.GIVEN, RewardOutcome.GIVEN, RewardOutcome.LOST_ROLL), outcomes(report));
        assertEquals(5, steve.count("gem"));
        assertEquals(List.of(250.0), host.money);
        assertEquals(List.of("say Steve"), host.commands);
        assertEquals(List.of(30), steve.exp);
        assertEquals(List.of("speed 200 1"), host.potions);
        assertEquals(List.of("Well done"), host.messages);
        assertTrue(report.anyGiven());
        assertTrue(report.failures().isEmpty());
        assertEquals(RewardOutcome.LOST_ROLL, report.get("lost").outcome());
    }

    @Test
    void brokenRewardsAreFailuresNotSkips() {
        host.moneyWorks = false;
        host.commandsWork = false;
        RewardReport report = deliver(rewards.resolve(steve.player, RewardList.of(
                Reward.money("coins", 250).build(),
                Reward.command("rank", "nosuchcommand").build(),
                Reward.potion("nope", "nope", 20, 0).build())), OverflowPolicy.DROP);
        assertEquals(List.of(RewardOutcome.FAILED, RewardOutcome.FAILED, RewardOutcome.FAILED), outcomes(report));
        assertEquals(3, report.failures().size());
        assertEquals(3, host.log.warnings().size(), host.log.warnings().toString());
    }

    @Test
    void dropPolicyDropsWhatDoesNotFit() {
        steve.fill(1);
        RewardReport report = deliver(rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 100)).build())), OverflowPolicy.DROP);
        assertEquals(RewardOutcome.GIVEN, report.get("gem").outcome());
        assertEquals(64, steve.count("gem"));
        assertEquals(36, steve.droppedCount("gem"));
    }

    /** FAIL gives nothing at all of a reward that does not fit whole; the next reward is independent. */
    @Test
    void failPolicyGivesNothingOfARewardThatDoesNotFit() {
        steve.fill(1);
        RewardReport report = deliver(rewards.resolve(steve.player, RewardList.of(
                Reward.item("big", RewardKit.stack("gem", 100)).build(),
                Reward.item("small", RewardKit.stack("rod", 1)).build())), OverflowPolicy.FAIL);
        assertEquals(List.of(RewardOutcome.NO_SPACE, RewardOutcome.GIVEN), outcomes(report));
        assertEquals(0, steve.count("gem"));
        assertEquals(1, steve.count("rod"));
        assertTrue(steve.dropped.isEmpty());
        assertEquals(1, report.failures().size());
    }

    @Test
    void queueWithoutAStoreDropsInstead() {
        steve.fill(0);
        RewardReport report = deliver(rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 10)).build())), OverflowPolicy.QUEUE);
        assertEquals(RewardOutcome.GIVEN, report.get("gem").outcome());
        assertEquals(10, steve.droppedCount("gem"));
        assertEquals(1, host.log.warnings().size());
    }

    /** Exactly once in memory: a decided result pays out a single time. */
    @Test
    void aDecidedResultIsDeliveredOnlyOnce() {
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 5)).build(),
                Reward.money("coins", 10).build(),
                Reward.money("lost", 1).chance(0).build()));
        deliver(resolved, OverflowPolicy.DROP);
        assertTrue(resolved.delivered());
        RewardReport again = deliver(resolved, OverflowPolicy.DROP);
        assertEquals(List.of(RewardOutcome.FAILED, RewardOutcome.FAILED, RewardOutcome.LOST_ROLL), outcomes(again));
        assertEquals(5, steve.count("gem"));
        assertEquals(List.of(10.0), host.money);
    }

    @Test
    void aResultDecidedForSomeoneElseIsRefused() {
        FakePlayer alex = new FakePlayer("Alex");
        ResolvedRewards resolved = rewards.resolve(alex.player, RewardList.of(Reward.money("coins", 10).build()));
        assertThrows(IllegalArgumentException.class, () -> rewards.deliver(steve.player, resolved));
        assertFalse(resolved.delivered());
        assertTrue(host.money.isEmpty());
    }

    /** Called off the player's thread, the delivery hops there first. */
    @Test
    void deliveryHopsToThePlayersThread() {
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 5)).build()));
        CompletableFuture<RewardReport> future = rewards.deliver(steve.player, resolved);
        assertFalse(future.isDone());
        assertEquals(0, steve.count("gem"));
        assertEquals(1, host.queued());
        assertEquals(RewardOutcome.GIVEN, host.await(future).get("gem").outcome());
        assertEquals(5, steve.count("gem"));
    }

    /** Without a store, a reward owed to a player who is not online is a loud failure, never silent. */
    @Test
    void anOfflinePlayerWithoutAStoreIsALoudFailure() {
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(Reward.money("coins", 10).build()));
        RewardReport report = rewards.deliver(UUID.fromString(steve.id.toString()), resolved).join();
        assertEquals(RewardOutcome.FAILED, report.get("coins").outcome());
        assertEquals(1, host.log.messages(java.util.logging.Level.SEVERE).size());
        assertThrows(UnsupportedOperationException.class, () -> rewards.claim(steve.player));
        assertThrows(UnsupportedOperationException.class, () -> rewards.pendingCount(steve.id));
        assertFalse(rewards.keepsPending());
    }
}
