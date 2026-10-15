package com.rs.lib.db;

import java.util.Objects;

/**
 * One table a plugin needs, created by {@link RsDb#bootstrap(Schema...)} with
 * {@code CREATE TABLE IF NOT EXISTS}.
 *
 * <p>An existing table is never altered: changing a definition here does not change tables
 * that servers already have. Use {@link RsDb#migrate(Migration...)} for that.</p>
 */
public final class Schema {

    private final String table;
    private final String createSql;

    private Schema(String table, String createSql) {
        this.table = table;
        this.createSql = createSql;
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS <table> (<defs joined by ", ">)}; each definition is a
     * column or a constraint, such as {@code "uuid VARCHAR(36) PRIMARY KEY"} or
     * {@code "PRIMARY KEY (uuid, kit)"}.
     */
    public static Schema of(String table, String... columnDefs) {
        String name = requireName(table);
        if (columnDefs == null || columnDefs.length == 0) {
            throw new IllegalArgumentException("Table " + name + " has no columns");
        }
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(name).append(" (");
        for (int i = 0; i < columnDefs.length; i++) {
            String def = columnDefs[i];
            if (def == null || def.isBlank()) {
                throw new IllegalArgumentException("Table " + name + " has a blank column definition");
            }
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(def.trim());
        }
        return new Schema(name, sql.append(')').toString());
    }

    /** A statement written by hand, for dialect-specific tables; run as given. */
    public static Schema raw(String table, String createSql) {
        String name = requireName(table);
        if (createSql == null || createSql.isBlank()) {
            throw new IllegalArgumentException("Table " + name + " has no CREATE statement");
        }
        return new Schema(name, createSql);
    }

    private static String requireName(String table) {
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) {
            throw new IllegalArgumentException("Blank table name");
        }
        return table.trim();
    }

    public String table() {
        return table;
    }

    public String createSql() {
        return createSql;
    }

    @Override
    public String toString() {
        return createSql;
    }
}
