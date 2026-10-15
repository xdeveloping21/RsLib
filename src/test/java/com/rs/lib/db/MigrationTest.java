package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Versioned migrations on top of tables that already exist. */
class MigrationTest {

    @TempDir
    Path dir;

    private final FakeDbHost host = new FakeDbHost();
    private final List<RsDb> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(RsDb::shutdown);
    }

    private RsDb open(FakeDbHost owner) {
        RsDb db = TestDbs.sqlite(dir.toFile(), owner);
        opened.add(db);
        return db;
    }

    private static List<String> columns(RsDb db, String table) {
        return db.query("SELECT * FROM " + table, null, rows -> {
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                names.add(rows.getMetaData().getColumnName(i));
            }
            return names;
        }).join();
    }

    /** Trap: the tables a server already has are the baseline; they are kept as they are, with their data. */
    @Test
    void existingTablesAreTheBaseline() {
        RsDb db = open(host);
        db.bootstrap(Schema.of("tops", "uuid VARCHAR(36) PRIMARY KEY", "wins BIGINT NOT NULL")).join();
        db.update("INSERT INTO tops VALUES ('a', 3)", null).join();

        assertEquals(1, db.migrate(Migration.addColumn(1, "tops", "losses", "BIGINT NOT NULL DEFAULT 0")).join());
        assertEquals(List.of("uuid", "wins", "losses"), columns(db, "tops"));
        assertEquals(3L, db.query("SELECT wins FROM tops WHERE uuid = 'a'", null,
                rows -> rows.next() ? rows.getLong(1) : -1L).join());
        assertEquals(List.of("Applied database migration 1 (add tops.losses)."), host.log.messages(Level.INFO));

        host.log.clear();
        assertEquals(1, db.migrate(Migration.addColumn(1, "tops", "losses", "BIGINT NOT NULL DEFAULT 0")).join());
        assertTrue(host.log.messages(Level.INFO).isEmpty(), "an applied migration never runs again");
        assertEquals(1, TestDbs.count(db, RsDb.MIGRATIONS_TABLE));
    }

    /** A column the plugin used to add by hand is already there: the migration is recorded, nothing fails. */
    @Test
    void addColumnSkipsAColumnThatIsAlreadyThere() {
        RsDb db = open(host);
        db.bootstrap(Schema.of("tops", "uuid VARCHAR(36) PRIMARY KEY", "Losses BIGINT")).join();
        assertEquals(1, db.migrate(Migration.addColumn(1, "TOPS", "losses", "BIGINT")).join());
        assertEquals(List.of("uuid", "Losses"), columns(db, "tops"));
    }

    @Test
    void migrationsRunOnceInVersionOrderAcrossStarts() {
        RsDb db = open(host);
        List<Integer> ran = new ArrayList<>();
        Migration two = Migration.of(2, "second", ctx -> ran.add(2));
        Migration one = Migration.sql(1, "create", "CREATE TABLE notes (id INT)", "INSERT INTO notes VALUES (1)");
        assertEquals(2, db.migrate(two, one).join());
        assertEquals(List.of(2), ran);
        assertEquals(1, TestDbs.count(db, "notes"));

        Migration three = Migration.of(3, "third", ctx -> ran.add(3));
        assertEquals(3, db.migrate(one, two, three).join());
        assertEquals(List.of(2, 3), ran);
    }

    @Test
    void aFailureRollsBackThatMigrationAndStops() {
        RsDb db = open(host);
        db.bootstrap(Schema.of("notes", "id INT")).join();
        AtomicInteger third = new AtomicInteger();
        RsFuture<Integer> run = db.migrate(
                Migration.sql(1, "one", "INSERT INTO notes VALUES (1)"),
                Migration.of(2, "two", ctx -> {
                    ctx.execute("INSERT INTO notes VALUES (2)");
                    throw new SQLException("broken");
                }),
                Migration.of(3, "three", ctx -> third.incrementAndGet()));
        CompletionException failure = assertThrows(CompletionException.class, run::join);
        assertEquals("Database migration 2 (two) failed", failure.getCause().getMessage());
        assertEquals(1, TestDbs.count(db, "notes"));
        assertEquals(0, third.get());
        assertEquals(1, TestDbs.count(db, RsDb.MIGRATIONS_TABLE));

        assertEquals(3, db.migrate(Migration.sql(1, "one", "INSERT INTO notes VALUES (1)"),
                Migration.sql(2, "two", "INSERT INTO notes VALUES (2)"),
                Migration.of(3, "three", ctx -> third.incrementAndGet())).join());
        assertEquals(2, TestDbs.count(db, "notes"));
        assertEquals(1, third.get());
    }

    @Test
    void aNewerDatabaseIsLeftAloneWithAWarning() {
        RsDb db = open(host);
        db.migrate(Migration.of(1, "a", ctx -> { }), Migration.of(2, "b", ctx -> { })).join();
        assertEquals(2, db.migrate(Migration.of(1, "a", ctx -> { })).join());
        assertEquals(List.of("The database is at migration 2, newer than this version of Test knows (1). "
                + "Was the plugin downgraded?"), host.log.warnings());
    }

    @Test
    void versionsArePerPlugin() {
        RsDb first = open(host);
        first.migrate(Migration.of(1, "a", ctx -> { }), Migration.of(2, "b", ctx -> { })).join();
        first.shutdown();
        FakeDbHost other = new FakeDbHost();
        other.name = "Other";
        RsDb second = open(other);
        AtomicInteger ran = new AtomicInteger();
        assertEquals(1, second.migrate(Migration.of(1, "a", ctx -> ran.incrementAndGet())).join());
        assertEquals(1, ran.get());
    }

    @Test
    void invalidMigrationsAreRejected() {
        RsDb db = open(host);
        assertThrows(IllegalArgumentException.class, () -> Migration.of(0, "zero", ctx -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> db.migrate(Migration.of(1, "a", ctx -> { }), Migration.of(1, "b", ctx -> { })));
        assertThrows(IllegalArgumentException.class, () -> Migration.addColumn(1, "t;", "c", "INT"));
        assertThrows(IllegalArgumentException.class, () -> Migration.addColumn(1, "t", "c", " "));
        assertThrows(IllegalArgumentException.class, () -> Migration.sql(1, "x", "SELECT 1", ""));
        assertEquals("migration 4", Migration.of(4, " ", ctx -> { }).description());
    }

    @Test
    void contextChecksTablesAndColumnsIgnoringCase() {
        RsDb db = open(host);
        db.bootstrap(Schema.of("Shops", "Owner VARCHAR(36)")).join();
        List<Boolean> checks = new ArrayList<>();
        db.migrate(Migration.of(1, "checks", ctx -> {
            checks.add(ctx.tableExists("shops"));
            checks.add(ctx.tableExists("missing"));
            checks.add(ctx.columnExists("SHOPS", "owner"));
            checks.add(ctx.columnExists("shops", "price"));
            checks.add(ctx.columnExists("missing", "owner"));
            checks.add(ctx.type() == DbConfig.Type.SQLITE);
        })).join();
        assertEquals(List.of(true, false, true, false, false, true), checks);
        assertFalse(columns(db, "shops").isEmpty());
    }
}
