package com.rs.lib.reward;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import com.rs.lib.db.DbConfig;
import com.rs.lib.db.RsDb;

/**
 * Rewards kept for later in {@code rslib_pending_rewards}: one row per stack, command, amount
 * of money, ... owed to a player, per plugin ({@code owner}).
 *
 * <p><b>Each row is handed over at most once.</b> {@link #take} reads a player's rows and
 * deletes them one by one in the same transaction, keeping only the rows its own
 * {@code DELETE} removed; the caller gives them only after the commit. Two claims at the same
 * time (a join and a command, or two servers on one MySQL database) cannot both get a row:
 * the second {@code DELETE} of a row removes nothing. A crash between the commit and the
 * delivery loses that delivery instead of giving it twice; RsLib chose that side on purpose
 * (a duplicated reward is an exploit, a lost one is a support ticket), keeps the window to one
 * tick, and puts rows back whenever it knows they were not given (the player left, the plugin
 * is shutting down, an item could not be read).</p>
 */
final class PendingStore {

    static final String TABLE = "rslib_pending_rewards";
    static final int MAX_ENTRY = 64;
    static final int MAX_OWNER = 64;

    /** One thing owed. {@code data}: Base64 item, command, message or {@code "effect ticks amplifier"}. */
    record Row(String entry, RewardType type, String data, double amount) {
    }

    private final RsDb db;
    private final String owner;
    private final LongSupplier clock;
    private final Object readyLock = new Object();
    /** Guarded by {@link #readyLock}: the table exists once it completes normally. */
    private CompletableFuture<Void> ready;

    PendingStore(RsDb db, String owner, LongSupplier clock) {
        this.db = db;
        this.owner = owner.length() > MAX_OWNER ? owner.substring(0, MAX_OWNER) : owner;
        this.clock = clock;
    }

    /** Creates the table once; a failed attempt is tried again by the next operation. */
    CompletableFuture<Void> ready() {
        synchronized (readyLock) {
            if (ready == null || ready.isCompletedExceptionally()) {
                boolean mysql = db.config().type() == DbConfig.Type.MYSQL;
                ready = db.bootstrap(connection -> create(connection, mysql)).toCompletableFuture();
            }
            return ready;
        }
    }

    private static void create(Connection connection, boolean mysql) throws SQLException {
        String columns = "id VARCHAR(36) NOT NULL PRIMARY KEY, owner VARCHAR(" + MAX_OWNER + ") NOT NULL, "
                + "uuid VARCHAR(36) NOT NULL, entry VARCHAR(" + MAX_ENTRY + ") NOT NULL, kind VARCHAR(16) NOT NULL, "
                + "data " + (mysql ? "MEDIUMTEXT" : "TEXT") + ", amount DOUBLE NOT NULL, "
                + "created_at BIGINT NOT NULL, seq INTEGER NOT NULL";
        try (Statement statement = connection.createStatement()) {
            if (mysql) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (" + columns
                        + ", INDEX " + TABLE + "_player (owner, uuid))");
            } else {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (" + columns + ")");
                statement.execute("CREATE INDEX IF NOT EXISTS " + TABLE + "_player ON " + TABLE + " (owner, uuid)");
            }
        }
    }

    /** Keeps {@code rows} for {@code player}, all or none (one transaction). */
    CompletableFuture<Void> put(UUID player, List<Row> rows) {
        if (rows.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<Row> copy = List.copyOf(rows);
        long now = clock.getAsLong();
        return ready().thenCompose(ignored -> db.transaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE
                    + " (id, owner, uuid, entry, kind, data, amount, created_at, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (int i = 0; i < copy.size(); i++) {
                    Row row = copy.get(i);
                    String entry = row.entry().length() > MAX_ENTRY ? row.entry().substring(0, MAX_ENTRY) : row.entry();
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, owner);
                    insert.setString(3, player.toString());
                    insert.setString(4, entry);
                    insert.setString(5, row.type().name());
                    insert.setString(6, row.data());
                    insert.setDouble(7, row.amount());
                    insert.setLong(8, now);
                    insert.setInt(9, i);
                    insert.executeUpdate();
                }
            }
        }).toCompletableFuture());
    }

    /**
     * Takes every row of {@code player}: read and deleted in one transaction, and only the rows
     * this transaction deleted are returned. When {@code abort} says so before the commit (the
     * plugin is shutting down), everything is rolled back and the future fails. Rows of a kind
     * this version does not know stay in the table.
     */
    CompletableFuture<List<Row>> take(UUID player, BooleanSupplier abort) {
        List<Row> taken = new ArrayList<>();
        return ready().thenCompose(ignored -> db.transaction(connection -> {
            taken.clear();
            List<String> ids = new ArrayList<>();
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("SELECT id, entry, kind, data, amount FROM "
                    + TABLE + " WHERE owner = ? AND uuid = ? ORDER BY created_at, seq")) {
                select.setString(1, owner);
                select.setString(2, player.toString());
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        RewardType type = RewardType.parse(result.getString(3));
                        if (type != null) {
                            ids.add(result.getString(1));
                            rows.add(new Row(result.getString(2), type, result.getString(4), result.getDouble(5)));
                        }
                    }
                }
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
                for (int i = 0; i < ids.size(); i++) {
                    delete.setString(1, ids.get(i));
                    if (delete.executeUpdate() == 1) {
                        taken.add(rows.get(i));
                    }
                }
            }
            if (abort.getAsBoolean()) {
                throw new SQLException("The plugin is shutting down; the rewards stay kept.");
            }
        }).toCompletableFuture()).thenApply(ignored -> List.copyOf(taken));
    }

    /** How many rows {@code player} is owed. */
    CompletableFuture<Integer> count(UUID player) {
        return ready().thenCompose(ignored -> db.query("SELECT COUNT(*) FROM " + TABLE + " WHERE owner = ? AND uuid = ?",
                statement -> {
                    statement.setString(1, owner);
                    statement.setString(2, player.toString());
                }, result -> result.next() ? result.getInt(1) : 0).toCompletableFuture());
    }
}
