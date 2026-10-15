package com.rs.lib.velocity;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;

/**
 * Shortcuts over the Velocity scheduler for one plugin. Every task runs on Velocity's task
 * threads (the proxy has no main thread); cancel it with {@link ScheduledTask#cancel()}.
 * Durations are rounded down to milliseconds.
 */
public final class RsvScheduler {

    private final Object plugin;
    private final Scheduler scheduler;

    RsvScheduler(Object plugin, Scheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /** Runs {@code task} as soon as possible. */
    public ScheduledTask run(Runnable task) {
        return scheduler.buildTask(plugin, Objects.requireNonNull(task, "task")).schedule();
    }

    /** Runs {@code task} once after {@code delay}. */
    public ScheduledTask later(Runnable task, Duration delay) {
        return scheduler.buildTask(plugin, Objects.requireNonNull(task, "task"))
                .delay(millis(delay), TimeUnit.MILLISECONDS)
                .schedule();
    }

    /** Runs {@code task} now and then every {@code interval}. */
    public ScheduledTask repeat(Runnable task, Duration interval) {
        return scheduler.buildTask(plugin, Objects.requireNonNull(task, "task"))
                .repeat(millis(interval), TimeUnit.MILLISECONDS)
                .schedule();
    }

    /** Runs {@code task} after {@code delay} and then every {@code interval}. */
    public ScheduledTask repeat(Runnable task, Duration delay, Duration interval) {
        return scheduler.buildTask(plugin, Objects.requireNonNull(task, "task"))
                .delay(millis(delay), TimeUnit.MILLISECONDS)
                .repeat(millis(interval), TimeUnit.MILLISECONDS)
                .schedule();
    }

    private static long millis(Duration duration) {
        return Objects.requireNonNull(duration, "duration").toMillis();
    }
}
