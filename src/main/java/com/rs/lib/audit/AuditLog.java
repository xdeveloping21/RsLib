package com.rs.lib.audit;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Entity;

import com.rs.lib.Rs;
import com.rs.lib.RsLib;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.RsFuture;
import com.rs.lib.scheduler.TaskHandle;

/**
 * The plugin's staff audit log, reached with {@code rs().audit()} (needs {@code db()} in the
 * spec): who did what to whom, where, with how much money and which items.
 *
 * <pre>
 * // any thread, once the action really happened
 * rs().audit().entry("market-buy").actor(buyer).target(sellerId, sellerName)
 *         .money(price).items(AuditItems.describe(stack)).details("listing #" + id).record();
 *
 * AuditItems.Diff diff = AuditItems.diff(before, AuditItems.snapshot(chest.getContents()));
 * rs().audit().entry("chest-edit").actor(player).at(chest.getLocation()).items(diff.describe()).record();
 * </pre>
 *
 * <ul>
 *   <li>{@code record()} only queues the entry. The queue is written in one batch every
 *   {@code audit.flush-seconds} (RsLib's config.yml) or as soon as 500 entries wait, off the
 *   main thread, and one last time when the plugin disables. A failed write is retried with
 *   the next one; past 10,000 waiting entries the oldest are dropped and the console says so.</li>
 *   <li>Entries live in the plugin's database (table {@code rslib_audit}) and are deleted after
 *   {@code audit.keep-days} days (0 keeps them), a minute after the log starts and every six
 *   hours.</li>
 *   <li>{@link #query} writes what is queued first, so a read never misses what just happened.
 *   Staff read every plugin's log with {@code /rslib logs}.</li>
 *   <li>Actions are keys such as {@code crate-keys-give}: stored in lower case, with spaces and
 *   underscores turned into dashes, 48 characters at most. Names are cut to 32 characters and
 *   texts to 4000.</li>
 * </ul>
 *
 * <p>Every method is safe from any thread. Text is stored as given and shown literally.</p>
 */
public final class AuditLog {

    static final int MAX_ACTION = 48;
    static final int MAX_NAME = 32;
    static final int MAX_TEXT = 4000;
    static final int MAX_QUEUE = 10_000;
    /** Entries waiting that trigger a write before {@code flush-seconds}. */
    static final int BATCH_TRIGGER = 500;
    /** How often the queue is looked at: every second. */
    static final long CHECK_PERIOD_TICKS = 20L;
    /** One minute after start, then every six hours. */
    static final long FIRST_PURGE_TICKS = 1200L;
    static final long PURGE_PERIOD_TICKS = 432_000L;
    /** What an actor that is not a player is stored as. */
    public static final String CONSOLE = "CONSOLE";
    private static final long CLOSE_WAIT_SECONDS = 5L;

    /** Starts a repeating async task; throws when it cannot be scheduled. */
    @FunctionalInterface
    interface Timers {
        TaskHandle every(long delayTicks, long periodTicks, Runnable task);
    }

    private final Rs rs;
    private final Logger logger;
    private final LongSupplier clock;
    private final Timers timers;
    private final Supplier<RsDb> db;
    private final String owner;
    private final Supplier<AuditSettings> settings;
    private final Predicate<Entity> ownsHere;
    private final AuditStore store;
    private final ConcurrentLinkedDeque<AuditEntry> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean overflowWarned = new AtomicBoolean();
    private final AtomicBoolean failureWarned = new AtomicBoolean();
    private final Object lock = new Object();
    private final Object flushLock = new Object();
    /** Guarded by {@link #flushLock}. */
    private CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);
    private volatile long lastFlush;
    private volatile CompletableFuture<Void> ready;
    private final List<TaskHandle> tasks = new ArrayList<>();
    private volatile boolean stopped;

    /** Created by the context when the spec declares {@code db()}; starts with the first entry. */
    public AuditLog(Rs rs) {
        this(rs, rs.plugin().getLogger(), System::currentTimeMillis,
                (delay, period, task) -> rs.scheduler().timerAsync(delay, period, task), rs::db,
                rs.plugin().getName(), AuditSettings::current, entity -> rs.scheduler().ownsHere(entity));
    }

    AuditLog(Rs rs, Logger logger, LongSupplier clock, Timers timers, Supplier<RsDb> db, String owner,
             Supplier<AuditSettings> settings, Predicate<Entity> ownsHere) {
        this.rs = rs;
        this.logger = logger;
        this.clock = clock;
        this.timers = timers;
        this.db = db;
        this.owner = owner;
        this.settings = settings;
        this.ownsHere = ownsHere;
        this.store = new AuditStore(owner);
        this.lastFlush = clock.getAsLong();
    }

    /** The name the entries are stored under: the plugin's. */
    public String plugin() {
        return owner;
    }

    /**
     * Starts an entry for {@code action} (such as {@code market-buy}); finish it with
     * {@link AuditBuilder#record()}.
     *
     * @throws IllegalArgumentException for a blank action or one longer than 48 characters
     */
    public AuditBuilder entry(String action) {
        return new AuditBuilder(this, normalizeAction(action));
    }

    /**
     * Reads one page of this plugin's entries, newest first, after writing what is queued.
     * The {@link AuditQuery#plugin() plugin} filter of the query is matched against this plugin.
     */
    public RsFuture<AuditPage> query(AuditQuery query) {
        return RsFuture.wrap(Objects.requireNonNull(rs, "rs"), find(query));
    }

    /**
     * Reads one page across every plugin keeping a log (those enabled now), newest first. Each
     * plugin writes what it has queued first. Used by {@code /rslib logs}.
     */
    public static CompletableFuture<AuditPage> search(AuditQuery query) {
        List<AuditLog> logs = new ArrayList<>();
        for (Rs context : RsLib.contexts()) {
            AuditLog log = context.auditOrNull();
            if (log != null) {
                logs.add(log);
            }
        }
        return search(logs, query);
    }

    /** {@link #search(AuditQuery)} over {@code logs}: each reads its first pages, then they are merged. */
    static CompletableFuture<AuditPage> search(List<AuditLog> logs, AuditQuery query) {
        List<CompletableFuture<AuditPage>> reads = new ArrayList<>();
        int window = query.page() * query.pageSize();
        for (AuditLog log : logs) {
            if (query.acceptsPlugin(log.owner)) {
                reads.add(log.find(query, 0, window).exceptionally(error -> {
                    log.logger.log(Level.WARNING, "Could not read the audit log", error);
                    return AuditPage.empty(query);
                }));
            }
        }
        return CompletableFuture.allOf(reads.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
            List<AuditEntry> all = new ArrayList<>();
            int total = 0;
            for (CompletableFuture<AuditPage> read : reads) {
                AuditPage page = read.join();
                all.addAll(page.entries());
                total += page.total();
            }
            all.sort(Comparator.comparingLong(AuditEntry::createdAt).reversed()
                    .thenComparing(AuditEntry::plugin).thenComparing(Comparator.comparingLong(AuditEntry::id).reversed()));
            int from = Math.min(all.size(), (query.page() - 1) * query.pageSize());
            int to = Math.min(all.size(), from + query.pageSize());
            return new AuditPage(all.subList(from, to), total, query.page(), query.pageSize());
        });
    }

    /**
     * Stops the timers and writes what is queued (waiting up to 5 s for a write already
     * running); the database finishes that write while it closes. Called by the context on
     * shutdown, before the database closes.
     */
    public void shutdown() {
        stopped = true;
        synchronized (lock) {
            tasks.forEach(TaskHandle::cancel);
            tasks.clear();
        }
        CompletableFuture<Void> previous;
        synchronized (flushLock) {
            previous = inFlight;
        }
        CompletableFuture<Void> table = ready;
        try {
            // The table first: a write queued behind it would reach the database after it closed.
            if (table != null) {
                table.get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
            }
            previous.get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warning("An audit log write took longer than " + CLOSE_WAIT_SECONDS + " s; "
                    + queued.get() + " recent entries may be lost.");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException e) {
            // Logged by the write itself; its entries are queued again.
        }
        flush();
    }

    // ------------------------------------------------------------------ internals

    /** Queues a finished entry; called by {@link AuditBuilder#record()}. */
    void add(AuditEntry entry) {
        ready();
        queue.addLast(entry);
        if (queued.incrementAndGet() > MAX_QUEUE) {
            AuditEntry dropped = queue.pollFirst();
            if (dropped != null) {
                queued.decrementAndGet();
                if (overflowWarned.compareAndSet(false, true)) {
                    logger.warning("More than " + MAX_QUEUE + " audit log entries are waiting to be written;"
                            + " the oldest are dropped until the database answers again.");
                }
            }
        }
    }

    long now() {
        return clock.getAsLong();
    }

    boolean ownsHere(Entity entity) {
        try {
            return ownsHere.test(entity);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Entries waiting to be written. */
    int queued() {
        return queued.get();
    }

    CompletableFuture<AuditPage> find(AuditQuery query) {
        return find(query, (query.page() - 1) * query.pageSize(), query.pageSize());
    }

    /** Writes the queue, then reads rows {@code offset} to {@code offset + limit} of the query. */
    CompletableFuture<AuditPage> find(AuditQuery query, int offset, int limit) {
        Objects.requireNonNull(query, "query");
        if (!query.acceptsPlugin(owner)) {
            return CompletableFuture.completedFuture(AuditPage.empty(query));
        }
        boolean started = ready != null;
        AtomicReference<AuditPage> page = new AtomicReference<>(AuditPage.empty(query));
        CompletableFuture<Void> before = started ? flush().handle((ignored, error) -> null) : CompletableFuture.completedFuture(null);
        return before.thenCompose(ignored -> db.get().transaction(connection -> {
            if (started || AuditStore.exists(connection)) {
                page.set(store.find(connection, query, offset, limit));
            }
        }).toCompletableFuture()).thenApply(ignored -> page.get());
    }

    /** Called every second: writes the queue when it is due or large. */
    void tick() {
        if (queue.isEmpty()) {
            return;
        }
        long dueAfter = settings.get().flushSeconds() * 1000L;
        if (queued.get() >= BATCH_TRIGGER || clock.getAsLong() - lastFlush >= dueAfter) {
            synchronized (flushLock) {
                if (!inFlight.isDone()) {
                    return;
                }
            }
            flush();
        }
    }

    /** Writes everything queued; when a write is running, waits for it and writes what is left. */
    CompletableFuture<Void> flush() {
        synchronized (flushLock) {
            if (!inFlight.isDone()) {
                return inFlight.handle((ignored, error) -> null).thenCompose(ignored -> flush());
            }
            lastFlush = clock.getAsLong();
            List<AuditEntry> batch = new ArrayList<>();
            AuditEntry next;
            while ((next = queue.pollFirst()) != null) {
                queued.decrementAndGet();
                batch.add(next);
            }
            if (batch.isEmpty()) {
                return inFlight;
            }
            CompletableFuture<Void> write = run(connection -> store.insertAll(connection, batch));
            write.whenComplete((ignored, error) -> {
                if (error == null) {
                    failureWarned.set(false);
                    overflowWarned.set(false);
                    return;
                }
                for (int i = batch.size() - 1; i >= 0; i--) {
                    queue.addFirst(batch.get(i));
                    queued.incrementAndGet();
                }
                if (failureWarned.compareAndSet(false, true)) {
                    logger.log(Level.WARNING, "Could not write " + batch.size() + " audit log entries; retrying later", error);
                }
            });
            inFlight = write;
            return write;
        }
    }

    /** Deletes the entries older than {@code audit.keep-days}; the value is how many. */
    CompletableFuture<Integer> purge() {
        int keepDays = settings.get().keepDays();
        if (keepDays <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        long cutoff = clock.getAsLong() - keepDays * 86_400_000L;
        AtomicInteger deleted = new AtomicInteger();
        return run(connection -> deleted.set(store.purge(connection, cutoff))).handle((ignored, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Could not delete old audit log entries", error);
            }
            return deleted.get();
        });
    }

    private CompletableFuture<Void> run(RsDb.SqlConsumer<Connection> work) {
        return ready().thenCompose(ignored -> db.get().transaction(work).toCompletableFuture());
    }

    private CompletableFuture<Void> ready() {
        CompletableFuture<Void> current = ready;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (ready != null) {
                return ready;
            }
            RsDb database = db.get();
            var type = database.config().type();
            CompletableFuture<Void> created = database.bootstrap(connection -> AuditStore.create(connection, type))
                    .toCompletableFuture();
            created.whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.log(Level.SEVERE, "Could not create the table " + AuditStore.TABLE
                            + "; the audit log of " + owner + " is not kept", error);
                }
            });
            ready = created;
            if (!stopped) {
                try {
                    tasks.add(timers.every(CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS, this::tick));
                    tasks.add(timers.every(FIRST_PURGE_TICKS, PURGE_PERIOD_TICKS, this::purge));
                } catch (RuntimeException e) {
                    logger.warning("Could not schedule the audit log writes (" + e.getMessage()
                            + "); entries are written when read and on shutdown.");
                }
            }
            return created;
        }
    }

    /** {@code Market Buy} and {@code market_buy} are {@code market-buy}. */
    static String normalizeAction(String action) {
        Objects.requireNonNull(action, "action");
        String key = action.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        if (key.isEmpty() || key.length() > MAX_ACTION) {
            throw new IllegalArgumentException("An audit action needs 1 to " + MAX_ACTION + " characters: '" + action + "'");
        }
        return key;
    }

    static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
