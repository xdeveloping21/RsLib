package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The MySQL dialect against a real server. Skipped unless {@code RSLIB_TEST_MYSQL} is set to
 * {@code user:password@host:port/database} (an empty database the tests may write to).
 */
@EnabledIfEnvironmentVariable(named = TestDbs.MYSQL_ENV, matches = ".+")
class MysqlIntegrationTest {

    private final FakeDbHost host = new FakeDbHost();
    private RsDb db;

    @BeforeEach
    void open() {
        db = TestDbs.mysql(host, 3);
        db.bootstrap(connection -> {
            for (String table : List.of("it_balances", "it_kits", "it_tops", RsDb.MIGRATIONS_TABLE)) {
                connection.createStatement().execute("DROP TABLE IF EXISTS " + table);
            }
        }).join();
    }

    @AfterEach
    void close() {
        db.shutdown();
    }

    @Test
    void upsertUsesTheTableUniqueIndexes() {
        db.bootstrap(Schema.of("it_balances", "uuid VARCHAR(36) PRIMARY KEY", "coins BIGINT NOT NULL"),
                Schema.of("it_kits", "uuid VARCHAR(36) NOT NULL", "kit VARCHAR(32) NOT NULL", "uses INT",
                        "UNIQUE KEY (uuid, kit)")).join();
        UUID uuid = UUID.randomUUID();
        assertEquals(1, db.upsert("it_balances").keys("uuid", uuid).set("coins", 5).run().join());
        assertEquals(2, db.upsert("it_balances").keys("uuid", uuid).set("coins", 8).run().join(),
                "MySQL counts an update of an existing row as 2");
        assertEquals(1, TestDbs.count(db, "it_balances"));
        assertEquals(8L, db.query("SELECT coins FROM it_balances WHERE uuid = ?", ps -> ps.setString(1, uuid.toString()),
                rows -> rows.next() ? rows.getLong(1) : -1L).join(), "a UUID is stored as its text");

        db.upsert("it_kits").keys("uuid", uuid).keys("kit", "a").set("uses", 1).run().join();
        db.upsert("it_kits").keys("uuid", uuid).keys("kit", "b").set("uses", 1).run().join();
        db.upsert("it_kits").keys("uuid", uuid).keys("kit", "a").set("uses", 2).run().join();
        assertEquals(2, TestDbs.count(db, "it_kits"));
        assertEquals(1, db.upsert("it_kits").keys("uuid", uuid).keys("kit", "b").run().join(),
                "the no-op assignment changes nothing (Connector/J reports the row as found)");
    }

    @Test
    void oneThreadPerPooledConnection() {
        Set<String> names = ConcurrentHashMap.newKeySet();
        List<RsFuture<Void>> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            work.add(db.transaction(connection -> {
                names.add(Thread.currentThread().getName());
                connection.createStatement().execute("DO SLEEP(0.1)");
            }));
        }
        work.forEach(RsFuture::join);
        assertEquals(Set.of("Test-db-1", "Test-db-2", "Test-db-3"), names);
    }

    @Test
    void migrationsOnMysql() {
        db.bootstrap(Schema.of("it_tops", "uuid VARCHAR(36) PRIMARY KEY", "wins BIGINT NOT NULL")).join();
        db.update("INSERT INTO it_tops VALUES ('a', 3)", null).join();
        assertEquals(2, db.migrate(Migration.addColumn(1, "it_tops", "losses", "BIGINT NOT NULL DEFAULT 0"),
                Migration.of(2, "fill", ctx -> {
                    assertTrue(ctx.columnExists("IT_TOPS", "LOSSES"));
                    ctx.execute("UPDATE it_tops SET losses = wins");
                })).join());
        assertEquals(3L, db.query("SELECT losses FROM it_tops", null, rows -> rows.next() ? rows.getLong(1) : -1L).join());
        assertEquals(2, db.migrate(Migration.addColumn(1, "it_tops", "losses", "BIGINT NOT NULL DEFAULT 0")).join());
        assertEquals(2, TestDbs.count(db, RsDb.MIGRATIONS_TABLE));
    }
}
