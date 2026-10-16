package com.rs.lib.action;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Rs;
import com.rs.lib.scheduler.TaskHandle;

/** An action host without a server: records warnings, PlaceholderAPI calls and scheduled work. */
final class FakeActionHost implements ActionHost {

    final List<String> warnings = new ArrayList<>();
    final List<String> debug = new ArrayList<>();
    final List<String> papiCalls = new ArrayList<>();
    final List<String> console = new ArrayList<>();
    final Deque<Runnable> queue = new ArrayDeque<>();
    final List<Later> delayed = new ArrayList<>();
    final List<Consumer<Player>> quitCallbacks = new ArrayList<>();
    boolean owns = true;
    boolean shuttingDown;
    boolean refuseScheduling;
    UnaryOperator<String> papi = UnaryOperator.identity();
    private final Logger logger = Logger.getAnonymousLogger();

    FakeActionHost() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    /** Runs everything scheduled so far, as the next tick would. */
    void tick() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public JavaPlugin plugin() {
        return null;
    }

    @Override
    public Rs rs() {
        return null;
    }

    @Override
    public boolean ownsHere(Player player) {
        return owns;
    }

    @Override
    public void runFor(Player player, Runnable task) {
        if (refuseScheduling) {
            throw new IllegalStateException("plugin disabled");
        }
        queue.add(task);
    }

    @Override
    public TaskHandle later(Player player, long ticks, Runnable task) {
        if (refuseScheduling) {
            throw new IllegalStateException("plugin disabled");
        }
        Later later = new Later(player, ticks, task);
        delayed.add(later);
        return later;
    }

    @Override
    public void onQuit(Consumer<Player> callback) {
        quitCallbacks.add(callback);
    }

    /** The player leaves: the quit callbacks run, as RsLib's listener would. */
    void quit(Player player) {
        quitCallbacks.forEach(callback -> callback.accept(player));
    }

    /** Runs, in order, the delayed tasks that were not cancelled; returns how many ran. */
    int runDelayed() {
        int ran = 0;
        while (!delayed.isEmpty()) {
            Later later = delayed.remove(0);
            if (!later.cancelled) {
                later.task.run();
                ran++;
            }
        }
        return ran;
    }

    /** A task scheduled with {@link #later}. */
    static final class Later implements TaskHandle {
        final Player player;
        final long ticks;
        final Runnable task;
        boolean cancelled;

        Later(Player player, long ticks, Runnable task) {
            this.player = player;
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

    @Override
    public void console(String command) {
        console.add(command);
    }

    @Override
    public String papi(Player player, String text) {
        papiCalls.add(text);
        return papi.apply(text);
    }

    @Override
    public boolean shuttingDown() {
        return shuttingDown;
    }

    @Override
    public void debug(Supplier<String> message) {
        debug.add(message.get());
    }
}
