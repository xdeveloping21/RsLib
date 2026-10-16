package com.rs.lib.input;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.scheduler.TaskHandle;

/** An input host without a server: records messages, queues work and keeps timers for the test to fire. */
final class FakeInputHost implements InputHost {

    final List<String> sent = new ArrayList<>();
    /** The placeholders of each message in {@link #sent}, by position (none for {@code sendText}). */
    final List<Ph[]> sentPhs = new ArrayList<>();
    final List<String> severe = new ArrayList<>();
    final Deque<Runnable> queue = new ArrayDeque<>();
    final List<Timer> timers = new ArrayList<>();
    boolean owns = true;
    boolean shuttingDown;
    boolean refuseTimers;
    private final Logger logger = Logger.getAnonymousLogger();

    FakeInputHost() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                severe.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /** Runs everything queued, as the next tick would. */
    void tick() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    /** Fires the last timer that is not cancelled. */
    void fireTimer() {
        for (int i = timers.size() - 1; i >= 0; i--) {
            Timer timer = timers.get(i);
            if (!timer.cancelled) {
                timer.cancelled = true;
                timer.task.run();
                return;
            }
        }
        throw new AssertionError("no live timer");
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public boolean ownsHere(Player player) {
        return owns;
    }

    @Override
    public void runFor(Player player, Runnable task) {
        queue.add(task);
    }

    @Override
    public TaskHandle later(Player player, long ticks, Runnable task) {
        if (refuseTimers) {
            throw new IllegalStateException("plugin disabled");
        }
        Timer timer = new Timer(ticks, task);
        timers.add(timer);
        return timer;
    }

    @Override
    public boolean shuttingDown() {
        return shuttingDown;
    }

    @Override
    public void send(Player player, String key, Ph... phs) {
        StringBuilder line = new StringBuilder(key);
        for (Ph ph : phs) {
            line.append(' ').append(ph.key()).append('=').append(ph.value());
        }
        sent.add(line.toString());
        sentPhs.add(phs.clone());
    }

    @Override
    public void sendText(Player player, String text) {
        sent.add("text:" + text);
        sentPhs.add(new Ph[0]);
    }

    /** A timer the test fires by hand. */
    static final class Timer implements TaskHandle {
        final long ticks;
        final Runnable task;
        boolean cancelled;

        Timer(long ticks, Runnable task) {
            this.ticks = ticks;
            this.task = task;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** A player that only has an id, a name and an online flag ({@code online[0]}). */
    static Player player(boolean[] online) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(FakeInputHost.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "Alex";
                    case "isOnline" -> online[0];
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Alex";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
