package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaTest {

    @TempDir
    Path dir;

    private RsDb db;

    @AfterEach
    void close() {
        if (db != null) {
            db.shutdown();
        }
    }

    @Test
    void ofJoinsDefinitionsIntoCreateIfNotExists() {
        Schema schema = Schema.of("balances", "uuid VARCHAR(36) PRIMARY KEY", " coins BIGINT NOT NULL DEFAULT 0 ");
        assertEquals("balances", schema.table());
        assertEquals("CREATE TABLE IF NOT EXISTS balances (uuid VARCHAR(36) PRIMARY KEY, coins BIGINT NOT NULL DEFAULT 0)",
                schema.createSql());
    }

    @Test
    void rawIsKeptAsWritten() {
        String sql = "CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, line TEXT)";
        assertEquals(sql, Schema.raw("logs", sql).createSql());
    }

    @Test
    void blankInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Schema.of(" "));
        assertThrows(IllegalArgumentException.class, () -> Schema.of("t"));
        assertThrows(IllegalArgumentException.class, () -> Schema.of("t", "a INT", " "));
        assertThrows(IllegalArgumentException.class, () -> Schema.raw("t", ""));
        assertThrows(NullPointerException.class, () -> Schema.of(null, "a INT"));
    }

    /** Trap: CREATE TABLE IF NOT EXISTS never alters a table that is already there. */
    @Test
    void bootstrapLeavesExistingTablesAlone() {
        FakeDbHost host = new FakeDbHost();
        db = TestDbs.sqlite(dir.toFile(), host);
        db.bootstrap(Schema.of("tops", "uuid VARCHAR(36) PRIMARY KEY", "wins BIGINT")).join();
        db.update("INSERT INTO tops (uuid, wins) VALUES ('a', 3)", null).join();

        db.bootstrap(Schema.of("tops", "uuid VARCHAR(36) PRIMARY KEY", "wins BIGINT", "losses BIGINT"),
                Schema.of("extra", "id INT")).join();

        List<String> columns = db.query("SELECT * FROM tops", null, rows -> {
            List<String> names = new ArrayList<>();
            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                names.add(rows.getMetaData().getColumnName(i));
            }
            return names;
        }).join();
        assertEquals(List.of("uuid", "wins"), columns);
        assertEquals(1, TestDbs.count(db, "tops"));
        assertEquals(0, TestDbs.count(db, "extra"));
    }
}
