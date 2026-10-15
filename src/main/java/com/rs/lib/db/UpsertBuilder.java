package com.rs.lib.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Insert-or-update of one row in the dialect of the plugin's database, from
 * {@code db.upsert(table)}.
 *
 * <pre>
 * db.upsert("balances").keys("uuid", uuid).set("coins", coins).run();
 * </pre>
 *
 * <ul>
 *   <li>SQLite: {@code INSERT ... ON CONFLICT(keys) DO UPDATE SET c=excluded.c} (or
 *   {@code DO NOTHING} without {@code set}). The table needs a PRIMARY KEY or UNIQUE constraint
 *   on exactly the {@code keys} columns.</li>
 *   <li>MySQL: {@code INSERT ... ON DUPLICATE KEY UPDATE c=VALUES(c)}; MySQL uses the table's
 *   unique indexes, not the declared keys. Without {@code set}, a no-op assignment of the
 *   first key. A row that existed counts as 2 affected rows when it changed, 1 when it already
 *   had those values.</li>
 * </ul>
 *
 * <p>Table and column names are identifiers written in the plugin's code
 * ({@code [A-Za-z_][A-Za-z0-9_]*}); values are bound by position with {@code setObject}, a
 * {@link UUID} as its text form. Setting the same column twice keeps the last value.</p>
 */
public final class UpsertBuilder {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final RsDb db;
    private final DbConfig.Type type;
    private final String table;
    /** Lower-case name to column, in the order first given. */
    private final Map<String, Column> keys = new LinkedHashMap<>();
    private final Map<String, Column> sets = new LinkedHashMap<>();

    private record Column(String name, Object value) {
    }

    UpsertBuilder(RsDb db, DbConfig.Type type, String table) {
        this.db = db;
        this.type = type;
        this.table = identifier(table, "table");
    }

    /** A key column: part of the PRIMARY KEY or UNIQUE constraint the row is matched on. */
    public UpsertBuilder keys(String column, Object value) {
        put(keys, sets, column, value);
        return this;
    }

    /** A column written on insert and overwritten on conflict. */
    public UpsertBuilder set(String column, Object value) {
        put(sets, keys, column, value);
        return this;
    }

    /**
     * Runs the statement off the main thread.
     *
     * @return the affected rows (on MySQL an existing row that changed counts as 2)
     * @throws IllegalStateException when no {@link #keys} column was given
     */
    public RsFuture<Integer> run() {
        String sql = sql();
        List<Object> values = values();
        return db.update(sql, ps -> {
            for (int i = 0; i < values.size(); i++) {
                ps.setObject(i + 1, values.get(i));
            }
        });
    }

    /** The statement for the current columns. */
    String sql() {
        if (keys.isEmpty()) {
            throw new IllegalStateException("Upsert into " + table + " has no keys(...) column");
        }
        List<String> keyNames = names(keys);
        List<String> setNames = names(sets);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        join(sql, keyNames);
        if (!setNames.isEmpty()) {
            sql.append(", ");
            join(sql, setNames);
        }
        sql.append(") VALUES (");
        for (int i = 0, n = keyNames.size() + setNames.size(); i < n; i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(')');
        if (type == DbConfig.Type.MYSQL) {
            sql.append(" ON DUPLICATE KEY UPDATE ");
            if (setNames.isEmpty()) {
                String first = keyNames.get(0);
                sql.append(first).append('=').append(first);
            } else {
                assignments(sql, setNames, "VALUES(", ")");
            }
        } else {
            sql.append(" ON CONFLICT(");
            join(sql, keyNames);
            sql.append(')');
            if (setNames.isEmpty()) {
                sql.append(" DO NOTHING");
            } else {
                sql.append(" DO UPDATE SET ");
                assignments(sql, setNames, "excluded.", "");
            }
        }
        return sql.toString();
    }

    /** Bound values, keys first, in the order of {@link #sql()}. */
    List<Object> values() {
        List<Object> out = new ArrayList<>(keys.size() + sets.size());
        for (Column column : keys.values()) {
            out.add(bindable(column.value()));
        }
        for (Column column : sets.values()) {
            out.add(bindable(column.value()));
        }
        return out;
    }

    private static Object bindable(Object value) {
        return value instanceof UUID uuid ? uuid.toString() : value;
    }

    private void put(Map<String, Column> into, Map<String, Column> other, String column, Object value) {
        String name = identifier(column, "column");
        String key = name.toLowerCase(Locale.ROOT);
        if (other.containsKey(key)) {
            throw new IllegalArgumentException("Column " + name + " of " + table + " is both a key and a set column");
        }
        into.put(key, new Column(name, value));
    }

    private static String identifier(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " name for an upsert: " + name);
        }
        return name;
    }

    private static List<String> names(Map<String, Column> columns) {
        List<String> out = new ArrayList<>(columns.size());
        for (Column column : columns.values()) {
            out.add(column.name());
        }
        return out;
    }

    private static void join(StringBuilder sql, List<String> names) {
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(names.get(i));
        }
    }

    private static void assignments(StringBuilder sql, List<String> names, String before, String after) {
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(name).append('=').append(before).append(name).append(after);
        }
    }
}
