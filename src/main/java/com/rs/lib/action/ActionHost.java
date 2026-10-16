package com.rs.lib.action;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Rs;
import com.rs.lib.compat.RsVersion;
import com.rs.lib.scheduler.TaskHandle;

/**
 * What {@link ActionEngine} needs from the server and the plugin's context. Lets the engine's
 * logic run in tests without either.
 */
interface ActionHost {

    Logger logger();

    /** The plugin, or null in tests. */
    JavaPlugin plugin();

    /** The context handed to {@link ActionContext}, or null in tests. */
    Rs rs();

    /** True when the calling thread may run actions for {@code player} (null: no player) now. */
    boolean ownsHere(Player player);

    /** Runs {@code task} on the thread that owns {@code player}; throws when it cannot be scheduled. */
    void runFor(Player player, Runnable task);

    /**
     * Runs {@code task} on the thread that owns {@code player} (null: the main thread) after
     * {@code ticks} (at least one); throws when it cannot be scheduled.
     */
    TaskHandle later(Player player, long ticks, Runnable task);

    /** Runs {@code callback} when a player leaves the server. */
    void onQuit(Consumer<Player> callback);

    /** Runs {@code command} as the console, on the thread the server wants it on. */
    void console(String command);

    /** PlaceholderAPI for {@code player}; the text unchanged when it cannot resolve here. */
    String papi(Player player, String text);

    boolean shuttingDown();

    /** A note for the plugin's debug output. */
    void debug(Supplier<String> message);

    static ActionHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements ActionHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public JavaPlugin plugin() {
            return rs.plugin();
        }

        @Override
        public boolean ownsHere(Player player) {
            return player == null ? Bukkit.isPrimaryThread() : rs.scheduler().ownsHere(player);
        }

        @Override
        public void runFor(Player player, Runnable task) {
            if (player == null) {
                rs.scheduler().sync(task);
            } else {
                rs.scheduler().entity(player, task);
            }
        }

        @Override
        public TaskHandle later(Player player, long ticks, Runnable task) {
            return player == null ? rs.scheduler().syncLater(ticks, task) : rs.scheduler().entityLater(player, ticks, task);
        }

        @Override
        public void onQuit(Consumer<Player> callback) {
            rs.onQuit(callback);
        }

        @Override
        public void console(String command) {
            if (RsVersion.isFolia()) {
                // Folia runs console commands on the global region only.
                rs.scheduler().sync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
        }

        @Override
        public String papi(Player player, String text) {
            return rs.papi().apply(player, text);
        }

        @Override
        public boolean shuttingDown() {
            return rs.isShuttingDown();
        }

        @Override
        public void debug(Supplier<String> message) {
            rs.debug().log(message);
        }
    }
}
