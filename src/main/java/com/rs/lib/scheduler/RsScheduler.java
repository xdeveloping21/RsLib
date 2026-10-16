package com.rs.lib.scheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.rs.lib.compat.RsVersion;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Sync and async tasks for one plugin, on Paper and on Folia.
 *
 * <p>On Paper the classic scheduler is used. On Folia "sync" means the global region and
 * "async" the async scheduler (ticks are converted to milliseconds). Every delay and period
 * is at least one tick, the same on both, so passing 0 means "the next tick". Work on one
 * player or entity goes through {@link #entity} and {@link #ownsHere}: the main thread on Paper,
 * the entity's region on Folia; work on a place (spawning, a block) through {@link #at(Location, Runnable)}.
 * Like Bukkit,
 * scheduling for a plugin that is already disabled throws
 * {@link org.bukkit.plugin.IllegalPluginAccessException}.</p>
 */
public final class RsScheduler {

    private static final long MILLIS_PER_TICK = 50L;

    private final JavaPlugin plugin;
    private final Backend backend;

    public RsScheduler(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.backend = RsVersion.isFolia() ? new FoliaBackend(plugin) : new PaperBackend(plugin);
    }

    /** Runs on the main thread (global region on Folia) on the next tick. */
    public TaskHandle sync(Runnable task) {
        return backend.sync(task);
    }

    /** Runs off the main thread. */
    public TaskHandle async(Runnable task) {
        return backend.async(task);
    }

    public TaskHandle syncLater(long delayTicks, Runnable task) {
        return backend.syncLater(atLeastOne(delayTicks), task);
    }

    public TaskHandle asyncLater(long delayTicks, Runnable task) {
        return backend.asyncLater(atLeastOne(delayTicks), task);
    }

    public TaskHandle timer(long delayTicks, long periodTicks, Runnable task) {
        return backend.timer(atLeastOne(delayTicks), atLeastOne(periodTicks), task);
    }

    public TaskHandle timerAsync(long delayTicks, long periodTicks, Runnable task) {
        return backend.timerAsync(atLeastOne(delayTicks), atLeastOne(periodTicks), task);
    }

    /**
     * True when the calling thread may touch {@code entity} now: the main thread on Paper, the
     * thread that owns the entity's region on Folia.
     */
    public boolean ownsHere(Entity entity) {
        return backend.ownsHere(entity);
    }

    /**
     * Runs on the thread that owns {@code entity}: the main thread on the next tick on Paper,
     * the entity's own scheduler on Folia. On Folia a task for an entity that is already gone
     * (a player who left) never runs, and the handle comes back cancelled.
     */
    public TaskHandle entity(Entity entity, Runnable task) {
        return backend.entity(Objects.requireNonNull(entity, "entity"), task);
    }

    /**
     * Same as {@link #entity(Entity, Runnable)}, and {@code retired} runs instead of
     * {@code task} when the task can never run: on Folia when the entity is removed first (or,
     * on the calling thread, when it is already gone now). On Paper the task always runs, so it
     * must check that the entity is still there.
     */
    public TaskHandle entity(Entity entity, Runnable task, Runnable retired) {
        return backend.entity(Objects.requireNonNull(entity, "entity"), task, Objects.requireNonNull(retired, "retired"));
    }

    /** Same as {@link #entity} after {@code delayTicks} (at least one). */
    public TaskHandle entityLater(Entity entity, long delayTicks, Runnable task) {
        return backend.entityLater(Objects.requireNonNull(entity, "entity"), atLeastOne(delayTicks), task);
    }

    /**
     * Repeats {@code task} on the thread that owns {@code entity}, first after {@code delayTicks}
     * then every {@code periodTicks} (both at least one): the main thread on Paper, the entity's
     * own scheduler on Folia, where the task stops by itself once the entity is gone.
     */
    public TaskHandle entityTimer(Entity entity, long delayTicks, long periodTicks, Runnable task) {
        return backend.entityTimer(Objects.requireNonNull(entity, "entity"), atLeastOne(delayTicks),
                atLeastOne(periodTicks), task);
    }

    /**
     * True when the calling thread may touch the chunk at {@code location} now: the main thread
     * on Paper, the thread that owns that chunk's region on Folia.
     */
    public boolean ownsHere(Location location) {
        return backend.ownsHere(Objects.requireNonNull(location, "location"));
    }

    /**
     * Runs on the thread that owns the chunk at {@code location} (it needs a world): the main
     * thread on the next tick on Paper, that chunk's region on Folia.
     */
    public TaskHandle at(Location location, Runnable task) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location has no world");
        return backend.at(location, task);
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
                plugin.getLogger().log(Level.WARNING, "Async task finished with an error: " + describe(error), error);
                return;
            }
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                sync(() -> consumer.accept(value));
            } catch (RuntimeException disabledMeanwhile) {
                plugin.getLogger().warning("Hop to main discarded: plugin disabled during scheduling");
            }
        });
    }

    /** Cancels every task of this plugin, on both schedulers on Folia. */
    public void cancelAll() {
        backend.cancelAll();
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

    /** One handle shape for both schedulers' task types. */
    private record Handle(Runnable canceller, BooleanSupplier cancelled) implements TaskHandle {

        /** A task that was never scheduled. */
        static final Handle NONE = new Handle(() -> { }, () -> true);

        @Override
        public void cancel() {
            canceller.run();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.getAsBoolean();
        }
    }

    /** Where tasks go; delays and periods arrive in ticks, already at least one. */
    private interface Backend {

        TaskHandle sync(Runnable task);

        TaskHandle async(Runnable task);

        TaskHandle syncLater(long delay, Runnable task);

        TaskHandle asyncLater(long delay, Runnable task);

        TaskHandle timer(long delay, long period, Runnable task);

        TaskHandle timerAsync(long delay, long period, Runnable task);

        boolean ownsHere(Entity entity);

        TaskHandle entity(Entity entity, Runnable task);

        TaskHandle entity(Entity entity, Runnable task, Runnable retired);

        TaskHandle entityLater(Entity entity, long delay, Runnable task);

        TaskHandle entityTimer(Entity entity, long delay, long period, Runnable task);

        boolean ownsHere(Location location);

        TaskHandle at(Location location, Runnable task);

        void cancelAll();
    }

    /** The classic Bukkit scheduler. */
    private record PaperBackend(JavaPlugin plugin) implements Backend {

        private static TaskHandle handle(BukkitTask task) {
            return new Handle(task::cancel, task::isCancelled);
        }

        @Override
        public TaskHandle sync(Runnable task) {
            return handle(Bukkit.getScheduler().runTask(plugin, task));
        }

        @Override
        public TaskHandle async(Runnable task) {
            return handle(Bukkit.getScheduler().runTaskAsynchronously(plugin, task));
        }

        @Override
        public TaskHandle syncLater(long delay, Runnable task) {
            return handle(Bukkit.getScheduler().runTaskLater(plugin, task, delay));
        }

        @Override
        public TaskHandle asyncLater(long delay, Runnable task) {
            return handle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay));
        }

        @Override
        public TaskHandle timer(long delay, long period, Runnable task) {
            return handle(Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period));
        }

        @Override
        public TaskHandle timerAsync(long delay, long period, Runnable task) {
            return handle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period));
        }

        @Override
        public boolean ownsHere(Entity entity) {
            return Bukkit.isPrimaryThread();
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task) {
            return sync(task);
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task, Runnable retired) {
            return sync(task);
        }

        @Override
        public TaskHandle entityLater(Entity entity, long delay, Runnable task) {
            return syncLater(delay, task);
        }

        @Override
        public TaskHandle entityTimer(Entity entity, long delay, long period, Runnable task) {
            return timer(delay, period, task);
        }

        @Override
        public boolean ownsHere(Location location) {
            return Bukkit.isPrimaryThread();
        }

        @Override
        public TaskHandle at(Location location, Runnable task) {
            return sync(task);
        }

        @Override
        public void cancelAll() {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    /** Folia: "sync" is the global region, "async" the async scheduler (ticks become milliseconds). */
    private record FoliaBackend(JavaPlugin plugin) implements Backend {

        private static TaskHandle handle(ScheduledTask task) {
            return new Handle(task::cancel, task::isCancelled);
        }

        @Override
        public TaskHandle sync(Runnable task) {
            return handle(Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run()));
        }

        @Override
        public TaskHandle async(Runnable task) {
            return handle(Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run()));
        }

        @Override
        public TaskHandle syncLater(long delay, Runnable task) {
            return handle(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delay));
        }

        @Override
        public TaskHandle asyncLater(long delay, Runnable task) {
            return handle(Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(),
                    delay * MILLIS_PER_TICK, TimeUnit.MILLISECONDS));
        }

        @Override
        public TaskHandle timer(long delay, long period, Runnable task) {
            return handle(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), delay, period));
        }

        @Override
        public TaskHandle timerAsync(long delay, long period, Runnable task) {
            return handle(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(),
                    delay * MILLIS_PER_TICK, period * MILLIS_PER_TICK, TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean ownsHere(Entity entity) {
            return Bukkit.isOwnedByCurrentRegion(entity);
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task) {
            ScheduledTask scheduled = entity.getScheduler().run(plugin, t -> task.run(), null);
            return scheduled == null ? Handle.NONE : handle(scheduled);
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task, Runnable retired) {
            ScheduledTask scheduled = entity.getScheduler().run(plugin, t -> task.run(), retired);
            if (scheduled == null) {
                retired.run();
                return Handle.NONE;
            }
            return handle(scheduled);
        }

        @Override
        public TaskHandle entityLater(Entity entity, long delay, Runnable task) {
            ScheduledTask scheduled = entity.getScheduler().runDelayed(plugin, t -> task.run(), null, delay);
            return scheduled == null ? Handle.NONE : handle(scheduled);
        }

        @Override
        public TaskHandle entityTimer(Entity entity, long delay, long period, Runnable task) {
            ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(plugin, t -> task.run(), null, delay, period);
            return scheduled == null ? Handle.NONE : handle(scheduled);
        }

        @Override
        public boolean ownsHere(Location location) {
            return Bukkit.isOwnedByCurrentRegion(location);
        }

        @Override
        public TaskHandle at(Location location, Runnable task) {
            return handle(Bukkit.getRegionScheduler().run(plugin, location, t -> task.run()));
        }

        @Override
        public void cancelAll() {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        }
    }
}
