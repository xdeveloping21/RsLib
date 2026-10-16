package com.rs.lib.notice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.Ph;
import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.TestDbs;
import com.rs.lib.notice.FakeNoticeHost.Shown;

class NoticesTest {

    @TempDir
    Path dir;

    private final FakeNoticeHost host = new FakeNoticeHost();
    private final UUID steve = UUID.randomUUID();
    private RsDb db;
    private Notices notices;

    @BeforeEach
    void open() {
        db = TestDbs.sqlite(dir.toFile(), new FakeDbHost());
        host.db = db;
        notices = new Notices(host, "Market", () -> { });
        notices.category("sold").summary("notices.sold-summary");
        notices.category("expired");
    }

    @AfterEach
    void close() {
        db.shutdown();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Notices.Outcome send(UUID player, String category, String key, Ph... values) {
        return await(notices.deliver(player, category, key, values));
    }

    private int stored() {
        return TestDbs.count(db, NoticeStore.TABLE);
    }

    private static Map<String, String> values(Shown shown) {
        return shown.phs().stream().collect(Collectors.toMap(Ph::key, Ph::value));
    }

    @Test
    void anOnlinePlayerSeesTheMessageAtOnceAndNothingIsStored() {
        host.online.add(steve);
        assertEquals(Notices.Outcome.SHOWN, send(steve, "sold", "notices.sold", Ph.of("price", 10)));
        assertEquals(List.of(new Shown(steve, "notices.sold", List.of(Ph.of("price", 10)))), host.shown());
        assertEquals(0, stored());
    }

    @Test
    void anOfflinePlayerGetsOneSummaryPerCategoryOnJoinInTheOrderTheyArrived() {
        assertEquals(Notices.Outcome.STORED, send(steve, "expired", "notices.expired", Ph.literal("item", "Dirt")));
        send(steve, "sold", "notices.sold", Ph.literal("item", "Diamond x16"), Ph.of("price", 4500));
        send(steve, "sold", "notices.sold", Ph.literal("item", "Stone"), Ph.of("price", "0.25"));
        send(steve, "expired", "notices.expired", Ph.literal("item", "Sand"));
        send(steve, "sold", "notices.sold", Ph.literal("item", "Gold"), Ph.of("price", "100.5"));
        assertEquals(5, stored());
        assertTrue(host.shown().isEmpty());

        host.online.add(steve);
        assertEquals(5, await(notices.claim(steve)));
        List<Shown> shown = host.shown();
        assertEquals(List.of("notices.expired", "notices.expired", "notices.sold-summary"),
                shown.stream().map(Shown::key).toList(), "expired came first; its notices are listed one by one");
        assertEquals("Dirt", values(shown.get(0)).get("item"));
        assertEquals("Sand", values(shown.get(1)).get("item"));
        Map<String, String> summary = values(shown.get(2));
        assertEquals("3", summary.get("count"));
        assertEquals("4600.75", summary.get("sum_price"));
        assertFalse(summary.containsKey("sum_item"), "values that are not numbers are not added up");
        assertEquals(0, stored());
    }

    /** Trap: every notice is shown once, taken by deleting its row. */
    @Test
    void aNoticeIsShownOnlyOnceEvenWhenTwoClaimsRace() {
        for (int i = 0; i < 8; i++) {
            send(steve, "expired", "notices.expired", Ph.of("n", i));
        }
        host.online.add(steve);
        CompletableFuture<Integer> first = notices.claim(steve);
        CompletableFuture<Integer> second = notices.claim(steve);
        assertEquals(8, await(first) + await(second));
        assertEquals(0, await(notices.claim(steve)));
        assertEquals(0, stored());
    }

    /** Trap: a row another server took between the read and the delete is not shown here. */
    @Test
    void onlyTheDeleteThatRemovedARowShowsIt() {
        for (int i = 0; i < 3; i++) {
            send(steve, "expired", "notices.expired", Ph.of("n", i));
        }
        NoticeStore store = new NoticeStore("Market");
        List<List<Notice>> taken = new ArrayList<>();
        long[] elsewhere = new long[1];
        db.transaction(connection -> {
            List<Notice> read = new ArrayList<>();
            try (var select = connection.prepareStatement("SELECT id FROM " + NoticeStore.TABLE + " ORDER BY id")) {
                try (var rows = select.executeQuery()) {
                    while (rows.next()) {
                        read.add(new Notice(rows.getLong(1), "expired", "notices.expired", List.of(), 0));
                    }
                }
            }
            try (var other = connection.prepareStatement("DELETE FROM " + NoticeStore.TABLE + " WHERE id = ?")) {
                elsewhere[0] = read.get(1).id();
                other.setLong(1, elsewhere[0]);
                other.executeUpdate();
            }
            taken.add(store.deleteEach(connection, read));
        }).join();
        assertEquals(2, taken.get(0).size());
        assertFalse(taken.get(0).stream().anyMatch(notice -> notice.id() == elsewhere[0]), "the second row went elsewhere");
        assertEquals(0, stored());
    }

    @Test
    void aCategoryWithoutSummaryShowsFiveAndHowManyMore() {
        for (int i = 0; i < 7; i++) {
            send(steve, "expired", "notices.expired", Ph.of("n", i));
        }
        host.online.add(steve);
        await(notices.claim(steve));
        List<Shown> shown = host.shown();
        assertEquals(6, shown.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(String.valueOf(i), values(shown.get(i)).get("n"), "oldest first");
        }
        assertEquals(Notices.MORE_KEY, shown.get(5).key());
        assertEquals("2", values(shown.get(5)).get("count"));
    }

    @Test
    void literalValuesStayLiteralAfterBeingStored() {
        String typed = "&cfree <click:run_command:/op me>=\nline \\ two";
        send(steve, "expired", "notices.expired", Ph.literal("note", typed), Ph.of("price", 5));
        host.online.add(steve);
        await(notices.claim(steve));
        Shown shown = host.shown().get(0);
        assertEquals(List.of(Ph.literal("note", typed), Ph.of("price", 5)), shown.phs());
    }

    @Test
    void thePerPlayerLimitRefusesNewNoticesAndWarnsOnce() {
        host.settings = new NoticeSettings(30, 2, 60);
        assertEquals(Notices.Outcome.STORED, send(steve, "expired", "notices.expired"));
        assertEquals(Notices.Outcome.STORED, send(steve, "expired", "notices.expired"));
        assertEquals(Notices.Outcome.REFUSED, send(steve, "expired", "notices.expired"));
        assertEquals(Notices.Outcome.REFUSED, send(steve, "sold", "notices.sold"));
        assertEquals(2, stored());
        assertEquals(1, host.log.warnings().size(), "one warning per player, not one per refusal");
        assertEquals(Notices.Outcome.STORED, send(UUID.randomUUID(), "expired", "notices.expired"),
                "the limit is per player");
    }

    @Test
    void noticesNobodyReadAreDeletedAfterKeepDays() {
        host.settings = new NoticeSettings(2, 500, 60);
        UUID alex = UUID.randomUUID();
        send(steve, "expired", "notices.expired", Ph.of("n", "old"));
        send(alex, "expired", "notices.expired");
        host.now.addAndGet(86_400_000L);
        send(steve, "expired", "notices.expired", Ph.of("n", "new"));
        host.now.addAndGet(86_400_000L + 1);
        assertEquals(2, await(notices.sweep()));
        assertEquals(1, stored());

        send(steve, "expired", "notices.expired", Ph.of("n", "late"));
        host.now.addAndGet(86_400_000L);
        host.online.add(steve);
        assertEquals(1, await(notices.claim(steve)), "a claim skips and deletes the ones too old");
        assertEquals("late", values(host.shown().get(0)).get("n"));
        assertEquals(0, stored());
    }

    @Test
    void theJoinWaitsTheConfiguredDelayAndOnlyShowsToPlayersStillOnline() {
        send(steve, "expired", "notices.expired");
        host.settings = new NoticeSettings(30, 500, 100);
        assertEquals(1, host.joinListeners.size(), "the first category registered the join listener");
        host.online.add(steve);
        host.joinListeners.get(0).accept(steve);
        assertEquals(100L, host.later.get(0).ticks());
        host.online.remove(steve);
        host.runLater();
        assertEquals(1, stored(), "left before the delay: the notice keeps waiting");
        assertTrue(host.shown().isEmpty());

        host.online.add(steve);
        host.holdPlayerTasks = true;
        host.joinListeners.get(0).accept(steve);
        host.runLater();
        host.awaitPlayerTasks(1).get(0).task().run();
        assertEquals(1, host.shown().size());
        assertEquals(0, stored());
    }

    @Test
    void aPlayerWhoLeavesBeforeTheMessageGetsTheNoticesBack() {
        send(steve, "expired", "notices.expired", Ph.of("n", 1));
        send(steve, "sold", "notices.sold", Ph.of("price", 2));
        host.online.add(steve);
        host.holdPlayerTasks = true;
        CompletableFuture<Integer> claim = notices.claim(steve);
        FakeNoticeHost.PlayerTask task = host.awaitPlayerTasks(1).get(0);
        host.online.remove(steve);
        task.task().run();
        assertEquals(0, await(claim));
        await(notices.sweep());
        assertEquals(2, stored());

        host.online.add(steve);
        claim = notices.claim(steve);
        host.awaitPlayerTasks(1).get(0).retired().run();
        assertEquals(0, await(claim));
        await(notices.sweep());
        assertEquals(2, stored(), "a task that can never run writes them back too");

        host.holdPlayerTasks = false;
        assertEquals(2, await(notices.claim(steve)));
        assertEquals(List.of("notices.expired", "notices.sold-summary"), host.shown().stream().map(Shown::key).toList());
    }

    @Test
    void aLiveMessageToAPlayerWhoJustLeftIsStored() {
        host.online.add(steve);
        host.holdPlayerTasks = true;
        CompletableFuture<Notices.Outcome> outcome = notices.deliver(steve, "expired", "notices.expired");
        FakeNoticeHost.PlayerTask task = host.awaitPlayerTasks(1).get(0);
        host.online.remove(steve);
        task.task().run();
        assertEquals(Notices.Outcome.STORED, await(outcome));
        assertEquals(1, stored());
    }

    @Test
    void shutdownWritesBackWhatWasTakenButNotShown() {
        send(steve, "expired", "notices.expired");
        send(steve, "expired", "notices.expired");
        host.online.add(steve);
        host.holdPlayerTasks = true;
        CompletableFuture<Integer> claim = notices.claim(steve);
        FakeNoticeHost.PlayerTask task = host.awaitPlayerTasks(1).get(0);
        notices.shutdown();
        assertTrue(host.stopped, "the join listener is gone");
        task.task().run();
        assertEquals(0, await(claim));
        assertTrue(host.shown().isEmpty());
        assertEquals(2, TestDbs.count(db, NoticeStore.TABLE));
    }

    @Test
    void playersOnlineWhenTheModuleStartsAreGreetedAfterTheDelay() {
        FakeNoticeHost other = new FakeNoticeHost();
        other.db = db;
        other.online.add(steve);
        Notices fresh = new Notices(other, "Market", () -> { });
        fresh.category("sold");
        assertEquals(1, other.later.size());
        assertEquals(List.of(Notices.SWEEP_PERIOD_TICKS), other.timers);
    }

    @Test
    void notifyingNeedsADeclaredCategoryAndAMessageKey() {
        assertThrows(IllegalArgumentException.class, () -> notices.send(steve, "unknown", "notices.x"));
        assertThrows(IllegalArgumentException.class, () -> notices.send(steve, "sold", " "));
        assertThrows(IllegalArgumentException.class, () -> notices.category(""));
        assertThrows(IllegalArgumentException.class, () -> notices.category("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> notices.category("sold").summary(" "));
        Notices withoutLang = new Notices(host, "Market", () -> {
            throw new UnsupportedOperationException("Market called rs().lang() without declaring it");
        });
        assertThrows(UnsupportedOperationException.class, () -> withoutLang.category("sold"));
    }

    @Test
    void pluginsSharingADatabaseOnlyTakeTheirOwnNotices() {
        Notices other = new Notices(host, "Crates", () -> { });
        other.category("keys");
        await(other.deliver(steve, "keys", "notices.keys"));
        send(steve, "expired", "notices.expired");
        host.online.add(steve);
        assertEquals(1, await(notices.claim(steve)));
        assertEquals(1, stored());
        assertEquals(1, await(other.claim(steve)));
    }

    @Test
    void summaryTotalsCountAndAddUpNumbers() {
        List<Notice> group = new ArrayList<>();
        group.add(new Notice(1, "sold", "k", List.of(Ph.of("price", "1.10"), Ph.of("qty", 2)), 0));
        group.add(new Notice(2, "sold", "k", List.of(Ph.of("price", "-0.10"), Ph.of("qty", "x")), 0));
        Map<String, String> totals = java.util.Arrays.stream(Notices.totals(group))
                .collect(Collectors.toMap(Ph::key, Ph::value));
        assertEquals(Map.of("count", "2", "sum_price", "1", "sum_qty", "2"), totals);
    }
}
