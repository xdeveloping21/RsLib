package com.rs.lib.notice;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rs.lib.db.DbConfig;

/**
 * The table {@code rslib_notices}: one row per waiting notice, shared by every plugin that
 * keeps its notices in the same database and always filtered by the plugin's name.
 */
final class NoticeStore {

    static final String TABLE = "rslib_notices";
    static final String INDEX = "rslib_notices_player";

    private final String owner;

    NoticeStore(String owner) {
        this.owner = owner;
    }

    static void create(Connection connection, DbConfig.Type type) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (type == DbConfig.Type.MYSQL) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + columns() + ", INDEX " + INDEX + " (owner, uuid))");
            } else {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + columns() + ")");
                statement.execute("CREATE INDEX IF NOT EXISTS " + INDEX + " ON " + TABLE + " (owner, uuid)");
            }
        }
    }

    private static String columns() {
        return "owner VARCHAR(64) NOT NULL, uuid VARCHAR(36) NOT NULL, category VARCHAR(" + Notices.MAX_CATEGORY
                + ") NOT NULL, message VARCHAR(" + Notices.MAX_KEY + ") NOT NULL, vals TEXT NOT NULL, sent_at BIGINT NOT NULL";
    }

    /** Stores {@code notice} unless {@code player} already has {@code max} waiting; true when stored. */
    boolean insert(Connection connection, UUID player, Notice notice, int max) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE owner = ? AND uuid = ?")) {
            count.setString(1, owner);
            count.setString(2, player.toString());
            try (ResultSet rows = count.executeQuery()) {
                if (rows.next() && rows.getInt(1) >= max) {
                    return false;
                }
            }
        }
        insertAll(connection, player, List.of(notice));
        return true;
    }

    /** Stores every notice of {@code notices} as it is (no limit): notices written back. */
    void insertAll(Connection connection, UUID player, List<Notice> notices) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE
                + " (owner, uuid, category, message, vals, sent_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (Notice notice : notices) {
                insert.setString(1, owner);
                insert.setString(2, player.toString());
                insert.setString(3, notice.category());
                insert.setString(4, notice.message());
                insert.setString(5, NoticeValues.encode(notice.values()));
                insert.setLong(6, notice.sentAt());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Takes the waiting notices of {@code player}, oldest first: deletes those sent before
     * {@code cutoff}, then deletes the rest one by one and returns only those whose delete
     * removed a row, so two servers taking at once never both get the same notice.
     */
    List<Notice> take(Connection connection, UUID player, long cutoff) throws SQLException {
        try (PreparedStatement expired = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE owner = ? AND uuid = ? AND sent_at < ?")) {
            expired.setString(1, owner);
            expired.setString(2, player.toString());
            expired.setLong(3, cutoff);
            expired.executeUpdate();
        }
        List<Notice> found = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id, category, message, vals, sent_at FROM "
                + TABLE + " WHERE owner = ? AND uuid = ? ORDER BY id")) {
            select.setString(1, owner);
            select.setString(2, player.toString());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(new Notice(rows.getLong(1), rows.getString(2), rows.getString(3),
                            NoticeValues.decode(rows.getString(4)), rows.getLong(5)));
                }
            }
        }
        return deleteEach(connection, found);
    }

    /**
     * Deletes the rows of {@code found} one by one; returns the notices whose delete removed a
     * row. A row another server deleted first is left out: that server shows it.
     */
    List<Notice> deleteEach(Connection connection, List<Notice> found) throws SQLException {
        if (found.isEmpty()) {
            return found;
        }
        List<Notice> taken = new ArrayList<>(found.size());
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
            for (Notice notice : found) {
                delete.setLong(1, notice.id());
                if (delete.executeUpdate() == 1) {
                    taken.add(notice);
                }
            }
        }
        return taken;
    }

    /** Deletes this plugin's notices sent before {@code cutoff}; returns how many. */
    int purge(Connection connection, long cutoff) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE owner = ? AND sent_at < ?")) {
            delete.setString(1, owner);
            delete.setLong(2, cutoff);
            return delete.executeUpdate();
        }
    }
}
