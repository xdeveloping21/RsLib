package com.rs.lib.db;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Rs;

/**
 * What the database module needs from its plugin's context. Lets futures, caches and the
 * executor run in tests without a server.
 */
interface DbHost {

    /** The plugin name, for thread names, logs and the migration history. */
    String name();

    Logger logger();

    /** Set as context class loader of a database thread while it works. */
    ClassLoader classLoader();

    /** Identity of the plugin, the key of its pending main-thread hops. */
    Object owner();

    boolean isPrimaryThread();

    boolean isEnabled();

    boolean shuttingDown();

    /** True while the plugin's database runs startup work that the main thread may wait for. */
    boolean bootstrapping();

    /** Runs {@code task} on the main thread (global region on Folia); throws when it cannot. */
    void sync(Runnable task);

    /** Runs {@code task} on the plugin's async pool; throws when it cannot. */
    void async(Runnable task);

    void disablePlugin();

    /** Calls {@code callback} with the id of every player who leaves. */
    void onQuit(Consumer<UUID> callback);

    static DbHost of(Rs rs) {
        return new RsHost(rs);
    }

    /** The real host: Bukkit, through the context's scheduler. */
    record RsHost(Rs rs) implements DbHost {

        @Override
        public String name() {
            return rs.plugin().getName();
        }

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public ClassLoader classLoader() {
            return rs.plugin().getClass().getClassLoader();
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
        public boolean isEnabled() {
            return rs.plugin().isEnabled();
        }

        @Override
        public boolean shuttingDown() {
            return rs.isShuttingDown();
        }

        @Override
        public boolean bootstrapping() {
            RsDb db = rs.dbOrNull();
            return db != null && db.bootstrapping();
        }

        @Override
        public void sync(Runnable task) {
            rs.scheduler().sync(task);
        }

        @Override
        public void async(Runnable task) {
            rs.scheduler().async(task);
        }

        @Override
        public void disablePlugin() {
            JavaPlugin plugin = rs.plugin();
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        }

        @Override
        public void onQuit(Consumer<UUID> callback) {
            rs.onQuit(player -> callback.accept(player.getUniqueId()));
        }
    }
}
