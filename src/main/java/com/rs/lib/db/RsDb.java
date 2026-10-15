package com.rs.lib.db;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;

import com.rs.lib.Rs;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * One plugin's SQLite or MySQL database, reached with {@code rs().db()} after declaring
 * {@code RsSpec.builder().db()}. Settings come from the {@code database:} section of the main
 * config ({@link DbConfig}).
 *
 * <p><b>JDBC never runs on the main thread.</b> Every operation is queued on the plugin's own
 * database threads ({@code <Plugin>-db}) and returns an {@link RsFuture}, consumed on the main
 * thread with {@code thenSync} or {@code chainSync}. There are as many threads as pooled
 * connections, so borrowing a connection never waits on another borrow:</p>
 * <ul>
 *   <li>SQLite: one thread and one connection, so operations run in the order they were queued
 *   (a write followed by a read sees the write). WAL journal, 5 s busy timeout.</li>
 *   <li>MySQL: {@code pool-size} threads; operations may run in any order.</li>
 * </ul>
 *
 * <p>Nothing is opened until the first operation. A failed operation fails its future and
 * never throws at the caller. On shutdown, dirty {@link PlayerDataCache} values are saved and
 * waited for, then queued work gets up to 10 s to finish before the pool closes.</p>
 */
public final class RsDb {

    /** JDBC work that receives a statement or a connection. */
    @FunctionalInterface
    public interface SqlConsumer<T> {
        void accept(T t) throws SQLException;
    }

    /** JDBC work that maps a result set or a connection to a value. */
    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T t) throws SQLException;
    }

    /** How long shutdown waits for queued work, and separately for player-cache saves. */
    static final long SHUTDOWN_WAIT_SECONDS = 10L;
    static final String MIGRATIONS_TABLE = "rslib_migrations";
    private static final long IDLE_THREAD_SECONDS = 60L;
    private static final int SQLITE_BUSY_TIMEOUT_MS = 5000;
    private static final long DEFAULT_VALIDATION_TIMEOUT_MS = 5000L;

    private final DbHost host;
    private final DbConfig config;
    private final ThreadPoolExecutor executor;
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final Object dataSourceLock = new Object();
    private volatile HikariDataSource dataSource;
    /** Guarded by {@link #dataSourceLock}: true once the pool is closed for good. */
    private boolean poolClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger bootstraps = new AtomicInteger();
    private final List<PlayerDataCache<?>> caches = new CopyOnWriteArrayList<>();

    /** Created by the context when the spec declares {@code db()}; opens nothing yet. */
    public RsDb(Rs rs, DbConfig config) {
        this(DbHost.of(Objects.requireNonNull(rs, "rs")), config);
    }

    RsDb(DbHost host, DbConfig config) {
        this.host = host;
        this.config = Objects.requireNonNull(config, "config");
        int count = config.threads();
        this.executor = new ThreadPoolExecutor(count, count, IDLE_THREAD_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), threadFactory(host.name(), count));
        this.executor.allowCoreThreadTimeOut(true);
    }

    public DbConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ operations

    /**
     * Runs a query: {@code binder} sets the parameters (may be null) and {@code mapper} turns
     * the result set into the value.
     */
    public <R> RsFuture<R> query(String sql, SqlConsumer<PreparedStatement> binder, SqlFunction<ResultSet, R> mapper) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(mapper, "mapper");
        return future(submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (binder != null) {
                    binder.accept(statement);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    return mapper.apply(rows);
                }
            }
        }));
    }

    /** Runs an INSERT, UPDATE, DELETE or DDL statement; the value is the affected row count. */
    public RsFuture<Integer> update(String sql, SqlConsumer<PreparedStatement> binder) {
        Objects.requireNonNull(sql, "sql");
        return future(submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (binder != null) {
                    binder.accept(statement);
                }
                return statement.executeUpdate();
            }
        }));
    }

    /**
     * Runs {@code work} in one transaction: committed when it returns, rolled back when it
     * throws (the future then fails). The connection's auto-commit is restored afterwards.
     */
    public RsFuture<Void> transaction(SqlConsumer<Connection> work) {
        Objects.requireNonNull(work, "work");
        return future(submit(connection -> {
            inTransaction(connection, work);
            return null;
        }));
    }

    /**
     * Creates the tables that do not exist yet, in order, in one task. Existing tables are left
     * as they are. Waiting for it on the main thread is fine (no warning):
     * {@code db.bootstrap(...).orDisablePlugin()} or {@code .join()} in {@code onInnerEnable}.
     */
    public RsFuture<Void> bootstrap(Schema... schemas) {
        Schema[] copy = schemas.clone();
        for (Schema schema : copy) {
            Objects.requireNonNull(schema, "schema");
        }
        return bootstrap(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (Schema schema : copy) {
                    statement.execute(schema.createSql());
                }
            }
        });
    }

    /**
     * Startup work of any kind, with the same main-thread allowance as
     * {@link #bootstrap(Schema...)}; runs with auto-commit.
     */
    public RsFuture<Void> bootstrap(SqlConsumer<Connection> work) {
        Objects.requireNonNull(work, "work");
        return startup(connection -> {
            work.accept(connection);
            return null;
        });
    }

    /**
     * Brings the plugin's tables to the newest {@link Migration}. Applied versions are recorded
     * per plugin in {@code rslib_migrations}; a database without records is at version 0, so
     * the tables it already has are the baseline and stay as they are. Each pending migration
     * runs in its own transaction, in version order, together with its record; the first
     * failure stops the run and fails the future. MySQL commits schema statements (such as
     * {@code ALTER TABLE}) on its own, so write steps that can run again. Like
     * {@link #bootstrap(Schema...)}, it may be waited for on the main thread.
     *
     * @return the version the database is at afterwards
     */
    public RsFuture<Integer> migrate(Migration... migrations) {
        Migration[] sorted = migrations.clone();
        Arrays.sort(sorted, Comparator.comparingInt(Migration::version));
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i].version() == sorted[i - 1].version()) {
                throw new IllegalArgumentException("Two migrations have version " + sorted[i].version());
            }
        }
        return startup(connection -> runMigrations(connection, sorted));
    }

    /** An insert-or-update in this database's dialect. */
    public UpsertBuilder upsert(String table) {
        return new UpsertBuilder(this, config.type(), table);
    }

    /**
     * A per-player cache filled on join by {@code loader} (on the async pool) and written on
     * quit and shutdown by {@code saver}. See {@link PlayerDataCache}.
     */
    public <T> PlayerDataCache<T> playerCache(BiFunction<RsDb, UUID, T> loader, PlayerDataCache.Saver<T> saver) {
        if (closed.get()) {
            throw new IllegalStateException(closedMessage());
        }
        PlayerDataCache<T> cache = new PlayerDataCache<>(this, host, loader, saver);
        caches.add(cache);
        PlayerDataCache.register(cache);
        host.onQuit(cache::quit);
        return cache;
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Saves every dirty player-cache value and waits up to 10 s for those writes. Called by the
     * context on shutdown, before {@link #shutdown()}.
     */
    public void flushPlayerCaches() {
        if (caches.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> fences = new ArrayList<>(caches.size());
        for (PlayerDataCache<?> cache : caches) {
            fences.add(cache.saveDirty());
        }
        try {
            CompletableFuture.allOf(fences.toArray(new CompletableFuture<?>[0]))
                    .get(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            host.logger().warning("Player data saves did not finish within " + SHUTDOWN_WAIT_SECONDS
                    + " s; the database gets another " + SHUTDOWN_WAIT_SECONDS + " s while it closes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            host.logger().log(Level.WARNING, "A player data save failed", e.getCause());
        }
    }

    /**
     * Refuses new work, lets queued work finish (up to 10 s, then interrupts it and fails what
     * never started) and closes the pool. Idempotent; called by the context on shutdown.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        PlayerDataCache.unregister(caches);
        executor.shutdown();
        boolean finished;
        try {
            finished = executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            List<Runnable> dropped = executor.shutdownNow();
            for (Runnable job : dropped) {
                if (job instanceof Job<?> pending) {
                    pending.abandon();
                }
            }
            host.logger().warning("Database work of " + host.name() + " did not finish within " + SHUTDOWN_WAIT_SECONDS
                    + " s; interrupted it and dropped " + dropped.size() + " queued task(s).");
            ClassLoader own = RsDb.class.getClassLoader();
            for (Thread straggler : threads) {
                straggler.setContextClassLoader(own);
            }
        }
        HikariDataSource pool;
        synchronized (dataSourceLock) {
            poolClosed = true;
            pool = dataSource;
            dataSource = null;
        }
        if (pool != null) {
            pool.close();
        }
        caches.clear();
    }

    /** True once {@link #shutdown()} started; new work fails. */
    public boolean isClosed() {
        return closed.get();
    }

    // ------------------------------------------------------------------ internals

    /** True while startup work (bootstrap, migrations) is pending: main-thread joins are fine. */
    boolean bootstrapping() {
        return bootstraps.get() > 0;
    }

    /** Completes when the database threads reached everything queued before it (exact on SQLite). */
    CompletableFuture<Void> fence() {
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        Job<Void> job = new Job<>(null);
        try {
            executor.execute(job);
        } catch (RejectedExecutionException closedMeanwhile) {
            return CompletableFuture.completedFuture(null);
        }
        return job.future;
    }

    <R> RsFuture<R> future(CompletableFuture<R> future) {
        return new RsFuture<>(host, future, false);
    }

    private <R> RsFuture<R> startup(SqlFunction<Connection, R> work) {
        bootstraps.incrementAndGet();
        CompletableFuture<R> future = submit(work);
        future.whenComplete((value, error) -> bootstraps.decrementAndGet());
        return future(future);
    }

    /** Queues {@code work} with a pooled connection; the future fails instead of throwing. */
    <R> CompletableFuture<R> submit(SqlFunction<Connection, R> work) {
        Job<R> job = new Job<>(work);
        if (closed.get()) {
            job.abandon();
            return job.future;
        }
        try {
            executor.execute(job);
        } catch (RejectedExecutionException closedMeanwhile) {
            job.abandon();
        }
        return job.future;
    }

    private HikariDataSource dataSource() {
        HikariDataSource current = dataSource;
        if (current != null) {
            return current;
        }
        synchronized (dataSourceLock) {
            if (poolClosed) {
                throw new IllegalStateException(closedMessage());
            }
            if (dataSource == null) {
                if (config.type() == DbConfig.Type.SQLITE) {
                    File parent = config.sqliteFile().getAbsoluteFile().getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IllegalStateException("Could not create the folder " + parent);
                    }
                }
                dataSource = new HikariDataSource(hikariConfig(config, host.name() + "-db"));
            }
            return dataSource;
        }
    }

    /** The pool settings for {@code config}. */
    static HikariConfig hikariConfig(DbConfig config, String poolName) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(poolName);
        hikari.setJdbcUrl(config.jdbcUrl());
        if (config.type() == DbConfig.Type.SQLITE) {
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setMaximumPoolSize(1);
            hikari.addDataSourceProperty("busy_timeout", String.valueOf(SQLITE_BUSY_TIMEOUT_MS));
            hikari.addDataSourceProperty("journal_mode", "WAL");
        } else {
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setUsername(config.username());
            hikari.setPassword(config.password());
            hikari.setMaximumPoolSize(Math.max(1, config.poolSize()));
            hikari.addDataSourceProperty("cachePrepStmts", "true");
            hikari.addDataSourceProperty("prepStmtCacheSize", "250");
            hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }
        long connectMs = config.connectTimeoutSeconds() * 1000L;
        hikari.setConnectionTimeout(connectMs);
        // The liveness check must fit in the connection budget.
        hikari.setValidationTimeout(Math.min(DEFAULT_VALIDATION_TIMEOUT_MS, connectMs));
        return hikari;
    }

    static void inTransaction(Connection connection, SqlConsumer<Connection> work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Throwable failure = null;
        try {
            work.accept(connection);
            connection.commit();
        } catch (SQLException | RuntimeException | Error e) {
            failure = e;
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException restore) {
                if (failure == null) {
                    throw restore;
                }
                failure.addSuppressed(restore);
            }
        }
    }

    private Integer runMigrations(Connection connection, Migration[] migrations) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + MIGRATIONS_TABLE + " (owner VARCHAR(64) NOT NULL, "
                    + "version INT NOT NULL, description VARCHAR(255) NOT NULL, applied_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (owner, version))");
        }
        String owner = host.name();
        int current;
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT MAX(version) FROM " + MIGRATIONS_TABLE + " WHERE owner = ?")) {
            select.setString(1, owner);
            try (ResultSet rows = select.executeQuery()) {
                current = rows.next() ? rows.getInt(1) : 0;
            }
        }
        int newest = migrations.length == 0 ? 0 : migrations[migrations.length - 1].version();
        if (current > newest) {
            host.logger().warning("The database is at migration " + current + ", newer than this version of "
                    + owner + " knows (" + newest + "). Was the plugin downgraded?");
            return current;
        }
        Migration.Context context = new Migration.Context(connection, config.type());
        for (Migration migration : migrations) {
            if (migration.version() <= current) {
                continue;
            }
            try {
                inTransaction(connection, c -> {
                    migration.apply(context);
                    try (PreparedStatement record = c.prepareStatement("INSERT INTO " + MIGRATIONS_TABLE
                            + " (owner, version, description, applied_at) VALUES (?, ?, ?, ?)")) {
                        record.setString(1, owner);
                        record.setInt(2, migration.version());
                        String description = migration.description();
                        record.setString(3, description.length() > 255 ? description.substring(0, 255) : description);
                        record.setLong(4, System.currentTimeMillis());
                        record.executeUpdate();
                    }
                });
            } catch (SQLException | RuntimeException e) {
                throw new SQLException("Database migration " + migration + " failed", e);
            }
            current = migration.version();
            host.logger().info("Applied database migration " + migration + ".");
        }
        return current;
    }

    private String closedMessage() {
        return "RsDb closed: " + host.name();
    }

    private ThreadFactory threadFactory(String name, int count) {
        AtomicInteger next = new AtomicInteger();
        ClassLoader own = RsDb.class.getClassLoader();
        return task -> {
            String threadName = count == 1 ? name + "-db" : name + "-db-" + next.incrementAndGet();
            Thread thread = new Thread(() -> {
                try {
                    task.run();
                } finally {
                    threads.remove(Thread.currentThread());
                }
            }, threadName);
            thread.setDaemon(true);
            thread.setContextClassLoader(own);
            threads.add(thread);
            return thread;
        };
    }

    /** One queued operation; a null {@code work} is a write barrier that needs no connection. */
    private final class Job<R> implements Runnable {

        final CompletableFuture<R> future = new CompletableFuture<>();
        private final SqlFunction<Connection, R> work;

        Job(SqlFunction<Connection, R> work) {
            this.work = work;
        }

        @Override
        public void run() {
            if (work == null) {
                future.complete(null);
                return;
            }
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(host.classLoader());
            try {
                R result;
                try (Connection connection = dataSource().getConnection()) {
                    result = work.apply(connection);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                    return;
                }
                future.complete(result);
            } finally {
                thread.setContextClassLoader(previous);
            }
        }

        void abandon() {
            future.completeExceptionally(new IllegalStateException(closedMessage()));
        }
    }
}
