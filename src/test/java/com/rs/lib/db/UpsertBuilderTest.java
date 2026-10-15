package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpsertBuilderTest {

    @TempDir
    Path dir;

    private RsDb db;

    @AfterEach
    void close() {
        if (db != null) {
            db.shutdown();
        }
    }

    private static UpsertBuilder sqlite(String table) {
        return new UpsertBuilder(null, DbConfig.Type.SQLITE, table);
    }

    private static UpsertBuilder mysql(String table) {
        return new UpsertBuilder(null, DbConfig.Type.MYSQL, table);
    }

    @Test
    void sqliteUpdatesTheSetColumnsOnConflictOfTheKeys() {
        UpsertBuilder upsert = sqlite("kits").keys("uuid", "u").keys("kit", "k").set("uses", 2).set("last", 9L);
        assertEquals("INSERT INTO kits (uuid, kit, uses, last) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(uuid, kit) DO UPDATE SET uses=excluded.uses, last=excluded.last", upsert.sql());
        assertEquals(Arrays.asList("u", "k", 2, 9L), upsert.values());
    }

    @Test
    void sqliteWithoutSetColumnsDoesNothingOnConflict() {
        assertEquals("INSERT INTO seen (uuid) VALUES (?) ON CONFLICT(uuid) DO NOTHING",
                sqlite("seen").keys("uuid", "u").sql());
    }

    @Test
    void mysqlUsesDuplicateKeyUpdate() {
        assertEquals("INSERT INTO kits (uuid, kit, uses) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE uses=VALUES(uses)",
                mysql("kits").keys("uuid", "u").keys("kit", "k").set("uses", 2).sql());
    }

    /** MySQL needs one assignment: a no-op on the first key. */
    @Test
    void mysqlWithoutSetColumnsAssignsTheFirstKeyToItself() {
        assertEquals("INSERT INTO seen (uuid, day) VALUES (?, ?) ON DUPLICATE KEY UPDATE uuid=uuid",
                mysql("seen").keys("uuid", "u").keys("day", 1).sql());
    }

    @Test
    void namesAreIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> sqlite("t; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class, () -> sqlite("1t"));
        assertThrows(IllegalArgumentException.class, () -> sqlite("t").keys("a b", 1));
        assertThrows(IllegalArgumentException.class, () -> sqlite("t").set("", 1));
        assertThrows(IllegalArgumentException.class, () -> sqlite("t").set(null, 1));
        sqlite("_T1").keys("Col_2", 1);
    }

    @Test
    void keysAreRequired() {
        assertThrows(IllegalStateException.class, () -> sqlite("t").set("a", 1).sql());
        assertThrows(IllegalStateException.class, () -> sqlite("t").set("a", 1).run());
    }

    @Test
    void sameColumnTwiceKeepsTheLastValueAndAKeyCannotBeSet() {
        UpsertBuilder upsert = sqlite("t").keys("id", 1).set("a", 1).set("A", 2);
        assertEquals("INSERT INTO t (id, A) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET A=excluded.A", upsert.sql());
        assertEquals(List.of(1, 2), upsert.values());
        assertThrows(IllegalArgumentException.class, () -> sqlite("t").keys("id", 1).set("ID", 2));
        assertThrows(IllegalArgumentException.class, () -> sqlite("t").set("id", 1).keys("id", 2));
    }

    @Test
    void uuidValuesAreBoundAsText() {
        UUID uuid = UUID.randomUUID();
        assertEquals(List.of(uuid.toString()), sqlite("t").keys("uuid", uuid).values());
    }

    /** Trap: the SQLite upsert updates the row the PRIMARY KEY / UNIQUE constraint matches, never duplicates it. */
    @Test
    void upsertMatchesTheExistingConstraintInsteadOfDuplicating() {
        db = TestDbs.sqlite(dir.toFile(), new FakeDbHost());
        db.bootstrap(Schema.of("balances", "uuid VARCHAR(36) PRIMARY KEY", "coins BIGINT NOT NULL"),
                Schema.of("kits", "uuid VARCHAR(36) NOT NULL", "kit VARCHAR(32) NOT NULL", "uses INT",
                        "UNIQUE (uuid, kit)")).join();
        UUID uuid = UUID.randomUUID();

        assertEquals(1, db.upsert("balances").keys("uuid", uuid).set("coins", 5).run().join());
        assertEquals(1, db.upsert("balances").keys("uuid", uuid).set("coins", 8).run().join());
        assertEquals(1, TestDbs.count(db, "balances"));
        assertEquals(8L, db.query("SELECT coins FROM balances WHERE uuid = ?", ps -> ps.setString(1, uuid.toString()),
                rows -> rows.next() ? rows.getLong(1) : -1L).join());

        db.upsert("kits").keys("uuid", uuid).keys("kit", "a").set("uses", 1).run().join();
        db.upsert("kits").keys("uuid", uuid).keys("kit", "b").set("uses", 1).run().join();
        db.upsert("kits").keys("uuid", uuid).keys("kit", "a").set("uses", 2).run().join();
        assertEquals(2, TestDbs.count(db, "kits"));
        assertEquals(0, db.upsert("kits").keys("uuid", uuid).keys("kit", "a").run().join(), "DO NOTHING");
    }

    /** Trap: SQLite needs a constraint on exactly the key columns; without one the upsert fails loudly. */
    @Test
    void sqliteUpsertNeedsAConstraintOnTheKeys() {
        db = TestDbs.sqlite(dir.toFile(), new FakeDbHost());
        db.bootstrap(Schema.of("loose", "uuid VARCHAR(36)", "coins BIGINT")).join();
        CompletionException failure = assertThrows(CompletionException.class,
                () -> db.upsert("loose").keys("uuid", "u").set("coins", 1).run().join());
        assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals(0, TestDbs.count(db, "loose"));
    }
}
