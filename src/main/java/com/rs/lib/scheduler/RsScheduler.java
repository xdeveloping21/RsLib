package com.rs.lib.scheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.rs.lib.compat.RsVersion;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Sync and async tasks for one plugin, on Paper and on Folia.
 *
 * <p>On Paper the classic scheduler is used. On Folia "sync" means the global region and
 * "async" the async scheduler (ticks are converted to milliseconds). Every delay and period
 * is at least one tick, the same on both, so passing 0 means "the next tick". Like Bukkit,
 * scheduling for a plugin that is already disabled throws
 * {@link org.bukkit.plugin.IllegalPluginAccessException}.</p>
 */
public final class RsScheduler {

    private static final long MILLIS_PER_TICK = 50L;

    private final JavaPlugin plugin;
    private final boolean folia;

    public RsScheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.folia = RsVersion.isFolia();
    }

    /** Runs on the main thread (global region on Folia) on the next tick. */
    public TaskHandle sync(Runnable task) {
        if (folia) {
            return wrap(Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run()));
        }
        return wrap(Bukkit.getScheduler().runTask(plugin, task));
    }

    /** Runs off the main thread. */
    public TaskHandle async(Runnable task) {
        if (folia) {
            return wrap(Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run()));
        }
        return wrap(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
    }

    public TaskHandle syncLater(long delayTicks, Runnable task) {
        long delay = atLeastOne(delayTicks);
        if (folia) {
            return wrap(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delay));
        }
        return wrap(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
    }

    public TaskHandle asyncLater(long delayTicks, Runnable task) {
        long delay = atLeastOne(delayTicks);
        if (folia) {
            return wrap(Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(),
                    delay * MILLIS_PER_TICK, TimeUnit.MILLISECONDS));
        }
        return wrap(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay));
    }

    public TaskHandle timer(long delayTicks, long periodTicks, Runnable task) {
        long delay = atLeastOne(delayTicks);
        long period = atLeastOne(periodTicks);
        if (folia) {
            return wrap(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), delay, period));
        }
        return wrap(Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period));
    }

    public TaskHandle timerAsync(long delayTicks, long periodTicks, Runnable task) {
        long delay = atLeastOne(delayTicks);
        long period = atLeastOne(periodTicks);
        if (folia) {
            return wrap(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(),
                    delay * MILLIS_PER_TICK, period * MILLIS_PER_TICK, TimeUnit.MILLISECONDS));
        }
        return wrap(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period));
    }

    /**
     * Computes a value off the main thread. The future completes exceptionally when the
     * supplier throws or the plugin is already disabled; this method itself never throws.
     */
    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            async(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RuntimeException notScheduled) {
            future.completeExceptionally(notScheduled);
        }
        return future;
    }

    /**
     * Hands the result of {@code future} to {@code consumer} on the main thread. A failed
     * future is logged once as a warning and the consumer is not called; if the plugin is
     * disabled by then, nothing happens.
     */
    public <T> void thenSync(CompletableFuture<T> future, Consumer<T> consumer) {
        future.whenComplete((value, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "An async task failed: " + describe(error), error);
                return;
            }
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                sync(() -> consumer.accept(value));
            } catch (RuntimeException disabledMeanwhile) {
                plugin.getLogger().warning("A result arrived while " + plugin.getName()
                        + " was being disabled; it was dropped.");
            }
        });
    }

    /** Cancels every task of this plugin, on both schedulers on Folia. */
    public void cancelAll() {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private static long atLeastOne(long ticks) {
        return Math.max(1L, ticks);
    }

    private static String describe(Throwable t) {
        Throwable root = t;
        while ((root instanceof java.util.concurrent.CompletionException
                || root instanceof java.util.concurrent.ExecutionException) && root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static TaskHandle wrap(BukkitTask task) {
        return new TaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }

    private static TaskHandle wrap(ScheduledTask task) {
        return new TaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }
}
