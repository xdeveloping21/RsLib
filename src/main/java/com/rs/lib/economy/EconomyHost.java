package com.rs.lib.economy;

import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.rs.lib.Rs;

/** What the economy needs from the server and the plugin; lets its logic run in tests. */
interface EconomyHost {

    Logger logger();

    /** Identity of the plugin, the key of its pending main-thread hops. */
    Object owner();

    boolean isPrimaryThread();

    /** Runs {@code task} on the main thread (global region on Folia); throws when it cannot. */
    void sync(Runnable task);

    void debug(Supplier<String> message);

    /** Runs {@code command} as the console; true when a command ran. */
    boolean dispatchConsole(String command);

    /** {@code text} with PlaceholderAPI's placeholders resolved for {@code player}, or null without PlaceholderAPI. */
    String placeholders(Player player, String text);

    static EconomyHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements EconomyHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public Object owner() {
            return rs.plugin();
        }

        @Override
        public boolean isPrimaryThread() {
            return Bukkit.isPrimaryThread();
        }

        @Override
        public void sync(Runnable task) {
            rs.scheduler().sync(task);
        }

        @Override
        public void debug(Supplier<String> message) {
            rs.debug().log(message);
        }

        @Override
        public boolean dispatchConsole(String command) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }

        @Override
        public String placeholders(Player player, String text) {
            return rs.papi().available() ? rs.papi().applyHere(player, text) : null;
        }
    }
}
