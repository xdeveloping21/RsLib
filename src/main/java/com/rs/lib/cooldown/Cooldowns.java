package com.rs.lib.cooldown;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

import com.rs.lib.Rs;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.RsFuture;
import com.rs.lib.scheduler.TaskHandle;

/**
 * Cooldowns per category and player, reached with {@code rs().cooldowns()}.
 *
 * <pre>
 * if (!rs().cooldowns().tryUse(player.getUniqueId(), "heal", Duration.ofSeconds(30))) {
 *     long left = rs().cooldowns().remainingMillis(player.getUniqueId(), "heal");
 *     ...
 * }
 * </pre>
 *
 * <ul>
 *   <li>Entries survive a relog: reconnecting does not reset a cooldown. Categories registered
 *   with {@link #registerSessionCategory} are cleared when the player leaves.</li>
 *   <li>By default nothing is written to disk and a restart clears everything. A category
 *   registered with {@link #registerPersistentCategory} is also kept in the plugin's database
 *   and survives restarts (needs {@code RsSpec.builder().db()}).</li>
 *   <li>Ticks are converted at a fixed 50 ms each, whatever the server's TPS.</li>
 *   <li>Category names are plain keys shared by everything in the plugin that uses them.</li>
 * </ul>
 *
 * <p>Thread-safe. Expired entries are dropped lazily and by an async task every five minutes.</p>
 */
public final class Cooldowns {

    static final long MILLIS_PER_TICK = 50L;
    /** Five minutes. */
    static final long PURGE_PERIOD_TICKS = 6000L;

    /** Starts a repeating async task; throws when it cannot be scheduled. */
    @FunctionalInterface
    interface Timers {
        TaskHandle every(long periodTicks, Runnable task);
    }

    /** Category to player to {@code {expiry in epoch ms}}; arrays are never changed once stored. */
    private final Map<String, Map<UUID, long[]>> categories = new ConcurrentHashMap<>();
    private final Set<String> sessionCategories = ConcurrentHashMap.newKeySet();
    private final Logger logger;
    private final LongSupplier clock;
    private final Timers timers;
    private final Supplier<RsDb> db;
    private final String owner;
    private final Object lock = new Object();
    private volatile TaskHandle purgeTask;
    private volatile boolean stopped;
    /** Guarded by {@link #lock}. */
    private boolean purgeWarned;
    private volatile CooldownStore store;

    /** Created by the context. */
    public Cooldowns(Rs rs) {
        this(rs.plugin().getLogger(), System::currentTimeMillis,
                (period, task) -> rs.scheduler().timerAsync(period, period, task), rs::db, rs.plugin().getName());
        rs.onQuit(player -> clearSession(player.getUniqueId()));
    }

    Cooldowns(Logger logger, LongSupplier clock, Timers timers, Supplier<RsDb> db, String owner) {
        this.logger = logger;
        this.clock = clock;
        this.timers = timers;
        this.db = db;
        this.owner = owner;
    }

    /**
     * Starts the cooldown and returns true when {@code player} is not on cooldown in
     * {@code category}; returns false and changes nothing when they are. A zero or negative
     * duration always returns true and records nothing.
     */
    public boolean tryUse(UUID player, String category, Duration cooldown) {
        Objects.requireNonNull(cooldown, "cooldown");
        return tryUseMillis(player, category, toMillis(cooldown));
    }

    /** {@link #tryUse} with a duration in ticks of 50 ms. */
    public boolean tryUseTicks(UUID player, String category, long cooldownTicks) {
        return tryUseMillis(player, category, multiplyCapped(cooldownTicks, MILLIS_PER_TICK));
    }

    /** Milliseconds left on {@code player}'s cooldown in {@code category}; 0 when there is none. */
    public long remainingMillis(UUID player, String category) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(category, "category");
        Map<UUID, long[]> players = categories.get(category);
        if (players == null) {
            return 0L;
        }
        long[] slot = players.get(player);
        if (slot == null) {
            return 0L;
        }
        long left = slot[0] - clock.getAsLong();
        if (left > 0) {
            return left;
        }
        players.remove(player, slot);
        return 0L;
    }

    /** Cooldowns of {@code category} are cleared when the player leaves the server. */
    public void registerSessionCategory(String category) {
        sessionCategories.add(Objects.requireNonNull(category, "category"));
    }

    /**
     * Keeps {@code category} in the plugin's database (table {@code rslib_cooldowns}) so its
     * cooldowns survive restarts, and loads the ones still running. Call it in
     * {@code onInnerEnable}; the future completes once they are loaded (it may be joined on the
     * main thread). Writes are batched every 30 seconds and on shutdown.
     *
     * @throws UnsupportedOperationException when the plugin did not declare {@code db()}
     * @throws IllegalArgumentException      for a blank name or one longer than 64 characters
     */
    public RsFuture<Void> registerPersistentCategory(String category) {
        Objects.requireNonNull(category, "category");
        if (category.isBlank() || category.length() > CooldownStore.MAX_CATEGORY) {
            throw new IllegalArgumentException("A persistent cooldown category needs 1 to "
                    + CooldownStore.MAX_CATEGORY + " characters: '" + category + "'");
        }
        return store().register(category);
    }

    /** Clears the session categories of {@code player}; called when they leave. */
    public void clearSession(UUID player) {
        Objects.requireNonNull(player, "player");
        CooldownStore persisted = store;
        for (String category : sessionCategories) {
            Map<UUID, long[]> players = categories.get(category);
            if (players != null && players.remove(player) != null && persisted != null) {
                persisted.deleted(category, player);
            }
        }
    }

    /**
     * Clears every cooldown of every player, persistent ones included, and stops the cleanup
     * task; the next cooldown that is armed starts it again.
     */
    public void clearAll() {
        TaskHandle purge;
        synchronized (lock) {
            purge = purgeTask;
            purgeTask = null;
        }
        if (purge != null) {
            purge.cancel();
        }
        CooldownStore persisted = store;
        for (Map.Entry<String, Map<UUID, long[]>> entry : categories.entrySet()) {
            entry.getValue().clear();
        }
        if (persisted != null) {
            persisted.wipeAll();
        }
    }

    /**
     * Stops the cleanup, writes pending persistent cooldowns (before the database closes) and
     * forgets everything in memory. Called by the context on shutdown.
     */
    public void shutdown() {
        TaskHandle purge;
        synchronized (lock) {
            stopped = true;
            purge = purgeTask;
            purgeTask = null;
        }
        if (purge != null) {
            purge.cancel();
        }
        CooldownStore persisted = store;
        if (persisted != null) {
            persisted.close();
        }
        categories.clear();
    }

    // ------------------------------------------------------------------ internals

    private boolean tryUseMillis(UUID player, String category, long millis) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(category, "category");
        if (millis <= 0) {
            return true;
        }
        long now = clock.getAsLong();
        Map<UUID, long[]> players = categories.computeIfAbsent(category, k -> new ConcurrentHashMap<>());
        long[] current = players.get(player);
        if (current != null && current[0] > now) {
            return false;
        }
        long[] fresh = {addCapped(now, millis)};
        if (players.compute(player, (k, slot) -> slot != null && slot[0] > now ? slot : fresh) != fresh) {
            return false;
        }
        ensurePurge();
        CooldownStore persisted = store;
        if (persisted != null) {
            persisted.written(category, player, fresh[0]);
        }
        return true;
    }

    /** Called by the store while loading a persistent category: the later expiry wins. */
    void restore(String category, UUID player, long expiresAt) {
        categories.computeIfAbsent(category, k -> new ConcurrentHashMap<>())
                .merge(player, new long[]{expiresAt}, (old, loaded) -> old[0] >= loaded[0] ? old : loaded);
    }

    /** Drops expired entries; runs every five minutes off the main thread. */
    void purgeExpired() {
        long now = clock.getAsLong();
        for (Map<UUID, long[]> players : categories.values()) {
            players.values().removeIf(slot -> slot[0] <= now);
        }
    }

    /** Entries held for {@code category}, expired or not; for tests. */
    int size(String category) {
        Map<UUID, long[]> players = categories.get(category);
        return players == null ? 0 : players.size();
    }

    private void ensurePurge() {
        if (purgeTask != null || stopped) {
            return;
        }
        synchronized (lock) {
            if (purgeTask != null || stopped) {
                return;
            }
            try {
                purgeTask = timers.every(PURGE_PERIOD_TICKS, this::purgeExpired);
            } catch (RuntimeException e) {
                if (!purgeWarned) {
                    purgeWarned = true;
                    logger.warning("Could not schedule the cooldown cleanup (" + e.getMessage()
                            + "); it is retried on the next use.");
                }
            }
        }
    }

    private CooldownStore store() {
        CooldownStore current = store;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (store == null) {
                CooldownStore created = new CooldownStore(this, db.get(), owner, logger, clock);
                if (!stopped) {
                    created.start(timers);
                }
                store = created;
            }
            return store;
        }
    }

    private static long toMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException tooLarge) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static long addCapped(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    private static long multiplyCapped(long ticks, long factor) {
        if (ticks <= 0) {
            return 0L;
        }
        return ticks > Long.MAX_VALUE / factor ? Long.MAX_VALUE : ticks * factor;
    }
}
