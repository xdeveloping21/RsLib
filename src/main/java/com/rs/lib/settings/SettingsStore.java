package com.rs.lib.settings;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.rs.lib.db.RsDb;
import com.rs.lib.db.Schema;

/**
 * The database side of one plugin's settings: one row per plugin, player and setting in
 * {@value #TABLE}, only for values that differ from the default.
 *
 * <p>Every read and write waits for the one before it, so two quick clicks reach the database
 * in order (MySQL too) and a join read never overtakes a write of the same plugin.</p>
 */
final class SettingsStore {

    static final String TABLE = "rslib_player_settings";
    static final int MAX_VALUE = 255;
    private static final long CLOSE_WAIT_SECONDS = 5L;

    private final RsDb db;
    private final String owner;
    private final Logger logger;
    private final Object lock = new Object();
    /** Guarded by {@link #lock}: the last read or write queued. */
    private CompletableFuture<?> tail;

    SettingsStore(RsDb db, String owner, Logger logger) {
        this.db = db;
        this.owner = owner;
        this.logger = logger;
        CompletableFuture<Void> created = db.bootstrap(Schema.of(TABLE, "owner VARCHAR(64) NOT NULL",
                "uuid VARCHAR(36) NOT NULL", "setting VARCHAR(64) NOT NULL",
                "setting_value VARCHAR(" + MAX_VALUE + ") NOT NULL", "PRIMARY KEY (owner, uuid, setting)"))
                .toCompletableFuture();
        created.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not create " + TABLE + "; player settings keep their defaults", error);
            }
        });
        this.tail = created;
    }

    /** The stored values of {@code player}, setting to value. */
    CompletableFuture<Map<String, String>> load(UUID player) {
        return chain(() -> db.query("SELECT setting, setting_value FROM " + TABLE + " WHERE owner = ? AND uuid = ?",
                statement -> {
                    statement.setString(1, owner);
                    statement.setString(2, player.toString());
                }, rows -> {
                    Map<String, String> values = new HashMap<>();
                    while (rows.next()) {
                        values.put(rows.getString(1), rows.getString(2));
                    }
                    return values;
                }).toCompletableFuture());
    }

    /** Stores {@code value} for {@code player}, or deletes the row when it is null (back to the default). */
    CompletableFuture<Integer> write(UUID player, String setting, String value) {
        CompletableFuture<Integer> write = chain(() -> value == null
                ? db.update("DELETE FROM " + TABLE + " WHERE owner = ? AND uuid = ? AND setting = ?", statement -> {
                    statement.setString(1, owner);
                    statement.setString(2, player.toString());
                    statement.setString(3, setting);
                }).toCompletableFuture()
                : db.upsert(TABLE).keys("owner", owner).keys("uuid", player.toString()).keys("setting", setting)
                        .set("setting_value", value).run().toCompletableFuture());
        write.whenComplete((ignored, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not save setting " + setting + " of " + player, error);
            }
        });
        return write;
    }

    /** Waits (up to five seconds) for the reads and writes queued so far; the database closes after. */
    void close() {
        CompletableFuture<?> last;
        synchronized (lock) {
            last = tail;
        }
        try {
            last.get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warning("Saving player settings took longer than " + CLOSE_WAIT_SECONDS
                    + " s; the latest changes may be lost.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Logged by the write itself.
        }
    }

    /** Completes after everything queued so far; for tests. */
    CompletableFuture<?> idle() {
        synchronized (lock) {
            return tail.handle((value, error) -> null);
        }
    }

    private <T> CompletableFuture<T> chain(Supplier<CompletableFuture<T>> work) {
        synchronized (lock) {
            CompletableFuture<T> next = tail.handle((value, error) -> null).thenCompose(ignored -> work.get());
            tail = next;
            return next;
        }
    }
}
