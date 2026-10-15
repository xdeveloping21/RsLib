package com.rs.lib.cooldown;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.rs.lib.db.DbConfig;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.RsFuture;
import com.rs.lib.scheduler.TaskHandle;

/**
 * The database side of persistent cooldown categories: one row per plugin, category and player
 * in {@code rslib_cooldowns}, with the expiry in epoch milliseconds.
 *
 * <p>Changes are collected in memory and written in one transaction every 30 seconds and on
 * shutdown; only one write runs at a time, so they reach MySQL in order too. A failed write is
 * queued again unless a newer change replaced it.</p>
 */
final class CooldownStore {

    static final String TABLE = "rslib_cooldowns";
    static final int MAX_CATEGORY = 64;
    /** Thirty seconds. */
    static final long FLUSH_PERIOD_TICKS = 600L;
    private static final long CLOSE_WAIT_SECONDS = 5L;
    /** Pending value meaning "delete the row". */
    private static final long DELETE = 0L;

    private final Cooldowns cooldowns;
    private final RsDb db;
    private final String owner;
    private final Logger logger;
    private final LongSupplier clock;
    private final Set<String> categories = ConcurrentHashMap.newKeySet();
    /** Latest change per row: an expiry, or {@link #DELETE}. */
    private final Map<Key, Long> pending = new ConcurrentHashMap<>();
    /** Categories whose rows are all deleted before the pending changes are written. */
    private final Set<String> wipes = ConcurrentHashMap.newKeySet();
    private final Object flushLock = new Object();
    /** Guarded by {@link #flushLock}. */
    private CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);
    private volatile TaskHandle timer;

    record Key(String category, UUID player) {
    }

    CooldownStore(Cooldowns cooldowns, RsDb db, String owner, Logger logger, LongSupplier clock) {
        this.cooldowns = cooldowns;
        this.db = db;
        this.owner = owner;
        this.logger = logger;
        this.clock = clock;
    }

    void start(Cooldowns.Timers timers) {
        try {
            timer = timers.every(FLUSH_PERIOD_TICKS, this::flush);
        } catch (RuntimeException e) {
            logger.warning("Could not schedule the cooldown saves (" + e.getMessage()
                    + "); they happen on shutdown only.");
        }
    }

    /** Starts keeping {@code category} and loads its running cooldowns. */
    RsFuture<Void> register(String category) {
        categories.add(category);
        long now = clock.getAsLong();
        return db.bootstrap(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " (owner VARCHAR(64) NOT NULL, "
                        + "category VARCHAR(" + MAX_CATEGORY + ") NOT NULL, uuid VARCHAR(36) NOT NULL, "
                        + "expires_at BIGINT NOT NULL, PRIMARY KEY (owner, category, uuid))");
            }
            try (PreparedStatement expired = connection.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE owner = ? AND expires_at <= ?")) {
                expired.setString(1, owner);
                expired.setLong(2, now);
                expired.executeUpdate();
            }
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT uuid, expires_at FROM " + TABLE + " WHERE owner = ? AND category = ?")) {
                select.setString(1, owner);
                select.setString(2, category);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        restore(category, rows.getString(1), rows.getLong(2));
                    }
                }
            }
        });
    }

    /** A cooldown of {@code category} was started. */
    void written(String category, UUID player, long expiresAt) {
        if (categories.contains(category)) {
            pending.put(new Key(category, player), expiresAt);
        }
    }

    /** A cooldown of {@code category} was cleared. */
    void deleted(String category, UUID player) {
        if (categories.contains(category)) {
            pending.put(new Key(category, player), DELETE);
        }
    }

    /** Every persistent category was cleared: older pending changes no longer matter. */
    void wipeAll() {
        wipes.addAll(categories);
        pending.keySet().removeIf(key -> wipes.contains(key.category()));
    }

    /** Writes what changed since the last write, unless a write is still running. */
    CompletableFuture<Void> flush() {
        synchronized (flushLock) {
            if (!inFlight.isDone() || (pending.isEmpty() && wipes.isEmpty())) {
                return inFlight;
            }
            List<String> wiped = new ArrayList<>(wipes);
            wipes.removeAll(wiped);
            Map<Key, Long> batch = new HashMap<>();
            for (Key key : pending.keySet()) {
                Long value = pending.remove(key);
                if (value != null) {
                    batch.put(key, value);
                }
            }
            long now = clock.getAsLong();
            CompletableFuture<Void> write = db.transaction(connection -> write(connection, wiped, batch, now))
                    .toCompletableFuture();
            write.whenComplete((ignored, error) -> {
                if (error != null) {
                    wipes.addAll(wiped);
                    batch.forEach(pending::putIfAbsent);
                    logger.log(Level.WARNING, "Could not save cooldowns; retrying later", error);
                }
            });
            inFlight = write;
            return write;
        }
    }

    /** Stops the timer and queues the last write; the database drains it while it closes. */
    void close() {
        TaskHandle running = timer;
        if (running != null) {
            running.cancel();
        }
        CompletableFuture<Void> previous;
        synchronized (flushLock) {
            previous = inFlight;
        }
        try {
            previous.get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warning("A cooldown save took longer than " + CLOSE_WAIT_SECONDS
                    + " s; recent cooldowns may be lost.");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException e) {
            // Logged by the write itself; its changes are queued again.
        }
        flush();
    }

    private void restore(String category, String uuid, long expiresAt) {
        UUID player;
        try {
            player = UUID.fromString(uuid);
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        cooldowns.restore(category, player, expiresAt);
    }

    private void write(Connection connection, List<String> wiped, Map<Key, Long> batch, long now) throws SQLException {
        if (!wiped.isEmpty()) {
            try (PreparedStatement wipe = connection.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE owner = ? AND category = ?")) {
                for (String category : wiped) {
                    wipe.setString(1, owner);
                    wipe.setString(2, category);
                    wipe.addBatch();
                }
                wipe.executeBatch();
            }
        }
        boolean upserts = false;
        boolean deletes = false;
        try (PreparedStatement upsert = connection.prepareStatement(upsertSql(db.config().type()));
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE owner = ? AND category = ? AND uuid = ?")) {
            for (Map.Entry<Key, Long> entry : batch.entrySet()) {
                Key key = entry.getKey();
                long expiresAt = entry.getValue();
                if (expiresAt == DELETE) {
                    delete.setString(1, owner);
                    delete.setString(2, key.category());
                    delete.setString(3, key.player().toString());
                    delete.addBatch();
                    deletes = true;
                } else if (expiresAt > now) {
                    upsert.setString(1, owner);
                    upsert.setString(2, key.category());
                    upsert.setString(3, key.player().toString());
                    upsert.setLong(4, expiresAt);
                    upsert.addBatch();
                    upserts = true;
                }
            }
            if (deletes) {
                delete.executeBatch();
            }
            if (upserts) {
                upsert.executeBatch();
            }
        }
    }

    static String upsertSql(DbConfig.Type type) {
        String insert = "INSERT INTO " + TABLE + " (owner, category, uuid, expires_at) VALUES (?, ?, ?, ?)";
        return type == DbConfig.Type.MYSQL
                ? insert + " ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at)"
                : insert + " ON CONFLICT(owner, category, uuid) DO UPDATE SET expires_at=excluded.expires_at";
    }

    /** Pending changes, for tests. */
    int pendingChanges() {
        return pending.size() + wipes.size();
    }
}
