package com.rs.lib.audit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.rs.lib.db.DbConfig;

/**
 * The table {@code rslib_audit}: one row per entry, shared by every plugin that keeps its log in
 * the same database and always filtered by the plugin's name.
 */
final class AuditStore {

    static final String TABLE = "rslib_audit";
    private static final String COLUMNS = "id, owner, action, actor, actor_name, target, target_name, world, x, y, z, "
            + "money, items, details, created_at";

    private final String owner;

    AuditStore(String owner) {
        this.owner = owner;
    }

    static void create(Connection connection, DbConfig.Type type) throws SQLException {
        String columns = "owner VARCHAR(64) NOT NULL, action VARCHAR(" + AuditLog.MAX_ACTION + ") NOT NULL, "
                + "actor VARCHAR(36), actor_name VARCHAR(" + AuditLog.MAX_NAME + "), target VARCHAR(36), "
                + "target_name VARCHAR(" + AuditLog.MAX_NAME + "), world VARCHAR(64), x DOUBLE, y DOUBLE, z DOUBLE, "
                + "money VARCHAR(64), items TEXT, details TEXT, created_at BIGINT NOT NULL";
        try (Statement statement = connection.createStatement()) {
            if (type == DbConfig.Type.MYSQL) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + columns + ", INDEX rslib_audit_time (owner, created_at), "
                        + "INDEX rslib_audit_actor (owner, actor, created_at), "
                        + "INDEX rslib_audit_target (owner, target, created_at))");
            } else {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + columns + ")");
                statement.execute("CREATE INDEX IF NOT EXISTS rslib_audit_time ON " + TABLE + " (owner, created_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS rslib_audit_actor ON " + TABLE + " (owner, actor, created_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS rslib_audit_target ON " + TABLE + " (owner, target, created_at)");
            }
        }
    }

    /** True when the table exists, without creating it (a log that recorded nothing since start). */
    static boolean exists(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String name : new String[] {TABLE, TABLE.toUpperCase(Locale.ROOT)}) {
            try (ResultSet tables = meta.getTables(connection.getCatalog(), null, name, null)) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    void insertAll(Connection connection, List<AuditEntry> entries) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE + " (owner, action, actor, "
                + "actor_name, target, target_name, world, x, y, z, money, items, details, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (AuditEntry entry : entries) {
                insert.setString(1, owner);
                insert.setString(2, entry.action());
                insert.setString(3, text(entry.actorId()));
                insert.setString(4, entry.actorName());
                insert.setString(5, text(entry.targetId()));
                insert.setString(6, entry.targetName());
                insert.setString(7, entry.world());
                if (entry.world() == null) {
                    insert.setNull(8, Types.DOUBLE);
                    insert.setNull(9, Types.DOUBLE);
                    insert.setNull(10, Types.DOUBLE);
                } else {
                    insert.setDouble(8, entry.x());
                    insert.setDouble(9, entry.y());
                    insert.setDouble(10, entry.z());
                }
                insert.setString(11, entry.money() == null ? null : entry.money().toPlainString());
                insert.setString(12, entry.items());
                insert.setString(13, entry.details());
                insert.setLong(14, entry.createdAt());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /** The page {@code query} asks for, read as rows {@code offset} to {@code offset + limit}. */
    AuditPage find(Connection connection, AuditQuery query, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = where(query, params);
        int total;
        try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + TABLE + where)) {
            bind(count, params);
            try (ResultSet rows = count.executeQuery()) {
                total = rows.next() ? rows.getInt(1) : 0;
            }
        }
        List<AuditEntry> entries = new ArrayList<>();
        if (total > offset && limit > 0) {
            try (PreparedStatement select = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + TABLE + where
                    + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")) {
                bind(select, params);
                select.setInt(params.size() + 1, limit);
                select.setInt(params.size() + 2, offset);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        entries.add(read(rows));
                    }
                }
            }
        }
        return new AuditPage(entries, total, query.page(), query.pageSize());
    }

    /** Deletes this plugin's entries recorded before {@code cutoff}; returns how many. */
    int purge(Connection connection, long cutoff) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE owner = ? AND created_at < ?")) {
            delete.setString(1, owner);
            delete.setLong(2, cutoff);
            return delete.executeUpdate();
        }
    }

    private String where(AuditQuery query, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE owner = ?");
        params.add(owner);
        if (!query.actions().isEmpty()) {
            where.append(" AND action IN (");
            boolean first = true;
            for (String action : query.actions()) {
                where.append(first ? "?" : ", ?");
                params.add(action);
                first = false;
            }
            where.append(')');
        }
        if (query.player() != null) {
            where.append(" AND (actor = ? OR target = ?)");
            params.add(query.player().toString());
            params.add(query.player().toString());
        }
        if (query.actor() != null) {
            where.append(" AND actor = ?");
            params.add(query.actor().toString());
        }
        if (query.target() != null) {
            where.append(" AND target = ?");
            params.add(query.target().toString());
        }
        if (query.from() != null) {
            where.append(" AND created_at >= ?");
            params.add(query.from());
        }
        if (query.to() != null) {
            where.append(" AND created_at < ?");
            params.add(query.to());
        }
        return where.toString();
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Long number) {
                statement.setLong(i + 1, number);
            } else {
                statement.setString(i + 1, (String) value);
            }
        }
    }

    private static AuditEntry read(ResultSet rows) throws SQLException {
        String world = rows.getString(8);
        double x = rows.getDouble(9);
        double y = rows.getDouble(10);
        double z = rows.getDouble(11);
        return new AuditEntry(rows.getLong(1), rows.getString(2), rows.getString(3), uuid(rows.getString(4)),
                rows.getString(5), uuid(rows.getString(6)), rows.getString(7), world, x, y, z,
                decimal(rows.getString(12)), rows.getString(13), rows.getString(14), rows.getLong(15));
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    private static UUID uuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    private static BigDecimal decimal(String text) {
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }
}
