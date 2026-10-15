package com.rs.lib.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One numbered change to a plugin's tables, run once by {@link RsDb#migrate(Migration...)}.
 *
 * <p>Tables that exist before the first migration are the baseline (version 0) and are never
 * touched by RsLib itself. Because a server may already have a change that the plugin used to
 * apply by hand, prefer steps that check first, such as {@link #addColumn} or
 * {@link Context#columnExists}.</p>
 *
 * <pre>
 * db.bootstrap(Schema.of("tops", "uuid VARCHAR(36) PRIMARY KEY", "wins BIGINT NOT NULL DEFAULT 0")).orDisablePlugin();
 * db.migrate(
 *         Migration.addColumn(1, "tops", "losses", "BIGINT NOT NULL DEFAULT 0"),
 *         Migration.of(2, "fill losses", ctx -&gt; ctx.execute("UPDATE tops SET losses = 0 WHERE losses IS NULL")))
 *   .orDisablePlugin();
 * </pre>
 */
public final class Migration {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The work of one migration; it runs inside a transaction. */
    @FunctionalInterface
    public interface Step {
        void apply(Context context) throws SQLException;
    }

    private final int version;
    private final String description;
    private final Step step;

    private Migration(int version, String description, Step step) {
        if (version < 1) {
            throw new IllegalArgumentException("Migration versions start at 1, got " + version);
        }
        this.version = version;
        this.description = description == null || description.isBlank() ? "migration " + version : description.trim();
        this.step = Objects.requireNonNull(step, "step");
    }

    /** A migration written as code. */
    public static Migration of(int version, String description, Step step) {
        return new Migration(version, description, step);
    }

    /** A migration made of plain statements, run in order. */
    public static Migration sql(int version, String description, String... statements) {
        String[] copy = statements.clone();
        for (String statement : copy) {
            if (statement == null || statement.isBlank()) {
                throw new IllegalArgumentException("Migration " + version + " has a blank statement");
            }
        }
        return new Migration(version, description, context -> {
            for (String statement : copy) {
                context.execute(statement);
            }
        });
    }

    /**
     * Adds {@code column} with {@code definition} (such as {@code "BIGINT NOT NULL DEFAULT 0"})
     * unless the table already has it.
     */
    public static Migration addColumn(int version, String table, String column, String definition) {
        identifier(table);
        identifier(column);
        if (definition == null || definition.isBlank()) {
            throw new IllegalArgumentException("Column " + column + " has no definition");
        }
        return new Migration(version, "add " + table + "." + column,
                context -> context.addColumnIfMissing(table, column, definition));
    }

    public int version() {
        return version;
    }

    public String description() {
        return description;
    }

    void apply(Context context) throws SQLException {
        step.apply(context);
    }

    private static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid table or column name: " + name);
        }
        return name;
    }

    @Override
    public String toString() {
        return version + " (" + description + ")";
    }

    /** The connection of a running migration, with checks that work on SQLite and MySQL. */
    public static final class Context {

        private final Connection connection;
        private final DbConfig.Type type;

        Context(Connection connection, DbConfig.Type type) {
            this.connection = connection;
            this.type = type;
        }

        /** The connection, inside the migration's transaction. */
        public Connection connection() {
            return connection;
        }

        /** The backend, to write dialect-specific SQL. */
        public DbConfig.Type type() {
            return type;
        }

        /** Runs one statement without parameters. */
        public void execute(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        /** Whether {@code table} exists (names compared ignoring case). */
        public boolean tableExists(String table) throws SQLException {
            return storedName(table) != null;
        }

        /** Whether {@code table} exists and has {@code column} (names compared ignoring case). */
        public boolean columnExists(String table, String column) throws SQLException {
            identifier(column);
            String stored = storedName(table);
            return stored != null && hasColumn(stored, column);
        }

        /**
         * {@code ALTER TABLE table ADD COLUMN column definition} unless the column is there.
         *
         * @return true when the column was added
         */
        public boolean addColumnIfMissing(String table, String column, String definition) throws SQLException {
            identifier(column);
            String stored = storedName(table);
            if (stored != null && hasColumn(stored, column)) {
                return false;
            }
            execute("ALTER TABLE " + (stored == null ? table : stored) + " ADD COLUMN " + column + " "
                    + definition.trim());
            return true;
        }

        private boolean hasColumn(String storedTable, String column) throws SQLException {
            try (Statement statement = connection.createStatement();
                 ResultSet empty = statement.executeQuery("SELECT * FROM " + storedTable + " WHERE 1 = 0")) {
                ResultSetMetaData meta = empty.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    if (column.equalsIgnoreCase(meta.getColumnName(i))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The table's name as the database stores it, or null when there is none: MySQL on
         * Linux tells {@code Shops} and {@code shops} apart in queries.
         */
        private String storedName(String table) throws SQLException {
            identifier(table);
            DatabaseMetaData meta = connection.getMetaData();
            String caseInsensitiveMatch = null;
            try (ResultSet tables = meta.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String name = tables.getString("TABLE_NAME");
                    if (table.equals(name)) {
                        return name;
                    }
                    if (caseInsensitiveMatch == null && table.equalsIgnoreCase(name)) {
                        caseInsensitiveMatch = name;
                    }
                }
            }
            return caseInsensitiveMatch;
        }
    }
}
