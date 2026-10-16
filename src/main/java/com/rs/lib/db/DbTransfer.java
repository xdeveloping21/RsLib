package com.rs.lib.db;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonWriter;

/**
 * The work behind {@link RsDb#exportTo} and {@link RsDb#importFrom}: every table of one
 * plugin's database written to one file, and read back into SQLite or MySQL.
 *
 * <p>The file is gzip-compressed UTF-8 with one JSON value per line, so a reader can name the
 * line it could not read:</p>
 * <pre>
 * {"format":1,"plugin":"Shops","engine":"sqlite","exported-at":1700000000000,"migration":2,
 *  "tables":[{"name":"shops","columns":["id","owner","price"]}]}
 * {"table":"shops"}
 * [1,"6f1c...",{"dec":"12.50"}]
 * {"end":{"shops":1}}
 * </pre>
 * <p>A row is an array in column order. Each value carries its own type, because a SQLite
 * column may hold any of them: {@code null}, a JSON number (a whole number, read back exactly),
 * a JSON string (text), {@code {"dec":"…"}} (an exact decimal), {@code {"real":"…"}} (a
 * floating-point number) and {@code {"blob":"<base64>"}}. Anything else the driver returns,
 * such as a MySQL date, is written as its text. The last line holds the row count of every
 * table: a file without it was cut short and is refused.</p>
 *
 * <p>Of the shared migration history ({@code rslib_migrations}) only this plugin's records are
 * written and replaced.</p>
 */
final class DbTransfer {

    static final int FORMAT = 1;
    static final String EXTENSION = TransferReport.EXTENSION;
    /** Rows sent to the database per batch on import. */
    static final int BATCH = 500;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final String plugin;
    private final DbConfig.Type type;

    DbTransfer(String plugin, DbConfig.Type type) {
        this.plugin = plugin;
        this.type = type;
    }

    // ------------------------------------------------------------------ export

    /**
     * Writes every table to {@code destination}: a folder (the file gets a dated name) or a
     * path ending in {@code .rsdump.gz}. Reads in one transaction, so the file is one moment of
     * the database; the file appears only once complete.
     */
    TransferReport export(Connection connection, Path destination, TransferReport.Progress progress)
            throws SQLException {
        long started = System.nanoTime();
        Path file = exportFile(destination, System.currentTimeMillis());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Map<String, Long> counts = new LinkedHashMap<>();
        int migration;
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        boolean moved = false;
        try {
            List<String> tables = tables(connection);
            Map<String, List<String>> columns = new LinkedHashMap<>();
            for (String table : tables) {
                columns.put(table, columns(connection, table).names());
            }
            migration = exportedMigration(connection, tables);
            try (Writer out = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(temp)), StandardCharsets.UTF_8))) {
                writeHeader(out, columns, migration);
                for (String table : tables) {
                    long rows = writeTable(connection, out, table, columns.get(table).size());
                    counts.put(table, rows);
                    progress.table(table, rows);
                }
                line(out, w -> {
                    w.beginObject().name("end").beginObject();
                    for (Map.Entry<String, Long> count : counts.entrySet()) {
                        w.name(count.getKey()).value(count.getValue());
                    }
                    w.endObject().endObject();
                });
            }
            move(temp, file);
            moved = true;
        } catch (IOException e) {
            throw new TransferRefused("Could not write " + file + ": " + e.getMessage(), e);
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover .tmp is never read as a dump.
                }
            }
            endSnapshot(connection, autoCommit);
        }
        return new TransferReport(file, counts, migration, millisSince(started));
    }

    /** The file an export writes; refuses one that already exists. */
    Path exportFile(Path destination, long now) throws TransferRefused {
        Path file = destination;
        if (!destination.getFileName().toString().endsWith(EXTENSION)) {
            file = destination.resolve(plugin + "-" + type.name().toLowerCase(Locale.ROOT) + "-"
                    + STAMP.format(Instant.ofEpochMilli(now)) + EXTENSION);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new TransferRefused("Could not create the folder of " + file + ": " + e.getMessage(), e);
        }
        if (Files.exists(file)) {
            throw new TransferRefused("The file " + file + " already exists; it is never overwritten.");
        }
        return file;
    }

    private void writeHeader(Writer out, Map<String, List<String>> columns, int migration) throws IOException {
        line(out, w -> {
            w.beginObject();
            w.name("format").value(FORMAT);
            w.name("plugin").value(plugin);
            w.name("engine").value(type.name().toLowerCase(Locale.ROOT));
            w.name("exported-at").value(System.currentTimeMillis());
            w.name("migration").value(migration);
            w.name("tables").beginArray();
            for (Map.Entry<String, List<String>> table : columns.entrySet()) {
                w.beginObject().name("name").value(table.getKey()).name("columns").beginArray();
                for (String column : table.getValue()) {
                    w.value(column);
                }
                w.endArray().endObject();
            }
            w.endArray();
            w.endObject();
        });
    }

    private long writeTable(Connection connection, Writer out, String table, int columnCount)
            throws SQLException, IOException {
        line(out, w -> w.beginObject().name("table").value(table).endObject());
        boolean history = isHistory(table);
        String sql = "SELECT * FROM " + quote(table) + (history ? " WHERE owner = ?" : "");
        long rows = 0;
        try (PreparedStatement select = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            if (type == DbConfig.Type.MYSQL) {
                // Connector/J streams row by row only with this value; otherwise it loads the whole table.
                select.setFetchSize(Integer.MIN_VALUE);
            }
            if (history) {
                select.setString(1, plugin);
            }
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    line(out, w -> {
                        w.beginArray();
                        for (int i = 1; i <= columnCount; i++) {
                            writeValue(w, result, i);
                        }
                        w.endArray();
                    });
                    rows++;
                }
            } catch (SqlInJson wrapped) {
                throw wrapped.cause;
            }
        }
        return rows;
    }

    /** One value with its type; see the class comment. */
    static void writeValue(JsonWriter w, ResultSet result, int column) throws IOException {
        Object value;
        try {
            value = result.getObject(column);
        } catch (SQLException e) {
            throw new SqlInJson(e);
        }
        if (value == null) {
            w.nullValue();
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte) {
            w.value(((Number) value).longValue());
        } else if (value instanceof Boolean flag) {
            w.value(flag ? 1L : 0L);
        } else if (value instanceof BigInteger big) {
            if (big.bitLength() < Long.SIZE) {
                w.value(big.longValue());
            } else {
                tagged(w, "dec", big.toString());
            }
        } else if (value instanceof BigDecimal decimal) {
            tagged(w, "dec", decimal.toPlainString());
        } else if (value instanceof Double || value instanceof Float) {
            tagged(w, "real", value.toString());
        } else if (value instanceof byte[] bytes) {
            tagged(w, "blob", Base64.getEncoder().encodeToString(bytes));
        } else if (value instanceof String text) {
            w.value(text);
        } else {
            try {
                if (value instanceof Blob blob) {
                    tagged(w, "blob", Base64.getEncoder().encodeToString(blob.getBytes(1, (int) blob.length())));
                } else if (value instanceof Clob clob) {
                    w.value(clob.getSubString(1, (int) clob.length()));
                } else {
                    w.value(result.getString(column));
                }
            } catch (SQLException e) {
                throw new SqlInJson(e);
            }
        }
    }

    private static void tagged(JsonWriter w, String tag, String value) throws IOException {
        w.beginObject().name(tag).value(value).endObject();
    }

    private int exportedMigration(Connection connection, List<String> tables) throws SQLException {
        for (String table : tables) {
            if (isHistory(table)) {
                return migrationVersion(connection, table);
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ import

    /**
     * Reads {@code file} into this database. {@code prepare} first creates and migrates the
     * plugin's tables. Every check runs before the first write; the rows are then written in
     * one transaction, rolled back on any failure (a value the database refuses, a file cut
     * short).
     */
    TransferReport importFrom(Connection connection, Path file, boolean force, TransferReport.Progress progress,
                              RsDb.SqlConsumer<Connection> prepare) throws SQLException {
        long started = System.nanoTime();
        if (!Files.isRegularFile(file)) {
            throw new TransferRefused("There is no file " + file + ".");
        }
        try (DumpReader in = new DumpReader(file)) {
            Header header = readHeader(in);
            prepare.accept(connection);
            int current = migrationVersion(connection, storedTables(connection).get(lower(RsDb.MIGRATIONS_TABLE)));
            if (header.migration != current) {
                throw new TransferRefused("The file was written at database migration " + header.migration + " of "
                        + header.plugin + ", and this database is at migration " + current
                        + ". Export and import with the same version of " + plugin + ".");
            }
            List<Target> targets = plan(connection, header, force);
            Map<String, Long> counts = new LinkedHashMap<>();
            RsDb.inTransaction(connection, c -> {
                for (Target target : targets) {
                    long rows = load(c, in, target);
                    counts.put(target.name, rows);
                    progress.table(target.name, rows);
                }
                checkEnd(in, counts);
            });
            return new TransferReport(file, counts, header.migration, millisSince(started));
        } catch (IOException e) {
            throw new TransferRefused("Could not read " + file + ": " + e.getMessage() + ". Nothing was imported.", e);
        }
    }

    private record Header(String plugin, int migration, List<String> tables, Map<String, List<String>> columns) {
    }

    /** One table to fill: where its rows go and how they are written. */
    private record Target(String name, String stored, List<String> fileColumns, int[] sqlTypes, String insertSql,
                          int ownerIndex) {
    }

    private Header readHeader(DumpReader in) throws IOException, TransferRefused {
        JsonObject header = in.object(in.next(), "the header");
        int format = in.integer(header, "format");
        if (format != FORMAT) {
            throw new TransferRefused("The file has format " + format + "; this RsLib reads format " + FORMAT
                    + ". Import it with the RsLib version that wrote it, or a newer one.");
        }
        String owner = in.text(header, "plugin");
        if (!owner.equalsIgnoreCase(plugin)) {
            throw new TransferRefused("The file holds the database of " + owner + ", not of " + plugin + ".");
        }
        List<String> tables = new ArrayList<>();
        Map<String, List<String>> columns = new HashMap<>();
        JsonElement list = header.get("tables");
        if (list == null || !list.isJsonArray()) {
            throw in.bad("the header has no table list");
        }
        for (JsonElement each : list.getAsJsonArray()) {
            JsonObject table = in.object(each, "a table of the header");
            String name = in.text(table, "name");
            JsonElement names = table.get("columns");
            if (names == null || !names.isJsonArray()) {
                throw in.bad("table " + name + " has no column list");
            }
            List<String> cols = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonElement column : names.getAsJsonArray()) {
                if (!column.isJsonPrimitive() || !seen.add(lower(column.getAsString()))) {
                    throw in.bad("table " + name + " has a bad or repeated column name");
                }
                cols.add(column.getAsString());
            }
            if (columns.put(lower(name), cols) != null) {
                throw in.bad("table " + name + " is listed twice");
            }
            tables.add(name);
        }
        return new Header(owner, in.integer(header, "migration"), tables, columns);
    }

    /** Checks every table of the file against this database; throws listing every problem at once. */
    private List<Target> plan(Connection connection, Header header, boolean force) throws SQLException {
        Map<String, String> stored = storedTables(connection);
        List<String> problems = new ArrayList<>();
        List<String> occupied = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        for (String name : header.tables) {
            List<String> fileColumns = header.columns.get(lower(name));
            String table = stored.get(lower(name));
            if (table == null) {
                problems.add("table " + name + " does not exist here (" + plugin + " does not create it)");
                continue;
            }
            Columns target = columns(connection, table);
            List<String> columnNames = new ArrayList<>();
            int[] sqlTypes = new int[fileColumns.size()];
            boolean complete = true;
            for (int i = 0; i < fileColumns.size(); i++) {
                int index = target.indexOf(fileColumns.get(i));
                if (index < 0) {
                    problems.add("table " + table + " has no column " + fileColumns.get(i));
                    complete = false;
                    continue;
                }
                columnNames.add(target.names().get(index));
                sqlTypes[i] = target.types().get(index);
            }
            if (!complete) {
                continue;
            }
            boolean history = isHistory(table);
            int ownerIndex = history ? indexIgnoringCase(columnNames, "owner") : -1;
            if (history && ownerIndex < 0) {
                problems.add("table " + table + " has no owner column");
                continue;
            }
            List<String> keys = history ? List.of() : primaryKey(connection, table);
            long rows = history ? 0 : count(connection, table);
            boolean merge = false;
            if (rows > 0) {
                if (!force) {
                    occupied.add(table + " (" + rows + " rows)");
                    continue;
                }
                if (keys.isEmpty()) {
                    problems.add("table " + table + " has " + rows + " rows and no primary key, so --force cannot"
                            + " merge into it; empty it first");
                    continue;
                }
                for (String key : keys) {
                    if (indexIgnoringCase(columnNames, key) < 0) {
                        problems.add("table " + table + ": the file lacks the key column " + key);
                        complete = false;
                    }
                }
                if (!complete) {
                    continue;
                }
                merge = true;
            }
            targets.add(new Target(name, table, fileColumns, sqlTypes,
                    insertSql(table, columnNames, merge ? keys : List.of()), ownerIndex));
        }
        if (!occupied.isEmpty()) {
            problems.add(0, "these tables already hold data: " + String.join(", ", occupied)
                    + ". Nothing was imported. With --force, a row whose key is in the file overwrites the one in the"
                    + " table and every other row stays (a merge, not a replace)");
        }
        if (!problems.isEmpty()) {
            throw new TransferRefused("Import refused: " + String.join("; ", problems) + ".");
        }
        return targets;
    }

    private String insertSql(String table, List<String> columns, List<String> mergeKeys) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quote(table)).append(" (");
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                marks.append(", ");
            }
            sql.append(quote(columns.get(i)));
            marks.append('?');
        }
        sql.append(") VALUES (").append(marks).append(')');
        if (mergeKeys.isEmpty()) {
            return sql.toString();
        }
        List<String> updated = new ArrayList<>();
        for (String column : columns) {
            if (indexIgnoringCase(mergeKeys, column) < 0) {
                updated.add(column);
            }
        }
        if (type == DbConfig.Type.SQLITE) {
            sql.append(" ON CONFLICT (");
            for (int i = 0; i < mergeKeys.size(); i++) {
                sql.append(i > 0 ? ", " : "").append(quote(mergeKeys.get(i)));
            }
            sql.append(") DO ");
            if (updated.isEmpty()) {
                return sql.append("NOTHING").toString();
            }
            sql.append("UPDATE SET ");
            for (int i = 0; i < updated.size(); i++) {
                String column = quote(updated.get(i));
                sql.append(i > 0 ? ", " : "").append(column).append(" = excluded.").append(column);
            }
        } else {
            sql.append(" ON DUPLICATE KEY UPDATE ");
            if (updated.isEmpty()) {
                String key = quote(mergeKeys.get(0));
                return sql.append(key).append(" = ").append(key).toString();
            }
            for (int i = 0; i < updated.size(); i++) {
                String column = quote(updated.get(i));
                sql.append(i > 0 ? ", " : "").append(column).append(" = VALUES(").append(column).append(')');
            }
        }
        return sql.toString();
    }

    /** Writes one table's rows from the file; the plugin's migration records replace its old ones. */
    private long load(Connection connection, DumpReader in, Target target) throws SQLException {
        JsonObject marker = in.objectLine("the rows of " + target.name);
        if (!marker.has("table") || !target.name.equals(marker.get("table").getAsString())) {
            throw in.bad("expected the rows of " + target.name);
        }
        if (target.ownerIndex >= 0) {
            try (PreparedStatement clear = connection.prepareStatement(
                    "DELETE FROM " + quote(target.stored) + " WHERE owner = ?")) {
                clear.setString(1, plugin);
                clear.executeUpdate();
            }
        }
        long rows = 0;
        try (PreparedStatement insert = connection.prepareStatement(target.insertSql)) {
            int pending = 0;
            while (in.nextIsRow()) {
                JsonArray row = in.row();
                if (row.size() != target.fileColumns.size()) {
                    throw in.bad("a row of " + target.name + " has " + row.size() + " values for "
                            + target.fileColumns.size() + " columns");
                }
                for (int i = 0; i < row.size(); i++) {
                    if (i == target.ownerIndex) {
                        insert.setString(i + 1, plugin);
                    } else {
                        bind(in, insert, i + 1, row.get(i), target.sqlTypes[i]);
                    }
                }
                insert.addBatch();
                rows++;
                if (++pending == BATCH) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
        } catch (SQLException e) {
            if (e instanceof TransferRefused) {
                throw e;
            }
            throw new TransferRefused("The database refused a row of " + target.name + " (near line " + in.lineNumber()
                    + "): " + e.getMessage() + ". Nothing was imported.", e);
        }
        return rows;
    }

    /** Binds one value of the file; see the class comment for the forms. */
    static void bind(DumpReader in, PreparedStatement statement, int index, JsonElement value, int sqlType)
            throws SQLException {
        if (value == null || value.isJsonNull()) {
            statement.setNull(index, sqlType);
            return;
        }
        try {
            if (value.isJsonPrimitive()) {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isNumber()) {
                    statement.setLong(index, Long.parseLong(primitive.getAsString()));
                    return;
                }
                if (primitive.isString()) {
                    statement.setString(index, primitive.getAsString());
                    return;
                }
            } else if (value.isJsonObject() && value.getAsJsonObject().size() == 1) {
                JsonObject tagged = value.getAsJsonObject();
                if (tagged.has("dec")) {
                    statement.setBigDecimal(index, new BigDecimal(tagged.get("dec").getAsString()));
                    return;
                }
                if (tagged.has("real")) {
                    statement.setDouble(index, Double.parseDouble(tagged.get("real").getAsString()));
                    return;
                }
                if (tagged.has("blob")) {
                    statement.setBytes(index, Base64.getDecoder().decode(tagged.get("blob").getAsString()));
                    return;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
            // NumberFormatException is an IllegalArgumentException.
            throw in.bad("value " + value + " cannot be read");
        }
        throw in.bad("value " + value + " has no known type");
    }

    private void checkEnd(DumpReader in, Map<String, Long> counts) throws TransferRefused {
        JsonObject end = in.objectLine("the end of the file");
        JsonElement written = end.get("end");
        if (written == null || !written.isJsonObject()) {
            throw in.bad("expected the end of the file");
        }
        for (Map.Entry<String, Long> count : counts.entrySet()) {
            JsonElement expected = written.getAsJsonObject().get(count.getKey());
            if (expected == null || !expected.isJsonPrimitive()
                    || !expected.getAsString().equals(count.getValue().toString())) {
                throw in.bad("table " + count.getKey() + " should have " + expected + " rows, the file had "
                        + count.getValue());
            }
        }
    }

    // ------------------------------------------------------------------ database helpers

    /** Every table of the database (the file on SQLite, the schema on MySQL), sorted by name. */
    List<String> tables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>(storedTables(connection).values());
        tables.sort(String.CASE_INSENSITIVE_ORDER);
        for (String table : tables) {
            if (table.indexOf('"') >= 0 || table.indexOf('`') >= 0) {
                throw new TransferRefused("The table name " + table + " cannot be transferred.");
            }
        }
        return tables;
    }

    /** Lower-case name to the name the database stores, SQLite's own tables left out. */
    private static Map<String, String> storedTables(Connection connection) throws SQLException {
        Map<String, String> names = new LinkedHashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet tables = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (name != null && !lower(name).startsWith("sqlite_")) {
                    names.putIfAbsent(lower(name), name);
                }
            }
        }
        return names;
    }

    private record Columns(List<String> names, List<Integer> types) {

        int indexOf(String column) {
            return indexIgnoringCase(names, column);
        }
    }

    private Columns columns(Connection connection, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet empty = statement.executeQuery("SELECT * FROM " + quote(table) + " WHERE 1 = 0")) {
            ResultSetMetaData meta = empty.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnName(i));
                types.add(meta.getColumnType(i));
            }
        }
        return new Columns(names, types);
    }

    private static List<String> primaryKey(Connection connection, String table) throws SQLException {
        Map<Integer, String> bySequence = new TreeMap<>();
        try (ResultSet keys = connection.getMetaData().getPrimaryKeys(connection.getCatalog(), null, table)) {
            while (keys.next()) {
                bySequence.put(keys.getInt("KEY_SEQ"), keys.getString("COLUMN_NAME"));
            }
        }
        return new ArrayList<>(bySequence.values());
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }

    /** This plugin's newest migration recorded in {@code table} (null or absent: 0). */
    private int migrationVersion(Connection connection, String table) throws SQLException {
        if (table == null) {
            return 0;
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT MAX(version) FROM " + quote(table) + " WHERE owner = ?")) {
            select.setString(1, plugin);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private static boolean isHistory(String table) {
        return table.equalsIgnoreCase(RsDb.MIGRATIONS_TABLE);
    }

    String quote(String identifier) {
        return type == DbConfig.Type.MYSQL ? "`" + identifier + "`" : "\"" + identifier + "\"";
    }

    private static int indexIgnoringCase(List<String> names, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    private static void endSnapshot(Connection connection, boolean autoCommit) throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    // ------------------------------------------------------------------ file helpers

    @FunctionalInterface
    private interface LineWriter {
        void write(JsonWriter writer) throws IOException;
    }

    /** One JSON value and a line break. */
    private static void line(Writer out, LineWriter content) throws IOException {
        StringWriter text = new StringWriter();
        JsonWriter writer = new JsonWriter(text);
        content.write(writer);
        writer.flush();
        out.write(text.toString());
        out.write('\n');
    }

    /** Carries a SQLException through the JSON writer callbacks. */
    private static final class SqlInJson extends IOException {

        private static final long serialVersionUID = 1L;
        final transient SQLException cause;

        SqlInJson(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    /** Reads the file line by line, counting lines for the messages. */
    static final class DumpReader implements AutoCloseable {

        private final BufferedReader reader;
        private String peeked;
        private int line;

        DumpReader(Path file) throws IOException {
            this.reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8));
        }

        int lineNumber() {
            return line;
        }

        /** The next line; a missing one means the file was cut short. */
        JsonElement next() throws TransferRefused {
            String text = peeked != null ? peeked : read();
            peeked = null;
            if (text == null) {
                throw new TransferRefused("The file ends at line " + line + ", before its last line: it was cut"
                        + " short. Nothing was imported.");
            }
            try {
                return JsonParser.parseString(text);
            } catch (JsonParseException e) {
                throw bad("the line is not valid JSON");
            }
        }

        JsonObject objectLine(String what) throws TransferRefused {
            return object(next(), what);
        }

        boolean nextIsRow() throws TransferRefused {
            if (peeked == null) {
                peeked = read();
            }
            return peeked != null && peeked.startsWith("[");
        }

        JsonArray row() throws TransferRefused {
            JsonElement row = next();
            if (!row.isJsonArray()) {
                throw bad("expected a row");
            }
            return row.getAsJsonArray();
        }

        JsonObject object(JsonElement element, String what) throws TransferRefused {
            if (element == null || !element.isJsonObject()) {
                throw bad("expected " + what);
            }
            return element.getAsJsonObject();
        }

        String text(JsonObject object, String key) throws TransferRefused {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive()) {
                throw bad("missing " + key);
            }
            return value.getAsString();
        }

        int integer(JsonObject object, String key) throws TransferRefused {
            try {
                return Integer.parseInt(text(object, key));
            } catch (NumberFormatException e) {
                throw bad(key + " is not a whole number");
            }
        }

        TransferRefused bad(String problem) {
            return new TransferRefused("Line " + line + " of the file cannot be read: " + problem
                    + ". Nothing was imported.");
        }

        private String read() throws TransferRefused {
            try {
                String text = reader.readLine();
                if (text != null) {
                    line++;
                }
                return text;
            } catch (IOException e) {
                throw new TransferRefused("The file cannot be read after line " + line + " (" + e.getMessage()
                        + "): it may be cut short or damaged. Nothing was imported.", e);
            }
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
