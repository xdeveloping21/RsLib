package com.rs.lib.yml;

import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.rs.lib.Rs;
import com.rs.lib.debug.RsDebug;

/**
 * What a YAML file needs from its plugin's context. Lets the file logic run in tests without a
 * server.
 */
interface YmlHost {

    Logger logger();

    boolean isPrimaryThread();

    /** Resolves PlaceholderAPI tokens; only called on the main thread. */
    String papi(Player viewer, String text);

    /** Runs {@code task} off the main thread; throws when it cannot be scheduled. */
    void async(Runnable task);

    boolean shuttingDown();

    /** A trace line for the context's debug output, when there is one. */
    void trace(Supplier<String> message);

    static YmlHost of(Rs rs) {
        return new YmlHost() {
            @Override
            public Logger logger() {
                return rs.plugin().getLogger();
            }

            @Override
            public boolean isPrimaryThread() {
                return Bukkit.isPrimaryThread();
            }

            @Override
            public String papi(Player viewer, String text) {
                return rs.papi().apply(viewer, text);
            }

            @Override
            public void async(Runnable task) {
                rs.scheduler().async(task);
            }

            @Override
            public boolean shuttingDown() {
                return rs.isShuttingDown();
            }

            @Override
            public void trace(Supplier<String> message) {
                RsDebug debug = rs.debugOrNull();
                if (debug != null) {
                    debug.trace("yml", message);
                }
            }
        };
    }
}
