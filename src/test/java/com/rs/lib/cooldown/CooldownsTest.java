package com.rs.lib.cooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.db.DbConfig;
import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.TestDbs;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.yml.YmlTestSupport.Captured;

class CooldownsTest {

    @TempDir
    Path dir;

    private final Captured log = new Captured();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<Long> periods = new ArrayList<>();
    private final List<Runnable> timerTasks = new ArrayList<>();
    private final List<Handle> handles = new ArrayList<>();
    private final List<RsDb> opened = new ArrayList<>();
    private boolean refuseTimers;
    private final UUID player = UUID.randomUUID();

    @AfterEach
    void close() {
        opened.forEach(RsDb::shutdown);
    }

    private final class Handle implements TaskHandle {
        boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private Cooldowns cooldowns(Supplier<RsDb> db) {
        return new Cooldowns(log.logger, now::get, (period, task) -> {
            if (refuseTimers) {
                throw new IllegalStateException("plugin disabled");
            }
            periods.add(period);
            timerTasks.add(task);
            Handle handle = new Handle();
            handles.add(handle);
            return handle;
        }, db, "Test");
    }

    private Cooldowns memory() {
        return cooldowns(() -> {
            throw new UnsupportedOperationException("Test called rs().db() without declaring it");
        });
    }

    private RsDb database() {
        RsDb db = TestDbs.sqlite(dir.toFile(), new FakeDbHost());
        opened.add(db);
        return db;
    }

    @Test
    void tryUseStartsTheCooldownOnlyWhenNoneIsRunning() {
        Cooldowns cooldowns = memory();
        assertTrue(cooldowns.tryUse(player, "heal", Duration.ofSeconds(10)));
        assertFalse(cooldowns.tryUse(player, "heal", Duration.ofSeconds(1)));
        assertEquals(10_000L, cooldowns.remainingMillis(player, "heal"), "a refused use does not change the expiry");
        assertTrue(cooldowns.tryUse(player, "feed", Duration.ofSeconds(10)), "categories are independent");
        assertTrue(cooldowns.tryUse(UUID.randomUUID(), "heal", Duration.ofSeconds(10)), "players are independent");
        now.addAndGet(9_999L);
        assertEquals(1L, cooldowns.remainingMillis(player, "heal"));
        assertFalse(cooldowns.tryUse(player, "heal", Duration.ofSeconds(10)));
        now.addAndGet(1L);
        assertEquals(0L, cooldowns.remainingMillis(player, "heal"));
        assertTrue(cooldowns.tryUse(player, "heal", Duration.ofSeconds(10)));
    }

    @Test
    void zeroOrNegativeDurationsAlwaysPassAndRecordNothing() {
        Cooldowns cooldowns = memory();
        assertTrue(cooldowns.tryUse(player, "x", Duration.ZERO));
        assertTrue(cooldowns.tryUse(player, "x", Duration.ofSeconds(-5)));
        assertTrue(cooldowns.tryUseTicks(player, "x", 0));
        assertTrue(cooldowns.tryUseTicks(player, "x", -20));
        assertEquals(0, cooldowns.size("x"));
        assertTrue(periods.isEmpty(), "no cleanup task without a cooldown");
    }

    /** Trap: ticks are always 50 ms, whatever the TPS. */
    @Test
    void ticksAreFiftyMilliseconds() {
        Cooldowns cooldowns = memory();
        assertTrue(cooldowns.tryUseTicks(player, "jump", 20));
        assertEquals(1_000L, cooldowns.remainingMillis(player, "jump"));
        assertTrue(cooldowns.tryUseTicks(player, "huge", Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - now.get(), cooldowns.remainingMillis(player, "huge"));
        assertTrue(cooldowns.tryUse(player, "forever", Duration.ofSeconds(Long.MAX_VALUE)));
        assertFalse(cooldowns.tryUse(player, "forever", Duration.ofSeconds(1)));
    }

    @Test
    void expiredEntriesAreDroppedLazily() {
        Cooldowns cooldowns = memory();
        cooldowns.tryUse(player, "x", Duration.ofMillis(10));
        assertEquals(1, cooldowns.size("x"));
        now.addAndGet(10L);
        assertEquals(0L, cooldowns.remainingMillis(player, "x"));
        assertEquals(0, cooldowns.size("x"));
        assertEquals(0L, cooldowns.remainingMillis(player, "unknown"));
    }

    /** Trap: cooldowns survive a relog; only session categories are cleared when the player leaves. */
    @Test
    void relogKeepsCooldownsExceptSessionCategories() {
        Cooldowns cooldowns = memory();
        cooldowns.registerSessionCategory("combat");
        cooldowns.tryUse(player, "combat", Duration.ofMinutes(1));
        cooldowns.tryUse(player, "kit", Duration.ofMinutes(1));
        UUID other = UUID.randomUUID();
        cooldowns.tryUse(other, "combat", Duration.ofMinutes(1));
        cooldowns.clearSession(player);
        assertEquals(0L, cooldowns.remainingMillis(player, "combat"));
        assertEquals(60_000L, cooldowns.remainingMillis(player, "kit"));
        assertEquals(60_000L, cooldowns.remainingMillis(other, "combat"));
    }

    @Test
    void categoryNamesAreExactKeys() {
        Cooldowns cooldowns = memory();
        cooldowns.tryUse(player, "Kit", Duration.ofMinutes(1));
        assertEquals(0L, cooldowns.remainingMillis(player, "kit"));
    }

    @Test
    void clearAllForgetsEverything() {
        Cooldowns cooldowns = memory();
        cooldowns.tryUse(player, "a", Duration.ofMinutes(1));
        cooldowns.tryUse(player, "b", Duration.ofMinutes(1));
        cooldowns.clearAll();
        assertTrue(cooldowns.tryUse(player, "a", Duration.ofMinutes(1)));
        assertEquals(0L, cooldowns.remainingMillis(player, "b"));
    }

    /** clearAll also stops the cleanup task; the next armed cooldown starts a fresh one. */
    @Test
    void clearAllStopsTheCleanupUntilTheNextUse() {
        Cooldowns cooldowns = memory();
        cooldowns.tryUse(player, "a", Duration.ofMinutes(1));
        assertEquals(1, handles.size());
        cooldowns.clearAll();
        assertTrue(handles.get(0).isCancelled());
        cooldowns.clearAll();
        assertEquals(1, handles.size(), "clearing again schedules nothing");
        assertTrue(cooldowns.tryUse(player, "a", Duration.ofMinutes(1)));
        assertEquals(2, handles.size());
        assertFalse(handles.get(1).isCancelled());
        assertEquals(List.of(6000L, 6000L), periods);
    }

    @Test
    void cleanupRunsEveryFiveMinutesAndDropsOnlyExpiredEntries() {
        Cooldowns cooldowns = memory();
        cooldowns.tryUse(player, "short", Duration.ofSeconds(1));
        cooldowns.tryUse(player, "long", Duration.ofHours(1));
        cooldowns.tryUse(UUID.randomUUID(), "short", Duration.ofHours(1));
        assertEquals(List.of(6000L), periods, "one cleanup task, every 5 minutes");
        now.addAndGet(5_000L);
        timerTasks.get(0).run();
        assertEquals(1, cooldowns.size("short"));
        assertEquals(1, cooldowns.size("long"));
    }

    /** If the cleanup cannot be scheduled, warn once and try again on the next use. */
    @Test
    void cleanupSchedulingFailureWarnsOnceAndRetries() {
        Cooldowns cooldowns = memory();
        refuseTimers = true;
        cooldowns.tryUse(player, "a", Duration.ofSeconds(1));
        cooldowns.tryUse(player, "b", Duration.ofSeconds(1));
        assertEquals(1, log.warnings().size());
        assertTrue(log.warnings().get(0).startsWith("Could not schedule the cooldown cleanup"));
        refuseTimers = false;
        cooldowns.tryUse(player, "c", Duration.ofSeconds(1));
        cooldowns.tryUse(player, "d", Duration.ofSeconds(1));
        assertEquals(List.of(6000L), periods);
    }

    @Test
    void onlyOneConcurrentUseWins() throws InterruptedException {
        Cooldowns cooldowns = memory();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        for (int i = 0; i < 64; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (cooldowns.tryUse(player, "race", Duration.ofMinutes(1))) {
                    granted.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, granted.get());
    }

    /** Trap: by default nothing is stored; a restart clears every cooldown. */
    @Test
    void notPersistedByDefault() {
        RsDb db = database();
        Cooldowns before = cooldowns(() -> db);
        before.tryUse(player, "kit", Duration.ofHours(1));
        before.shutdown();
        Cooldowns after = cooldowns(() -> db);
        assertEquals(0L, after.remainingMillis(player, "kit"));
        assertFalse(db.query("SELECT name FROM sqlite_master WHERE name = 'rslib_cooldowns'", null, rows -> rows.next())
                .join(), "the database is not touched");
    }

    @Test
    void persistentCategoriesSurviveARestart() {
        RsDb db = database();
        Cooldowns before = cooldowns(() -> db);
        before.registerPersistentCategory("kit").join();
        assertEquals(List.of(600L), periods, "writes are batched every 30 seconds");
        before.tryUse(player, "kit", Duration.ofHours(1));
        before.tryUse(player, "heal", Duration.ofHours(1));
        before.shutdown();
        db.shutdown();

        now.addAndGet(60_000L);
        RsDb reopened = database();
        Cooldowns after = cooldowns(() -> reopened);
        assertEquals(0L, after.remainingMillis(player, "kit"), "not loaded before the category is registered");
        after.registerPersistentCategory("kit").join();
        assertEquals(3_540_000L, after.remainingMillis(player, "kit"));
        assertEquals(0L, after.remainingMillis(player, "heal"), "other categories stay in memory only");
    }

    @Test
    void persistentWritesAreBatchedAndClearsAreStored() {
        RsDb db = database();
        Cooldowns cooldowns = cooldowns(() -> db);
        cooldowns.registerPersistentCategory("kit").join();
        cooldowns.registerSessionCategory("kit");
        UUID other = UUID.randomUUID();
        cooldowns.tryUse(player, "kit", Duration.ofHours(1));
        cooldowns.tryUse(other, "kit", Duration.ofHours(1));
        assertEquals(0, TestDbs.count(db, "rslib_cooldowns"), "nothing written before the batch");
        timerTasks.get(periods.indexOf(CooldownStore.FLUSH_PERIOD_TICKS)).run();
        db.query("SELECT 1", null, rows -> 1).join();
        assertEquals(2, TestDbs.count(db, "rslib_cooldowns"));

        cooldowns.clearSession(player);
        timerTasks.get(periods.indexOf(CooldownStore.FLUSH_PERIOD_TICKS)).run();
        db.query("SELECT 1", null, rows -> 1).join();
        assertEquals(1, TestDbs.count(db, "rslib_cooldowns"));

        cooldowns.tryUse(player, "kit", Duration.ofHours(1));
        cooldowns.clearAll();
        cooldowns.tryUse(other, "kit", Duration.ofMinutes(2));
        cooldowns.shutdown();
        db.shutdown();
        RsDb reopened = database();
        Cooldowns after = cooldowns(() -> reopened);
        after.registerPersistentCategory("kit").join();
        assertEquals(0L, after.remainingMillis(player, "kit"), "cleared by clearAll");
        assertEquals(120_000L, after.remainingMillis(other, "kit"), "a use after clearAll is kept");
    }

    @Test
    void expiredRowsAreDroppedWhenLoading() {
        RsDb db = database();
        Cooldowns before = cooldowns(() -> db);
        before.registerPersistentCategory("kit").join();
        before.tryUse(player, "kit", Duration.ofSeconds(10));
        before.shutdown();
        now.addAndGet(20_000L);
        Cooldowns after = cooldowns(() -> db);
        after.registerPersistentCategory("kit").join();
        assertEquals(0L, after.remainingMillis(player, "kit"));
        assertEquals(0, TestDbs.count(db, "rslib_cooldowns"));
    }

    @Test
    void persistenceNeedsTheDatabaseAndAValidName() {
        Cooldowns cooldowns = memory();
        assertThrows(UnsupportedOperationException.class, () -> cooldowns.registerPersistentCategory("kit"));
        Cooldowns withDb = cooldowns(this::database);
        assertThrows(IllegalArgumentException.class, () -> withDb.registerPersistentCategory(" "));
        assertThrows(IllegalArgumentException.class, () -> withDb.registerPersistentCategory("x".repeat(65)));
    }

    @Test
    void sqlForBothDialects() {
        assertTrue(CooldownStore.upsertSql(DbConfig.Type.SQLITE)
                .endsWith("ON CONFLICT(owner, category, uuid) DO UPDATE SET expires_at=excluded.expires_at"));
        assertTrue(CooldownStore.upsertSql(DbConfig.Type.MYSQL)
                .endsWith("ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at)"));
    }
}
