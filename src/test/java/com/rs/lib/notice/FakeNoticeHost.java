package com.rs.lib.notice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.rs.lib.Ph;
import com.rs.lib.db.RsDb;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.yml.YmlTestSupport.Captured;

/** A server for {@link Notices}: players are ids, messages are recorded, tasks wait for the test. */
final class FakeNoticeHost implements NoticeHost {

    record Shown(UUID player, String key, List<Ph> phs) {
    }

    record Later(long ticks, Runnable task) {
    }

    record PlayerTask(UUID player, Runnable task, Runnable retired) {
    }

    final Captured log = new Captured();
    final AtomicLong now = new AtomicLong(1_000_000_000L);
    final Set<UUID> online = ConcurrentHashMap.newKeySet();
    final List<Shown> shown = new ArrayList<>();
    final List<Later> later = new ArrayList<>();
    final List<Long> timers = new ArrayList<>();
    final List<PlayerTask> playerTasks = new ArrayList<>();
    final List<Consumer<UUID>> joinListeners = new ArrayList<>();
    volatile NoticeSettings settings = NoticeSettings.DEFAULTS;
    volatile boolean holdPlayerTasks;
    volatile boolean refuseScheduling;
    volatile boolean stopped;
    RsDb db;

    @Override
    public Logger logger() {
        return log.logger;
    }

    @Override
    public long now() {
        return now.get();
    }

    @Override
    public NoticeSettings settings() {
        return settings;
    }

    @Override
    public RsDb db() {
        return db;
    }

    @Override
    public boolean isOnline(UUID player) {
        return online.contains(player);
    }

    @Override
    public boolean runFor(UUID player, Runnable task, Runnable retired) {
        if (!online.contains(player) || refuseScheduling) {
            return false;
        }
        if (holdPlayerTasks) {
            synchronized (playerTasks) {
                playerTasks.add(new PlayerTask(player, task, retired));
            }
        } else {
            task.run();
        }
        return true;
    }

    @Override
    public TaskHandle later(long ticks, Runnable task) {
        if (refuseScheduling) {
            throw new IllegalStateException("disabled");
        }
        synchronized (later) {
            later.add(new Later(ticks, task));
        }
        return handle();
    }

    @Override
    public TaskHandle every(long delayTicks, long periodTicks, Runnable task) {
        timers.add(periodTicks);
        return handle();
    }

    @Override
    public void listenJoins(Consumer<UUID> joined) {
        joinListeners.add(joined);
    }

    @Override
    public Collection<UUID> online() {
        return new ArrayList<>(online);
    }

    @Override
    public void show(UUID player, String key, Ph... phs) {
        synchronized (shown) {
            shown.add(new Shown(player, key, List.of(phs)));
        }
    }

    @Override
    public void stop() {
        stopped = true;
    }

    List<Shown> shown() {
        synchronized (shown) {
            return new ArrayList<>(shown);
        }
    }

    /** Runs the delayed tasks queued so far; returns how many. */
    int runLater() {
        List<Later> due;
        synchronized (later) {
            due = new ArrayList<>(later);
            later.clear();
        }
        due.forEach(each -> each.task().run());
        return due.size();
    }

    /** Waits until {@code count} player tasks are held; fails after five seconds. */
    List<PlayerTask> awaitPlayerTasks(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            synchronized (playerTasks) {
                if (playerTasks.size() >= count) {
                    List<PlayerTask> held = new ArrayList<>(playerTasks);
                    playerTasks.clear();
                    return held;
                }
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for a player task");
            }
            Thread.onSpinWait();
        }
    }

    private static TaskHandle handle() {
        return new TaskHandle() {
            private boolean cancelled;

            @Override
            public void cancel() {
                cancelled = true;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        };
    }
}
