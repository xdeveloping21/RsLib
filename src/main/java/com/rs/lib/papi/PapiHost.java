package com.rs.lib.papi;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Rs;
import com.rs.lib.RsLib;
import com.rs.lib.debug.RsDebug;
import com.rs.lib.scheduler.RsScheduler;

/**
 * What {@link RsPapi} needs from the server and from PlaceholderAPI. Lets its logic run in tests
 * without either. Only {@link Server} calls {@code PapiBridge}, and only after
 * {@link #papiEnabled()} said yes.
 */
interface PapiHost {

    Logger logger();

    boolean isPrimaryThread();

    /** True when PlaceholderAPI is installed and enabled right now; the lookup may be cached. */
    boolean papiEnabled();

    /** Forgets the cached lookup (PlaceholderAPI was enabled or disabled). */
    void forget();

    String setPlaceholders(Player viewer, String text);

    /** Registers {@code holder} with PlaceholderAPI; returns its handle, or null when refused. */
    Object register(PapiHolder holder);

    /** True while the expansion behind {@code handle} is the one PlaceholderAPI has for its identifier. */
    boolean isRegistered(Object handle);

    /** Unregisters the expansion behind {@code handle} if it is still the registered one. */
    void unregister(Object handle);

    /** Runs {@code task} on the main thread; throws when it cannot be scheduled. */
    void sync(Runnable task);

    /** A trace line for the context's debug output, when there is one. */
    void trace(Supplier<String> message);

    String defaultAuthor();

    String defaultVersion();

    static PapiHost of(JavaPlugin plugin) {
        return new Server(plugin);
    }

    /** The real server. */
    final class Server implements PapiHost {

        /** A cached lookup; {@code plugin} is null when PlaceholderAPI is not installed. */
        private record Lookup(Plugin plugin) {
        }

        private static final Lookup MISSING = new Lookup(null);

        private final JavaPlugin plugin;
        private final RsScheduler scheduler;
        private volatile Lookup lookup;

        Server(JavaPlugin plugin) {
            this.plugin = plugin;
            this.scheduler = new RsScheduler(plugin);
        }

        @Override
        public Logger logger() {
            return plugin.getLogger();
        }

        @Override
        public boolean isPrimaryThread() {
            return Bukkit.isPrimaryThread();
        }

        /**
         * The plugin object is kept, not the answer: a PlaceholderAPI that is disabled reads as
         * missing at once, even before the event that clears the cache.
         */
        @Override
        public boolean papiEnabled() {
            Lookup known = lookup;
            if (known == null) {
                Plugin found = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
                known = found == null ? MISSING : new Lookup(found);
                lookup = known;
            }
            return known.plugin != null && known.plugin.isEnabled();
        }

        @Override
        public void forget() {
            lookup = null;
        }

        @Override
        public String setPlaceholders(Player viewer, String text) {
            return PapiBridge.set(viewer, text);
        }

        @Override
        public Object register(PapiHolder holder) {
            return PapiBridge.register(holder);
        }

        @Override
        public boolean isRegistered(Object handle) {
            return PapiBridge.isRegistered(handle);
        }

        @Override
        public void unregister(Object handle) {
            PapiBridge.unregister(handle);
        }

        @Override
        public void sync(Runnable task) {
            scheduler.sync(task);
        }

        @Override
        public void trace(Supplier<String> message) {
            Rs rs = RsLib.context(plugin);
            RsDebug debug = rs == null ? null : rs.debugOrNull();
            if (debug != null) {
                debug.trace("papi", message);
            }
        }

        @Override
        public String defaultAuthor() {
            List<String> authors = plugin.getPluginMeta().getAuthors();
            return authors.isEmpty() ? plugin.getName() : String.join(", ", authors);
        }

        @Override
        public String defaultVersion() {
            return plugin.getPluginMeta().getVersion();
        }
    }
}
