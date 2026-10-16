package com.rs.lib.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Many repeating jobs run by one task of the plugin, which ticks once per server tick while it
 * has jobs and stops when the last one is cancelled.
 *
 * <p>Timing is that of {@code timer(period, period, job)} on the classic scheduler: the first
 * run comes {@code period} ticks after the job was added (counted from the ticker's last run,
 * the same tick a Bukkit timer counts from when added outside the scheduler's own pass), then
 * every {@code period} ticks. Jobs due in the same tick run in the order they were added, as
 * Bukkit runs tasks due in the same tick in the order they were created. A job added or
 * cancelled while the ticker runs takes effect from the next tick; a job cancelled by an
 * earlier job of the same tick does not run.</p>
 *
 * <p>A job that throws is logged once, with the plugin's name and the stack trace, and
 * cancelled; the others go on. When the ticker's own task is cancelled from outside (the plugin
 * is disabled, or someone cancelled every task of the plugin) its jobs read as cancelled, like
 * the tasks they replace, and the next job added starts a new ticker.</p>
 */
final class SharedTicker {

    private static final Comparator<Job> BY_DUE = Comparator.<Job>comparingLong(job -> job.due)
            .thenComparingLong(job -> job.seq);

    private final Logger logger;
    private final String owner;
    /** Starts the ticker's task: {@code runnable} once per tick, from the next tick. */
    private final Function<Runnable, TaskHandle> starter;
    /** Guarded by {@code this}. */
    private final PriorityQueue<Job> queue = new PriorityQueue<>(BY_DUE);
    /** The ticker's task while it has one; guarded by {@code this}. */
    private TaskHandle task;
    /** Ticks counted by the ticker's runs; guarded by {@code this}. */
    private long now;
    private long seq;
    private boolean running;
    /** How many times the ticker's task was started; for tests. */
    private int starts;

    SharedTicker(Logger logger, String owner, Function<Runnable, TaskHandle> starter) {
        this.logger = logger;
        this.owner = owner;
        this.starter = starter;
    }

    /** Adds {@code job} every {@code period} ticks (at least one); throws when the ticker cannot be started. */
    synchronized TaskHandle add(long period, Runnable job) {
        long every = Math.max(1L, period);
        if (task == null || task.isCancelled()) {
            dropAll();
            task = starter.apply(this::tick);
            starts++;
        }
        Job added = new Job(this, job, every, now + every, seq++, task);
        queue.add(added);
        return added;
    }

    /** Cancels every job and the ticker's task (the plugin is going away). */
    synchronized void cancelAll() {
        dropAll();
        stop();
    }

    /** Live jobs; for tests. */
    synchronized int size() {
        return queue.size();
    }

    /** True while the ticker's task is scheduled; for tests. */
    synchronized boolean ticking() {
        return task != null && !task.isCancelled();
    }

    /** How many times the ticker's task was started; for tests. */
    synchronized int starts() {
        return starts;
    }

    /** One server tick: runs the jobs due now, in order, then schedules them again. */
    void tick() {
        List<Job> due;
        long tick;
        synchronized (this) {
            tick = ++now;
            Job first = queue.peek();
            if (first == null || first.due > tick) {
                return;
            }
            due = new ArrayList<>();
            while (!queue.isEmpty() && queue.peek().due <= tick) {
                due.add(queue.poll());
            }
            running = true;
        }
        try {
            for (Job job : due) {
                if (!job.cancelled) {
                    job.run();
                }
            }
        } finally {
            synchronized (this) {
                running = false;
                for (Job job : due) {
                    if (!job.cancelled) {
                        job.due = tick + job.period;
                        queue.add(job);
                    }
                }
                if (queue.isEmpty()) {
                    stop();
                }
            }
        }
    }

    private synchronized void cancel(Job job) {
        job.cancelled = true;
        if (queue.remove(job) && queue.isEmpty() && !running) {
            stop();
        }
    }

    /** Caller holds the lock. */
    private void dropAll() {
        for (Job job : queue) {
            job.cancelled = true;
        }
        queue.clear();
    }

    /** Caller holds the lock. */
    private void stop() {
        TaskHandle current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }

    /** One repeating job and its handle. */
    private static final class Job implements TaskHandle {

        private final SharedTicker ticker;
        private final Runnable body;
        private final long period;
        private final long seq;
        /** The ticker task this job belongs to: cancelled from outside, the job is gone too. */
        private final TaskHandle ticket;
        /** Guarded by the ticker. */
        private long due;
        private volatile boolean cancelled;

        Job(SharedTicker ticker, Runnable body, long period, long due, long seq, TaskHandle ticket) {
            this.ticker = ticker;
            this.body = body;
            this.period = period;
            this.due = due;
            this.seq = seq;
            this.ticket = ticket;
        }

        void run() {
            try {
                body.run();
            } catch (Throwable error) {
                ticker.cancel(this);
                ticker.logger.log(Level.SEVERE, "A repeating task of " + ticker.owner + " threw "
                        + RsScheduler.describe(error) + "; it was cancelled and will not run again.", error);
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                ticker.cancel(this);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled || ticket.isCancelled();
        }
    }
}
