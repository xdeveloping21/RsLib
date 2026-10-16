package com.rs.lib.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.TestDbs;
import com.rs.lib.db.internal.MainHops;
import com.rs.lib.reward.RewardKit.FakeHost;
import com.rs.lib.reward.RewardKit.FakePlayer;

/** Rewards kept in SQLite: kept for players who are not online, claimed exactly once, never lost. */
class PendingRewardsTest {

    @TempDir
    Path dir;

    private final FakeHost host = new FakeHost();
    private final FakeDbHost dbHost = new FakeDbHost();
    private final FakePlayer steve = new FakePlayer();
    private final List<RsDb> opened = new ArrayList<>();
    private RsDb db;
    private PendingStore store;
    private Rewards rewards;

    @BeforeEach
    void open() {
        db = TestDbs.sqlite(dir.toFile(), dbHost);
        opened.add(db);
        store = new PendingStore(db, "Test", System::currentTimeMillis);
        rewards = new Rewards(host, store, "Test");
        host.quit(steve);
    }

    @AfterEach
    void close() {
        rewards.shutdown();
        MainHops.release(host.owner);
        opened.forEach(RsDb::shutdown);
    }

    private RewardList prizes() {
        return RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 10)).build(),
                Reward.money("coins", 250).build(),
                Reward.command("rank", "say {player}").build(),
                Reward.experience("xp", 30).build(),
                Reward.potion("speed", "speed", 200, 1).build(),
                Reward.message("note", "Well done {player}").build(),
                Reward.money("lost", 1).chance(0).build());
    }

    private int count() {
        return store.count(steve.id).join();
    }

    private RewardReport claim() {
        @SuppressWarnings("unchecked")
        CompletableFuture<RewardReport>[] future = new CompletableFuture[1];
        host.asMain(() -> future[0] = rewards.claim(steve.player));
        return host.await(future[0]);
    }

    private static List<RewardOutcome> outcomes(RewardReport report) {
        return report.results().stream().map(RewardResult::outcome).toList();
    }

    /** Offline: everything won waits in the table; on claim it arrives once, exactly as decided. */
    @Test
    void offlineRewardsAreKeptAndClaimedExactlyOnce() {
        ResolvedRewards resolved = rewards.resolve(steve.player, prizes());
        RewardReport kept = rewards.deliver(steve.id, resolved).join();
        assertEquals(List.of(RewardOutcome.QUEUED, RewardOutcome.QUEUED, RewardOutcome.QUEUED, RewardOutcome.QUEUED,
                RewardOutcome.QUEUED, RewardOutcome.QUEUED, RewardOutcome.LOST_ROLL), outcomes(kept));
        assertEquals(6, count());
        assertEquals(6, rewards.pendingCount(steve.id).join());

        host.join(steve);
        RewardReport claimed = claim();
        assertEquals(6, claimed.count(RewardOutcome.GIVEN), claimed.toString());
        assertEquals(List.of("gem", "coins", "rank", "xp", "speed", "note"),
                claimed.results().stream().map(RewardResult::id).toList());
        assertEquals(10, steve.count("gem"));
        assertEquals(List.of(250.0), host.money);
        assertEquals(List.of("say Steve"), host.commands);
        assertEquals(List.of(30), steve.exp);
        assertEquals(List.of("speed 200 1"), host.potions);
        assertEquals(List.of("Well done Steve"), host.messages);
        assertEquals(List.of(Rewards.CLAIMED + " count=6"), host.sent);
        assertEquals(0, count());

        assertEquals(RewardReport.EMPTY, claim());
        assertEquals(10, steve.count("gem"));
        assertEquals(1, host.money.size());
        assertEquals(1, host.commands.size());
    }

    @Test
    void aJoinClaimsByItself() {
        rewards.deliver(steve.id, rewards.resolve(steve.player, prizes())).join();
        host.join(steve);
        host.asMain(() -> rewards.joined(steve.player));
        host.runMainUntil(() -> steve.count("gem") == 10 && count() == 0);
        assertEquals(List.of(250.0), host.money);
    }

    @Test
    void anOnlinePlayerGetsItAtOnceAndOnlyTheOverflowIsKept() {
        host.join(steve);
        steve.fill(1);
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 100)).build(),
                Reward.money("coins", 5).build()));
        RewardReport report = host.await(rewards.deliver(steve.id, resolved));
        assertEquals(List.of(RewardOutcome.QUEUED, RewardOutcome.GIVEN), outcomes(report));
        assertEquals(64, steve.count("gem"));
        assertTrue(steve.dropped.isEmpty());
        assertEquals(1, count());
        assertEquals(List.of(Rewards.WAITING + " count=1"), host.sent);

        java.util.Arrays.fill(steve.slots, null);
        RewardReport claimed = claim();
        assertEquals(List.of(RewardOutcome.GIVEN), outcomes(claimed));
        assertEquals(36, steve.count("gem"));
        assertEquals(0, count());
    }

    /** A claim into a full inventory puts the rest back instead of dropping or losing it. */
    @Test
    void whatStillDoesNotFitGoesBackToTheTable() {
        rewards.deliver(steve.id, rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 10)).build()))).join();
        host.join(steve);
        steve.fill(0);
        RewardReport claimed = claim();
        assertEquals(List.of(RewardOutcome.QUEUED), outcomes(claimed));
        assertTrue(steve.dropped.isEmpty());
        assertEquals(1, count());
        assertEquals(List.of(Rewards.WAITING + " count=1"), host.sent);
    }

    /** Two servers on one database claim at the same time: every row is taken by one of them only. */
    @Test
    void concurrentTakesNeverHandOutARowTwice() throws Exception {
        List<PendingStore.Row> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(new PendingStore.Row("r" + i, RewardType.MONEY, null, i + 1));
        }
        store.put(steve.id, rows).join();
        FakeDbHost otherHost = new FakeDbHost();
        RsDb other = TestDbs.sqlite(dir.toFile(), otherHost);
        opened.add(other);
        PendingStore second = new PendingStore(other, "Test", System::currentTimeMillis);
        second.ready().join();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<PendingStore.Row>>> takes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            PendingStore target = i % 2 == 0 ? store : second;
            takes.add(pool.submit(() -> {
                start.await();
                try {
                    return target.take(steve.id, () -> false).get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException busy) {
                    return List.<PendingStore.Row>of();
                }
            }));
        }
        start.countDown();
        List<String> taken = new ArrayList<>();
        for (Future<List<PendingStore.Row>> take : takes) {
            for (PendingStore.Row row : take.get(20, TimeUnit.SECONDS)) {
                taken.add(row.entry());
            }
        }
        pool.shutdown();
        for (PendingStore.Row row : store.take(steve.id, () -> false).join()) {
            taken.add(row.entry());
        }
        Set<String> unique = new HashSet<>(taken);
        assertEquals(taken.size(), unique.size(), "a row was taken twice: " + taken);
        assertEquals(40, unique.size());
        assertEquals(0, count());
    }

    /** Delete, then give: once taken, a row is gone even if the delivery never happens (no dupe on a crash). */
    @Test
    void takingDeletesBeforeAnythingIsGiven() {
        store.put(steve.id, List.of(new PendingStore.Row("coins", RewardType.MONEY, null, 5))).join();
        assertEquals(1, store.take(steve.id, () -> false).join().size());
        assertEquals(0, count());
        assertEquals(0, store.take(steve.id, () -> false).join().size());
    }

    @Test
    void aPlayerWhoLeftBeforeTheHandOverKeepsTheirRewards() {
        rewards.deliver(steve.id, rewards.resolve(steve.player, prizes())).join();
        host.join(steve);
        CompletableFuture<RewardReport> future = rewards.claim(steve.player);
        while (host.queued() == 0) {
            Thread.onSpinWait();
        }
        host.quit(steve);
        RewardReport report = host.await(future);
        assertEquals(6, report.count(RewardOutcome.QUEUED));
        assertEquals(6, count());
        assertEquals(0, steve.count("gem"));
        assertTrue(host.money.isEmpty());
    }

    @Test
    void aClaimDuringShutdownTakesNothing() {
        rewards.deliver(steve.id, rewards.resolve(steve.player, prizes())).join();
        host.join(steve);
        host.shuttingDown = true;
        assertEquals(RewardReport.EMPTY, rewards.claim(steve.player).join());
        assertEquals(6, count());
    }

    /** The plugin shut down between the take and the hand-over: the rows go back. */
    @Test
    void rowsTakenWhenThePluginStopsArePutBack() {
        rewards.deliver(steve.id, rewards.resolve(steve.player, prizes())).join();
        host.join(steve);
        CompletableFuture<RewardReport> future = rewards.claim(steve.player);
        while (host.queued() == 0) {
            Thread.onSpinWait();
        }
        MainHops.release(host.owner);
        RewardReport report = future.join();
        assertEquals(6, report.count(RewardOutcome.QUEUED));
        assertEquals(6, count());
        host.runMainUntil(() -> host.queued() == 0);
        assertEquals(0, steve.count("gem"), "the dropped hand-over does not run");
        assertEquals(6, count());
    }

    @Test
    void moneyTheEconomyRefusedStaysOwed() {
        rewards.deliver(steve.id, rewards.resolve(steve.player, RewardList.of(Reward.money("coins", 10).build()))).join();
        host.join(steve);
        host.moneyWorks = false;
        assertEquals(List.of(RewardOutcome.QUEUED), outcomes(claim()));
        assertEquals(1, count());
        host.moneyWorks = true;
        assertEquals(List.of(RewardOutcome.GIVEN), outcomes(claim()));
        assertEquals(List.of(10.0), host.money);
        assertEquals(0, count());
    }

    @Test
    void anUnreadableRowStaysKept() {
        store.put(steve.id, List.of(new PendingStore.Row("broken", RewardType.ITEM, "BAD", 1),
                new PendingStore.Row("coins", RewardType.MONEY, null, 3))).join();
        host.join(steve);
        RewardReport report = claim();
        assertEquals(List.of(RewardOutcome.FAILED, RewardOutcome.GIVEN), outcomes(report));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count() != 1 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, count());
        assertEquals(1, host.log.messages(Level.SEVERE).size());
    }

    /** The database refuses the overflow: it is dropped at the feet, never lost. */
    @Test
    void aFailedWriteDropsTheOverflowAtTheFeet() {
        store.ready().join();
        db.shutdown();
        host.join(steve);
        steve.fill(0);
        ResolvedRewards resolved = rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 10)).build()));
        RewardReport report = host.await(rewards.deliver(steve.id, resolved));
        assertEquals(List.of(RewardOutcome.GIVEN), outcomes(report));
        assertEquals(10, steve.droppedCount("gem"));
    }

    /** The database refuses rewards owed to an offline player: a failure, written out whole in the log. */
    @Test
    void aFailedWriteForAnOfflinePlayerIsLoggedWhole() {
        store.ready().join();
        db.shutdown();
        RewardReport report = rewards.deliver(steve.id, rewards.resolve(steve.player, RewardList.of(
                Reward.item("gem", RewardKit.stack("gem", 10)).build()))).join();
        assertEquals(List.of(RewardOutcome.FAILED), outcomes(report));
        List<String> severe = host.log.messages(Level.SEVERE);
        assertTrue(severe.stream().anyMatch(line -> line.contains("gem*10*64")), severe.toString());
    }

    /** They joined while their rewards were being written: the rows are claimed as soon as they land. */
    @Test
    void rewardsWrittenWhileThePlayerJoinsAreClaimed() throws Exception {
        store.ready().join();
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<Void> blocker = db.bootstrap(connection -> {
            try {
                hold.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).toCompletableFuture();
        CompletableFuture<RewardReport> kept = rewards.deliver(steve.id, rewards.resolve(steve.player,
                RewardList.of(Reward.item("gem", RewardKit.stack("gem", 4)).build())));
        host.join(steve);
        hold.countDown();
        blocker.join();
        assertEquals(List.of(RewardOutcome.QUEUED), outcomes(kept.join()));
        host.runMainUntil(() -> steve.count("gem") == 4);
        assertEquals(0, count());
        assertFalse(host.sent.isEmpty());
    }
}
