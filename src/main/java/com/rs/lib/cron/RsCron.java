package com.rs.lib.cron;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;

import com.rs.lib.Rs;
import com.rs.lib.action.Requirement;
import com.rs.lib.action.RequirementEngine;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.RsYml;

/**
 * Jobs that run on the main thread at calendar times written as a {@link CronExpr}, reached
 * with {@code rs().cron()}.
 *
 * <pre>
 * rs().cron().schedule("payout", "0 4 * * *", this::payOut);        // every day at 04:00
 * rs().cron().create("backup", "daily 03:30").catchUp(true).schedule(this::backup);
 * rs().cron().cancel("payout");
 * </pre>
 *
 * <ul>
 *   <li>A job replaces an earlier one with the same id. An invalid expression throws
 *   {@link IllegalArgumentException} when the job is scheduled.</li>
 *   <li>Times are in the server's time zone; daylight saving is handled by {@link CronExpr}.</li>
 *   <li>After each run the next time is worked out again from the real clock, so a late run
 *   never shifts the ones after it. A task that throws is logged and keeps its schedule.</li>
 *   <li>Waits are checked against the real clock: steps of at most one minute, and in the last
 *   two seconds' approach each step covers half of what is left. A server down to 10 TPS still
 *   runs a job within about a second of its time, not minutes late.</li>
 *   <li>{@code catchUp(true)} keeps the time of the last run in
 *   {@code plugins/<Plugin>/cron-data.yml} ({@code last-run.<id>}, epoch milliseconds). When the
 *   job is scheduled and a run was missed since then (the server was off), it runs once right
 *   away, however many were missed. The first time there is no mark, so a new install never
 *   runs anything late; it only stores the current time. Without {@code config(...)} in the
 *   spec there is nowhere to keep the mark: a warning, and the job runs without catching up.</li>
 *   <li>Jobs stop when the plugin is disabled.</li>
 *   <li>Conditions, checked each time the job is due: {@code minPlayers(n)} and
 *   {@code maxPlayers(n)} (players online), {@code requirements(lines)} (a requirement list as
 *   {@link RequirementEngine} reads it, with PlaceholderAPI resolved for the server:
 *   {@code "%server_tps% >= 18"}; a line written wrong never holds) and {@code condition(test)}
 *   (the plugin's own test; one that throws is reported and does not hold). When one does not
 *   hold the run is skipped, noted in the debug output, and the job waits for its next time; a
 *   skipped run is not retried and counts as done for {@code catchUp}.</li>
 * </ul>
 *
 * <pre>
 * rs().cron().create("envoy", "0 20 * * 5,6")
 *         .minPlayers(5)
 *         .requirements(List.of("%server_tps% &gt;= 18"))
 *         .condition(() -&gt; !envoys.isRunning())
 *         .schedule(envoys::start);
 * </pre>
 */
public final class RsCron {

    /** The file that keeps the last runs of catch-up jobs. */
    public static final String DATA_FILE = "cron-data.yml";
    static final String LAST_RUN = "last-run.";
    /** The longest single wait before the real clock is checked again: one minute. */
    static final long MAX_STEP_TICKS = 1200L;
    /** Below this many ticks left (two seconds) the wait is taken in one step. */
    static final long FINAL_TICKS = 40L;
    private static final long MILLIS_PER_TICK = 50L;

    /** What the cron jobs need from the server; replaced in tests. */
    interface Host {

        Logger logger();

        /** Runs {@code task} on the main thread after {@code ticks} (at least 1); throws when it cannot. */
        TaskHandle later(long ticks, Runnable task);

        void debug(Supplier<String> message);

        /** The store of last runs, or null when the plugin has no config. */
        LastRuns lastRuns();

        /** How many players are online. */
        int onlinePlayers();

        /** PlaceholderAPI for the server (no player); the text unchanged when it cannot resolve. */
        String papi(String text);
    }

    /** Where catch-up jobs keep their last run. */
    interface LastRuns {

        /** The last run of {@code id} in epoch milliseconds, or -1 when there is none. */
        long get(String id);

        void put(String id, long millis);
    }

    private final Host host;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private volatile boolean warnedNoStore;

    /** Created by the context. */
    public RsCron(Rs rs) {
        this(new ServerHost(Objects.requireNonNull(rs, "rs")), System::currentTimeMillis, ZoneId.systemDefault());
    }

    RsCron(Host host, LongSupplier clock, ZoneId zone) {
        this.host = host;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * Schedules {@code task} at the times of {@code expr}, without catch-up. An invalid
     * expression logs a warning and schedules nothing.
     */
    public void schedule(String id, String expr, Runnable task) {
        create(id, expr).schedule(task);
    }

    /**
     * Starts a job to configure; nothing runs until {@link Builder#schedule(Runnable)}. An invalid
     * expression logs a warning and the builder's {@code schedule} then does nothing, leaving a
     * job already scheduled under this id as it was.
     *
     * @throws IllegalArgumentException when {@code id} is null or blank
     */
    public Builder create(String id, String expr) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A cron job needs an id");
        }
        String trimmed = id.trim();
        CronExpr parsed;
        try {
            parsed = CronExpr.parse(expr);
        } catch (IllegalArgumentException invalid) {
            host.logger().warning("Cron job '" + trimmed + "': " + invalid.getMessage() + "; not scheduled.");
            parsed = null;
        }
        return new Builder(trimmed, parsed);
    }

    /** Stops the job {@code id}; true when there was one. Its last-run mark stays on disk. */
    public boolean cancel(String id) {
        Job job = id == null ? null : jobs.remove(id.trim());
        if (job == null) {
            return false;
        }
        job.stop();
        return true;
    }

    /** True when a job with this id is scheduled. */
    public boolean isScheduled(String id) {
        return id != null && jobs.containsKey(id.trim());
    }

    /** The next time job {@code id} runs, or null when there is no such job. */
    public ZonedDateTime nextRun(String id) {
        Job job = id == null ? null : jobs.get(id.trim());
        return job == null ? null : ZonedDateTime.ofInstant(Instant.ofEpochMilli(job.target), zone);
    }

    /** Stops every job; called by the context when the plugin is disabled. */
    public void shutdown() {
        stopped = true;
        for (Job job : jobs.values()) {
            job.stop();
        }
        jobs.clear();
    }

    /** Configures one job. */
    public final class Builder {

        private final String id;
        /** Null when the expression was invalid: nothing is scheduled. */
        private final CronExpr expr;
        private boolean catchUp;
        private int minPlayers;
        private int maxPlayers = Integer.MAX_VALUE;
        private Requirement requirement = Requirement.ALWAYS;
        private final List<BooleanSupplier> tests = new ArrayList<>(1);

        private Builder(String id, CronExpr expr) {
            this.id = id;
            this.expr = expr;
        }

        /** Run once at start-up when a run was missed while the server was off (default false). */
        public Builder catchUp(boolean catchUp) {
            this.catchUp = catchUp;
            return this;
        }

        /** Runs only with at least {@code players} online (0, the default, always). */
        public Builder minPlayers(int players) {
            this.minPlayers = Math.max(0, players);
            return this;
        }

        /** Runs only with at most {@code players} online (no limit by default; a negative one means none). */
        public Builder maxPlayers(int players) {
            this.maxPlayers = players < 0 ? Integer.MAX_VALUE : players;
            return this;
        }

        /**
         * Runs only when every line holds, as {@link RequirementEngine} reads them, with
         * PlaceholderAPI resolved for the server. A line written wrong is reported now and never
         * holds. Null or empty: no requirement. Replaces earlier requirements of this builder.
         */
        public Builder requirements(List<String> lines) {
            this.requirement = RequirementEngine.parse(lines,
                    message -> host.logger().warning("Cron job '" + id + "': " + message));
            return this;
        }

        /** Runs only when {@code test} answers true, asked on the main thread each time; adds to earlier tests. */
        public Builder condition(BooleanSupplier test) {
            tests.add(Objects.requireNonNull(test, "test"));
            return this;
        }

        /** Schedules the job, replacing one with the same id; does nothing for an invalid expression. */
        public void schedule(Runnable task) {
            Objects.requireNonNull(task, "task");
            if (expr == null) {
                return;
            }
            start(new Job(id, expr, task, catchUp,
                    new Conditions(minPlayers, maxPlayers, requirement, List.copyOf(tests))));
        }
    }

    // ------------------------------------------------------------------ internals

    private void start(Job job) {
        if (stopped) {
            host.logger().warning("Cron job '" + job.id + "' was scheduled after shutdown; ignored.");
            return;
        }
        LastRuns store = job.catchUp ? store() : null;
        long now = clock.getAsLong();
        boolean missed = false;
        if (store != null) {
            long last = store.get(job.id);
            if (last < 0) {
                store.put(job.id, now);
            } else {
                missed = next(job.expr, last) <= now;
            }
        }
        Job previous = jobs.put(job.id, job);
        if (previous != null) {
            previous.stop();
        }
        if (missed) {
            host.debug(() -> "Cron job '" + job.id + "' missed a run while the server was off; running it now.");
            job.target = now;
            arm(job, 1L);
        } else {
            plan(job, now);
        }
    }

    /** Works out the next run of {@code job} from {@code now} and waits for it. */
    private void plan(Job job, long now) {
        job.target = next(job.expr, now);
        host.debug(() -> "Cron job '" + job.id + "' runs next at "
                + ZonedDateTime.ofInstant(Instant.ofEpochMilli(job.target), zone) + ".");
        waitFor(job, now);
    }

    /** Waits part of the way to the job's time; {@link #wake} looks at the clock again. */
    private void waitFor(Job job, long now) {
        long ticks = (job.target - now + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK;
        long step = ticks <= FINAL_TICKS ? ticks : Math.min(MAX_STEP_TICKS, ticks / 2);
        arm(job, Math.max(1L, step));
    }

    private void arm(Job job, long ticks) {
        if (job.stopped) {
            return;
        }
        try {
            job.task = host.later(ticks, () -> wake(job));
        } catch (RuntimeException notScheduled) {
            // The plugin is being disabled: nothing more will run.
            jobs.remove(job.id, job);
        }
    }

    /** A wait ended: run the job if its time came, otherwise keep waiting. */
    void wake(Job job) {
        if (job.stopped || stopped || jobs.get(job.id) != job) {
            return;
        }
        long now = clock.getAsLong();
        if (now < job.target) {
            waitFor(job, now);
            return;
        }
        if (job.catchUp) {
            LastRuns store = store();
            if (store != null) {
                store.put(job.id, now);
            }
        }
        String blocked = blocked(job);
        if (blocked != null) {
            host.debug(() -> "Cron job '" + job.id + "' skipped: " + blocked + ".");
        } else {
            try {
                job.run.run();
            } catch (RuntimeException | LinkageError e) {
                host.logger().log(Level.WARNING, "Cron job '" + job.id + "' failed", e);
            }
        }
        if (!job.stopped && jobs.get(job.id) == job) {
            plan(job, clock.getAsLong());
        }
    }

    /** Why {@code job} must not run now, or null when every condition holds. */
    private String blocked(Job job) {
        Conditions when = job.conditions;
        if (when.minPlayers > 0 || when.maxPlayers < Integer.MAX_VALUE) {
            int online = host.onlinePlayers();
            if (online < when.minPlayers) {
                return online + " players online, it needs at least " + when.minPlayers;
            }
            if (online > when.maxPlayers) {
                return online + " players online, it allows at most " + when.maxPlayers;
            }
        }
        if (when.requirement != Requirement.ALWAYS
                && !when.requirement.test(null, operand -> RsText.applyFallbacks(host.papi(operand)))) {
            return "its requirements do not hold (" + when.requirement + ")";
        }
        for (BooleanSupplier test : when.tests) {
            boolean holds;
            try {
                holds = test.getAsBoolean();
            } catch (RuntimeException | LinkageError e) {
                host.logger().log(Level.WARNING, "A condition of cron job '" + job.id + "' failed; the run is skipped", e);
                holds = false;
            }
            if (!holds) {
                return "a condition of the plugin does not hold";
            }
        }
        return null;
    }

    private LastRuns store() {
        LastRuns store = host.lastRuns();
        if (store == null && !warnedNoStore) {
            warnedNoStore = true;
            host.logger().warning("Cron jobs with catchUp(true) need config(...) in the spec to remember their"
                    + " last run; they run without catching up.");
        }
        return store;
    }

    private long next(CronExpr expr, long fromMillis) {
        return expr.nextRun(ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), zone)).toInstant().toEpochMilli();
    }

    /** One scheduled job. */
    static final class Job {

        final String id;
        final CronExpr expr;
        final Runnable run;
        final boolean catchUp;
        final Conditions conditions;
        /** When it runs next, in epoch milliseconds. */
        volatile long target;
        volatile TaskHandle task;
        volatile boolean stopped;

        Job(String id, CronExpr expr, Runnable run, boolean catchUp, Conditions conditions) {
            this.id = id;
            this.expr = expr;
            this.run = run;
            this.catchUp = catchUp;
            this.conditions = conditions;
        }

        void stop() {
            stopped = true;
            TaskHandle current = task;
            if (current != null) {
                current.cancel();
            }
        }
    }

    /** What must hold when a job is due; the defaults always hold. */
    record Conditions(int minPlayers, int maxPlayers, Requirement requirement, List<BooleanSupplier> tests) {
    }

    /** The real server. */
    private static final class ServerHost implements Host {

        private final Rs rs;
        private volatile LastRuns lastRuns;

        ServerHost(Rs rs) {
            this.rs = rs;
        }

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public TaskHandle later(long ticks, Runnable task) {
            return rs.scheduler().syncLater(ticks, task);
        }

        @Override
        public void debug(Supplier<String> message) {
            rs.debug().log("cron", message);
        }

        @Override
        public int onlinePlayers() {
            return Bukkit.getOnlinePlayers().size();
        }

        @Override
        public String papi(String text) {
            return rs.papi().apply(null, text);
        }

        @Override
        public LastRuns lastRuns() {
            LastRuns current = lastRuns;
            if (current != null) {
                return current;
            }
            if (rs.spec().config() == null) {
                return null;
            }
            synchronized (this) {
                if (lastRuns == null) {
                    RsYml file = rs.yml().data(DATA_FILE);
                    lastRuns = new LastRuns() {
                        @Override
                        public long get(String id) {
                            return file.isSet(LAST_RUN + id) ? file.getLong(LAST_RUN + id, -1L) : -1L;
                        }

                        @Override
                        public void put(String id, long millis) {
                            file.set(LAST_RUN + id, millis);
                            file.save();
                        }
                    };
                }
                return lastRuns;
            }
        }
    }
}
