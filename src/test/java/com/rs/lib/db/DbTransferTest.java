package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

/** A plugin's whole database written to one file and read back. */
class DbTransferTest {

    private static final byte[] ALL_BYTES = new byte[256];

    static {
        for (int i = 0; i < ALL_BYTES.length; i++) {
            ALL_BYTES[i] = (byte) i;
        }
    }

    @TempDir
    Path dir;

    private final List<RsDb> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(RsDb::shutdown);
    }

    /** A plugin "Shops" in its own folder, with its tables created and migrated. */
    private RsDb shops(String folder, String noteDefinition) {
        FakeDbHost host = new FakeDbHost();
        host.name = "Shops";
        RsDb db = TestDbs.sqlite(dir.resolve(folder).toFile(), host);
        opened.add(db);
        db.bootstrap(
                Schema.of("items", "id INTEGER PRIMARY KEY", "big BIGINT", "price REAL", "exact TEXT", "data BLOB",
                        "note " + noteDefinition),
                Schema.of("kits", "owner VARCHAR(36) NOT NULL", "kit VARCHAR(32) NOT NULL", "uses INT",
                        "PRIMARY KEY (owner, kit)")).join();
        db.migrate(Migration.addColumn(1, "items", "stock", "INT DEFAULT 5")).join();
        return db;
    }

    private RsDb shops(String folder) {
        return shops(folder, "TEXT");
    }

    private static void fill(RsDb db) {
        db.transaction(c -> {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO items (id, big, price, exact, data, note, stock) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                insert.setLong(1, 1);
                insert.setLong(2, Long.MAX_VALUE);
                insert.setDouble(3, 0.1);
                insert.setString(4, "12345678901234567890.123456789");
                insert.setBytes(5, ALL_BYTES);
                insert.setString(6, "ñandú € \"quoted\"\nnew line   [x] {y}");
                insert.setInt(7, 3);
                insert.executeUpdate();
                insert.setLong(1, 2);
                insert.setLong(2, Long.MIN_VALUE);
                insert.setDouble(3, 1e300);
                insert.setString(4, "");
                insert.setBytes(5, new byte[0]);
                insert.setString(6, "");
                insert.setNull(7, Types.INTEGER);
                insert.executeUpdate();
                insert.setLong(1, 3);
                for (int i = 2; i <= 7; i++) {
                    insert.setNull(i, Types.NULL);
                }
                insert.executeUpdate();
            }
            for (int i = 0; i < 1200; i++) {
                try (PreparedStatement kit = c.prepareStatement("INSERT INTO kits VALUES (?, ?, ?)")) {
                    kit.setString(1, "player-" + (i % 7));
                    kit.setString(2, "kit-" + i);
                    kit.setInt(3, i);
                    kit.executeUpdate();
                }
            }
        }).join();
    }

    /** Every value of a table with its SQLite storage class, blobs as lists so they compare by content. */
    private static List<List<Object>> dump(RsDb db, String table, String order) {
        return db.query("SELECT * FROM " + table + " ORDER BY " + order, null, rows -> {
            List<List<Object>> all = new ArrayList<>();
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    Object value = rows.getObject(i);
                    row.add(value instanceof byte[] bytes ? toList(bytes) : value);
                }
                all.add(row);
            }
            return all;
        }).join();
    }

    private static List<String> storage(RsDb db) {
        return db.query("SELECT typeof(big), typeof(price), typeof(exact), typeof(data), typeof(note), typeof(stock)"
                + " FROM items ORDER BY id", null, rows -> {
                    List<String> types = new ArrayList<>();
                    while (rows.next()) {
                        for (int i = 1; i <= 6; i++) {
                            types.add(rows.getString(i));
                        }
                    }
                    return types;
                }).join();
    }

    private static List<Byte> toList(byte[] bytes) {
        List<Byte> list = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            list.add(b);
        }
        return list;
    }

    private static Throwable failure(RsFuture<?> future) {
        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        return RsFuture.unwrap(thrown);
    }

    private static List<String> lines(Path file) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            return in.lines().toList();
        }
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)),
                StandardCharsets.UTF_8)) {
            for (String line : lines) {
                out.write(line);
                out.write('\n');
            }
        }
    }

    private Path export(RsDb db) {
        TransferReport report = db.exportTo(dir.resolve("dumps")).join();
        return report.file();
    }

    @Test
    void sqliteToFileToSqliteKeepsEveryValueAndType() throws IOException {
        RsDb source = shops("a");
        fill(source);
        List<String> progress = new ArrayList<>();
        TransferReport exported = source.exportTo(dir.resolve("dumps"), (table, rows) -> progress.add(table + "=" + rows))
                .join();

        String name = exported.file().getFileName().toString();
        assertTrue(name.matches("Shops-sqlite-\\d{8}-\\d{6}\\.rsdump\\.gz"), name);
        assertEquals(List.of("items=3", "kits=1200", "rslib_migrations=1"), progress);
        assertEquals(1, exported.migration());
        assertEquals(1204, exported.rows());
        try (Stream<Path> files = Files.list(dir.resolve("dumps"))) {
            assertEquals(List.of(exported.file()), files.toList(), "no .tmp is left behind");
        }

        RsDb target = shops("b");
        TransferReport imported = target.importFrom(exported.file(), false).join();
        assertEquals(exported.tables(), imported.tables());

        assertEquals(dump(source, "items", "id"), dump(target, "items", "id"));
        assertEquals(dump(source, "kits", "kit"), dump(target, "kits", "kit"));
        assertEquals(storage(source), storage(target));
        assertEquals(List.of("integer", "real", "text", "blob", "text", "integer",
                "integer", "real", "text", "blob", "text", "null",
                "null", "null", "null", "null", "null", "null"), storage(target));
        assertEquals(dump(source, RsDb.MIGRATIONS_TABLE, "version"), dump(target, RsDb.MIGRATIONS_TABLE, "version"),
                "the plugin's migration records (with their dates) come from the file");
        assertEquals(Long.MAX_VALUE, (long) dump(target, "items", "id").get(0).get(1));
        assertArrayEquals(ALL_BYTES, target.query("SELECT data FROM items WHERE id = 1", null,
                rows -> rows.next() ? rows.getBytes(1) : null).join());
    }

    /** The file stays readable line by line: header, a marker per table, rows, the counts at the end. */
    @Test
    void theFileIsGzipJsonLinesWithTypedValues() throws IOException {
        RsDb source = shops("a");
        fill(source);
        List<String> lines = lines(export(source));

        assertTrue(lines.get(0).startsWith("{\"format\":1,\"plugin\":\"Shops\",\"engine\":\"sqlite\""), lines.get(0));
        assertEquals("{\"table\":\"items\"}", lines.get(1));
        assertTrue(lines.get(2).startsWith("[1,9223372036854775807,{\"real\":\"0.1\"},\"12345678901234567890.123456789\","
                + "{\"blob\":\"AAECAwQF"), lines.get(2));
        assertTrue(lines.get(4).startsWith("[3,null,null,null,null,null,null"), lines.get(4));
        assertEquals("{\"end\":{\"items\":3,\"kits\":1200,\"rslib_migrations\":1}}", lines.get(lines.size() - 1));
    }

    @Test
    void refusesTablesThatHoldRowsUnlessForcedAndForceMerges() {
        RsDb source = shops("a");
        fill(source);
        Path file = export(source);
        RsDb target = shops("b");
        target.update("INSERT INTO items (id, note) VALUES (1, 'local')", null).join();
        target.update("INSERT INTO items (id, note) VALUES (99, 'only here')", null).join();

        Throwable refused = failure(target.importFrom(file, false));
        assertInstanceOf(TransferRefused.class, refused);
        assertTrue(refused.getMessage().contains("items (2 rows)"), refused.getMessage());
        assertTrue(refused.getMessage().contains("a merge, not a replace"), refused.getMessage());
        assertEquals(0, TestDbs.count(target, "kits"), "a refusal writes nothing, not even the empty tables");

        target.importFrom(file, true).join();
        assertEquals(4, TestDbs.count(target, "items"));
        assertEquals(dump(source, "items", "id").get(0), dump(target, "items", "id").get(0),
                "a row whose key is in the file is overwritten");
        assertEquals("only here", target.query("SELECT note FROM items WHERE id = 99", null,
                rows -> rows.next() ? rows.getString(1) : null).join(), "every other row stays");
        assertEquals(1200, TestDbs.count(target, "kits"));
    }

    @Test
    void forceIsRefusedOnATableWithRowsAndNoPrimaryKey() {
        FakeDbHost host = new FakeDbHost();
        host.name = "Logs";
        RsDb source = TestDbs.sqlite(dir.resolve("a").toFile(), host);
        opened.add(source);
        source.bootstrap(Schema.of("entries", "line TEXT")).join();
        source.update("INSERT INTO entries VALUES ('x')", null).join();
        Path file = export(source);

        FakeDbHost other = new FakeDbHost();
        other.name = "Logs";
        RsDb target = TestDbs.sqlite(dir.resolve("b").toFile(), other);
        opened.add(target);
        target.bootstrap(Schema.of("entries", "line TEXT")).join();
        target.update("INSERT INTO entries VALUES ('y')", null).join();

        Throwable refused = failure(target.importFrom(file, true));
        assertTrue(refused.getMessage().contains("no primary key"), refused.getMessage());
        assertEquals(1, TestDbs.count(target, "entries"));
    }

    /** Trap: a row refused by the database after other tables were written rolls everything back. */
    @Test
    void aRefusedRowRollsBackEveryTable() {
        RsDb source = shops("a");
        fill(source);
        Path file = export(source);
        RsDb target = shops("b", "TEXT NOT NULL");
        target.update("INSERT INTO rslib_migrations VALUES ('Other', 1, 'theirs', 5)", null).join();
        List<List<Object>> history = dump(target, RsDb.MIGRATIONS_TABLE, "owner, version");

        Throwable refused = failure(target.importFrom(file, false));
        assertInstanceOf(TransferRefused.class, refused);
        assertTrue(refused.getMessage().contains("refused a row of items"), refused.getMessage());
        assertEquals(0, TestDbs.count(target, "items"));
        assertEquals(0, TestDbs.count(target, "kits"));
        assertEquals(history, dump(target, RsDb.MIGRATIONS_TABLE, "owner, version"));
    }

    @Test
    void aFileCutShortIsRefusedAndRolledBack() throws IOException {
        RsDb source = shops("a");
        fill(source);
        Path file = export(source);
        List<String> lines = lines(file);
        Path cut = dir.resolve("cut" + DbTransfer.EXTENSION);
        writeLines(cut, lines.subList(0, lines.size() - 1));
        RsDb target = shops("b");

        Throwable refused = failure(target.importFrom(cut, false));
        assertTrue(refused.getMessage().contains("cut short"), refused.getMessage());
        assertEquals(0, TestDbs.count(target, "items"));
        assertEquals(0, TestDbs.count(target, "kits"));

        byte[] bytes = Files.readAllBytes(file);
        Path truncated = dir.resolve("truncated" + DbTransfer.EXTENSION);
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length / 2));
        assertInstanceOf(TransferRefused.class, failure(target.importFrom(truncated, false)));
        assertEquals(0, TestDbs.count(target, "kits"));
    }

    @Test
    void aWrongRowCountOrValueIsRefused() throws IOException {
        RsDb source = shops("a");
        fill(source);
        List<String> lines = new ArrayList<>(lines(export(source)));
        RsDb target = shops("b");

        List<String> counts = new ArrayList<>(lines);
        counts.set(counts.size() - 1, counts.get(counts.size() - 1).replace("\"kits\":1200", "\"kits\":1201"));
        Path wrongCount = dir.resolve("count" + DbTransfer.EXTENSION);
        writeLines(wrongCount, counts);
        assertTrue(failure(target.importFrom(wrongCount, false)).getMessage().contains("kits"));

        List<String> values = new ArrayList<>(lines);
        values.set(2, values.get(2).replace("[1,9223372036854775807,", "[1,1.5,"));
        Path wrongValue = dir.resolve("value" + DbTransfer.EXTENSION);
        writeLines(wrongValue, values);
        assertTrue(failure(target.importFrom(wrongValue, false)).getMessage().contains("Line 3"));
        assertEquals(0, TestDbs.count(target, "items"));
    }

    @Test
    void refusesAnotherPluginAnotherMigrationOrAMissingTable() {
        RsDb source = shops("a");
        fill(source);
        Path file = export(source);

        FakeDbHost otherPlugin = new FakeDbHost();
        otherPlugin.name = "Kits";
        RsDb kits = TestDbs.sqlite(dir.resolve("c").toFile(), otherPlugin);
        opened.add(kits);
        assertTrue(failure(kits.importFrom(file, true)).getMessage().contains("database of Shops, not of Kits"));

        FakeDbHost newer = new FakeDbHost();
        newer.name = "Shops";
        RsDb migrated = TestDbs.sqlite(dir.resolve("d").toFile(), newer);
        opened.add(migrated);
        migrated.bootstrap(Schema.of("items", "id INTEGER PRIMARY KEY")).join();
        migrated.migrate(Migration.addColumn(1, "items", "stock", "INT"), Migration.addColumn(2, "items", "x", "INT"))
                .join();
        Throwable mismatch = failure(migrated.importFrom(file, false));
        assertTrue(mismatch.getMessage().contains("migration 1 of Shops, and this database is at migration 2"),
                mismatch.getMessage());

        FakeDbHost lacking = new FakeDbHost();
        lacking.name = "Shops";
        RsDb partial = TestDbs.sqlite(dir.resolve("e").toFile(), lacking);
        opened.add(partial);
        partial.bootstrap(Schema.of("items", "id INTEGER PRIMARY KEY", "big BIGINT")).join();
        partial.migrate(Migration.addColumn(1, "items", "stock", "INT")).join();
        Throwable missing = failure(partial.importFrom(file, false));
        assertTrue(missing.getMessage().contains("table kits does not exist here"), missing.getMessage());
        assertTrue(missing.getMessage().contains("has no column price"), missing.getMessage());
        assertEquals(0, TestDbs.count(partial, "items"));
    }

    /** The plugin's declared tables and migrations run before the import, as at startup. */
    @Test
    void importCreatesTheDeclaredTablesFirst() {
        RsDb source = shops("a");
        fill(source);
        Path file = export(source);
        RsDb target = shops("b");
        target.update("DROP TABLE kits", null).join();

        target.importFrom(file, false).join();
        assertEquals(1200, TestDbs.count(target, "kits"));
    }

    /** Other plugins' migration records in a shared history are neither exported nor touched. */
    @Test
    void onlyThisPluginsMigrationRecordsMove() throws IOException {
        RsDb source = shops("a");
        source.update("INSERT INTO rslib_migrations VALUES ('Other', 7, 'theirs', 5)", null).join();
        Path file = export(source);
        assertFalse(String.join("\n", lines(file)).contains("theirs"));

        RsDb target = shops("b");
        target.update("INSERT INTO rslib_migrations VALUES ('Other', 3, 'kept', 5)", null).join();
        target.importFrom(file, false).join();
        assertEquals(1, target.query("SELECT COUNT(*) FROM rslib_migrations WHERE owner = 'Other' AND version = 3",
                null, rows -> rows.next() ? rows.getInt(1) : 0).join());
        assertEquals(1, target.query("SELECT COUNT(*) FROM rslib_migrations WHERE owner = 'Shops'",
                null, rows -> rows.next() ? rows.getInt(1) : 0).join());
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws IOException {
        RsDb source = shops("a");
        Path file = dir.resolve("fixed" + DbTransfer.EXTENSION);
        Files.writeString(file, "keep");
        Throwable refused = failure(source.exportTo(file));
        assertTrue(refused.getMessage().contains("already exists"));
        assertEquals("keep", Files.readString(file));
    }

    /** MySQL hands out exact decimals: they travel as text with every digit and the scale. */
    @Test
    void exactDecimalsKeepTheirDigitsAndScale() throws Exception {
        BigDecimal money = new BigDecimal("123456789012345678901234567890.50");
        ResultSet row = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getObject" -> money;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        StringWriter text = new StringWriter();
        DbTransfer.writeValue(new JsonWriter(text), row, 1);
        assertEquals("{\"dec\":\"123456789012345678901234567890.50\"}", text.toString());

        Map<String, Object> bound = new HashMap<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    bound.put(method.getName(), args[1]);
                    return null;
                });
        DbTransfer.bind(null, statement, 1, JsonParser.parseString(text.toString()), Types.DECIMAL);
        assertEquals(money, bound.get("setBigDecimal"));
        assertEquals(2, ((BigDecimal) bound.get("setBigDecimal")).scale());
    }

    /** SQLite to file to MySQL and back. Skipped unless {@code RSLIB_TEST_MYSQL} is set. */
    @Test
    @EnabledIfEnvironmentVariable(named = TestDbs.MYSQL_ENV, matches = ".+")
    void sqliteToMysqlAndBack() {
        RsDb source = shops("a");
        fill(source);
        Path file = export(source);

        FakeDbHost host = new FakeDbHost();
        host.name = "Shops";
        RsDb mysql = TestDbs.mysql(host, 2);
        opened.add(mysql);
        // The test database is empty by contract; leftovers of other tests would be exported too.
        mysql.bootstrap(connection -> {
            List<String> tables = new ArrayList<>();
            try (ResultSet all = connection.getMetaData().getTables(connection.getCatalog(), null, "%",
                    new String[]{"TABLE"})) {
                while (all.next()) {
                    tables.add(all.getString("TABLE_NAME"));
                }
            }
            for (String table : tables) {
                connection.createStatement().execute("DROP TABLE `" + table + "`");
            }
        }).join();
        mysql.bootstrap(
                Schema.of("items", "id BIGINT PRIMARY KEY", "big BIGINT", "price DOUBLE", "exact TEXT", "data BLOB",
                        "note TEXT"),
                Schema.of("kits", "owner VARCHAR(36) NOT NULL", "kit VARCHAR(32) NOT NULL", "uses INT",
                        "PRIMARY KEY (owner, kit)")).join();
        mysql.migrate(Migration.addColumn(1, "items", "stock", "INT DEFAULT 5")).join();
        mysql.importFrom(file, false).join();
        assertEquals(1200, TestDbs.count(mysql, "kits"));

        Path back = mysql.exportTo(dir.resolve("from-mysql")).join().file();
        RsDb again = shops("c");
        again.importFrom(back, false).join();
        assertEquals(dump(source, "items", "id"), dump(again, "items", "id"));
        assertEquals(dump(source, "kits", "kit"), dump(again, "kits", "kit"));
    }
}
