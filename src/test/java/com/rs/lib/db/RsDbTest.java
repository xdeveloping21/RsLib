package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link RsDb} on a real SQLite file, with a context that has no server. */
class RsDbTest {

    @TempDir
    Path dir;

    private final FakeDbHost host = new FakeDbHost();
    private final List<RsDb> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(RsDb::shutdown);
    }

    private RsDb open() {
        RsDb db = TestDbs.sqlite(dir.toFile(), host);
        opened.add(db);
        return db;
    }

    private static Schema balances() {
        return Schema.of("balances", "uuid VARCHAR(36) PRIMARY KEY", "coins BIGINT NOT NULL");
    }

    /** Trap: same file name in the plugin folder, in WAL mode. */
    @Test
    void createsDatabaseDbInWalMode() {
        RsDb db = open();
        File file = new File(dir.toFile(), "database.db");
        assertFalse(file.exists(), "nothing is opened before the first operation");
        db.bootstrap(balances()).join();
        assertTrue(file.isFile());
        assertEquals("wal", db.query("PRAGMA journal_mode", null, rows -> rows.next() ? rows.getString(1) : "").join());
        assertEquals(5000, db.query("PRAGMA busy_timeout", null, rows -> rows.next() ? rows.getInt(1) : 0).join());
    }

    @Test
    void createsMissingParentFolders() {
        DbConfig config = TestDbs.config("Test", dir.toFile(), null);
        File nested = new File(dir.toFile(), "a/b/c");
        RsDb db = TestDbs.open(host, DbConfig.load("Test", nested, host.logger(), null));
        opened.add(db);
        db.bootstrap(balances()).join();
        assertTrue(new File(nested, "database.db").isFile());
        assertEquals(new File(dir.toFile(), "database.db"), config.sqliteFile());
    }

    /** Trap: SQLite runs operations one at a time in the order they were queued. */
    @Test
    void sqliteRunsInQueueOrder() {
        RsDb db = open();
        db.bootstrap(balances());
        List<RsFuture<Integer>> writes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int n = i;
            writes.add(db.update("INSERT INTO balances (uuid, coins) VALUES (?, ?)", ps -> {
                ps.setString(1, "p" + n);
                ps.setLong(2, n);
            }));
        }
        db.update("UPDATE balances SET coins = coins + 1000 WHERE uuid = 'p7'", null);
        long coins = db.query("SELECT coins FROM balances WHERE uuid = 'p7'", null,
                rows -> rows.next() ? rows.getLong(1) : -1L).join();
        assertEquals(1007L, coins);
        assertEquals(200, TestDbs.count(db, "balances"));
        writes.forEach(write -> assertEquals(1, write.join()));
    }

    /** JDBC never runs on the main thread: it runs on the plugin's database thread with its class loader. */
    @Test
    void workRunsOnTheDatabaseThreadWithThePluginClassLoader() {
        RsDb db = open();
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<ClassLoader> loader = new AtomicReference<>();
        db.transaction(connection -> {
            thread.set(Thread.currentThread());
            loader.set(Thread.currentThread().getContextClassLoader());
        }).join();
        assertNotSame(Thread.currentThread(), thread.get());
        assertEquals("Test-db", thread.get().getName());
        assertTrue(thread.get().isDaemon());
        assertSame(host.loader, loader.get());
    }

    @Test
    void failuresFailTheFutureInsteadOfThrowing() {
        RsDb db = open();
        RsFuture<Integer> bad = db.update("INSERT INTO missing VALUES (1)", null);
        CompletionException failure = assertThrows(CompletionException.class, bad::join);
        assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals(1, db.query("SELECT 1", null, rows -> rows.next() ? rows.getInt(1) : 0).join(),
                "the database keeps working after a failure");
    }

    @Test
    void transactionCommitsOrRollsBackAndRestoresAutoCommit() {
        RsDb db = open();
        db.bootstrap(balances()).join();
        db.transaction(connection -> {
            connection.createStatement().executeUpdate("INSERT INTO balances VALUES ('a', 1)");
            connection.createStatement().executeUpdate("INSERT INTO balances VALUES ('b', 2)");
        }).join();
        assertEquals(2, TestDbs.count(db, "balances"));

        RsFuture<Void> failed = db.transaction(connection -> {
            connection.createStatement().executeUpdate("INSERT INTO balances VALUES ('c', 3)");
            throw new SQLException("boom");
        });
        assertThrows(CompletionException.class, failed::join);
        RsFuture<Void> crashed = db.transaction(connection -> {
            connection.createStatement().executeUpdate("INSERT INTO balances VALUES ('d', 4)");
            throw new IllegalStateException("bug");
        });
        assertInstanceOf(IllegalStateException.class, assertThrows(CompletionException.class, crashed::join).getCause());
        assertEquals(2, TestDbs.count(db, "balances"));
        assertTrue(db.query("SELECT 1", null, rows -> rows.getStatement().getConnection().getAutoCommit()).join());
    }

    @Test
    void closedDatabaseRefusesWork() {
        RsDb db = open();
        db.bootstrap(balances()).join();
        db.shutdown();
        db.shutdown();
        assertTrue(db.isClosed());
        CompletionException failure = assertThrows(CompletionException.class,
                () -> db.query("SELECT 1", null, rows -> 1).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals("RsDb closed: Test", failure.getCause().getMessage());
        assertThrows(IllegalStateException.class, () -> db.playerCache((d, u) -> "", (d, u, v) -> { }));
        assertTrue(db.fence().isDone());
    }

    @Test
    void shutdownLetsQueuedWorkFinish() {
        RsDb db = open();
        db.bootstrap(balances());
        for (int i = 0; i < 100; i++) {
            int n = i;
            db.update("INSERT INTO balances VALUES (?, ?)", ps -> {
                ps.setString(1, "p" + n);
                ps.setLong(2, n);
            });
        }
        db.shutdown();
        RsDb reopened = open();
        assertEquals(100, TestDbs.count(reopened, "balances"));
    }

    /** Trap: dirty player data is saved and waited for before the pool closes; the other order loses it. */
    @Test
    void playerDataIsSavedBeforeThePoolCloses() {
        RsDb db = open();
        db.bootstrap(balances()).join();
        PlayerDataCache<long[]> cache = db.playerCache(
                (d, uuid) -> new long[]{0L},
                (d, uuid, value) -> d.upsert("balances").keys("uuid", uuid).set("coins", value[0]).run());
        UUID saved = UUID.randomUUID();
        cache.load(saved);
        host.runMainUntil(() -> cache.get(saved) != null);
        cache.get(saved)[0] = 42L;
        cache.markDirty(saved);

        db.flushPlayerCaches();
        db.shutdown();
        RsDb reopened = open();
        assertEquals(42L, reopened.query("SELECT coins FROM balances WHERE uuid = ?",
                ps -> ps.setString(1, saved.toString()), rows -> rows.next() ? rows.getLong(1) : -1L).join());

        PlayerDataCache<long[]> late = reopened.playerCache((d, uuid) -> new long[]{0L},
                (d, uuid, value) -> d.upsert("balances").keys("uuid", uuid).set("coins", value[0]).run());
        UUID lost = UUID.randomUUID();
        late.load(lost);
        host.runMainUntil(() -> late.get(lost) != null);
        late.markDirty(lost);
        reopened.shutdown();
        late.saveAll();
        RsDb third = open();
        assertEquals(1, TestDbs.count(third, "balances"), "saving after the pool closed loses the data");
    }

    /** Startup work may be waited for on the main thread; other waits there warn with the caller. */
    @Test
    void mainThreadJoinWarnsOutsideStartupWork() {
        RsDb db = open();
        host.asMain(() -> db.bootstrap(connection -> pause(150)).join());
        assertTrue(host.log.warnings().isEmpty(), host.log.warnings().toString());
        assertFalse(db.bootstrapping());

        host.asMain(() -> db.transaction(connection -> pause(150)).join());
        assertEquals(1, host.log.warnings().size());
        String warning = host.log.warnings().get(0);
        assertTrue(warning.startsWith("join() is blocking the main thread of Test"), warning);
        assertTrue(warning.contains("RsDbTest"), warning);
        assertTrue(warning.split("\n {4}at ").length <= 6, warning);

        host.log.clear();
        host.shuttingDown = true;
        host.asMain(() -> db.transaction(connection -> pause(100)).join());
        assertTrue(host.log.warnings().isEmpty(), "waiting during shutdown is expected");
    }

    /** joinWithin follows the same main-thread rules as join. */
    @Test
    void mainThreadJoinWithinWarnsOutsideStartupWork() {
        RsDb db = open();
        host.asMain(() -> assertTrue(db.bootstrap(connection -> pause(150)).joinWithin(Duration.ofSeconds(5))));
        assertTrue(host.log.warnings().isEmpty(), host.log.warnings().toString());

        host.asMain(() -> assertTrue(db.transaction(connection -> pause(150)).joinWithin(Duration.ofSeconds(5))));
        assertEquals(1, host.log.warnings().size());
        String warning = host.log.warnings().get(0);
        assertTrue(warning.startsWith("joinWithin() is blocking the main thread of Test"), warning);
        assertTrue(warning.contains("RsDbTest"), warning);

        host.log.clear();
        host.shuttingDown = true;
        host.asMain(() -> assertTrue(db.transaction(connection -> pause(100)).joinWithin(Duration.ofSeconds(5))));
        assertTrue(host.log.warnings().isEmpty(), "waiting during shutdown is expected");

        host.shuttingDown = false;
        db.transaction(connection -> pause(100)).joinWithin(Duration.ofSeconds(5));
        assertTrue(host.log.warnings().isEmpty(), "waiting off the main thread is fine");
    }

    @Test
    void migrateCountsAsStartupWork() {
        RsDb db = open();
        host.asMain(() -> db.migrate(Migration.of(1, "slow", ctx -> pause(150))).join());
        assertTrue(host.log.warnings().isEmpty(), host.log.warnings().toString());
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
