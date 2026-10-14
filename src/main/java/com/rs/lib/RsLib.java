package com.rs.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** The contexts of every plugin built on RsLib, in the order they were enabled. */
public final class RsLib {

    private static final Map<Plugin, Rs> CONTEXTS = new LinkedHashMap<>();

    private RsLib() {
    }

    /** The context of {@code plugin}, or null if it does not use RsLib or is not enabled. */
    public static Rs context(Plugin plugin) {
        synchronized (CONTEXTS) {
            return CONTEXTS.get(plugin);
        }
    }

    /** A snapshot of every live context, oldest first. */
    public static List<Rs> contexts() {
        synchronized (CONTEXTS) {
            return new ArrayList<>(CONTEXTS.values());
        }
    }

    static Rs init(JavaPlugin plugin, RsSpec spec) {
        synchronized (CONTEXTS) {
            Rs existing = CONTEXTS.get(plugin);
            if (existing != null) {
                plugin.getLogger().warning("RsLib context created twice; keeping the first one.");
                return existing;
            }
        }
        Rs rs = new Rs(plugin, spec);
        synchronized (CONTEXTS) {
            CONTEXTS.put(plugin, rs);
        }
        return rs;
    }

    static void detach(Plugin plugin) {
        synchronized (CONTEXTS) {
            CONTEXTS.remove(plugin);
        }
    }

    /** Shuts down every context still alive, newest first (when RsLib itself is disabled). */
    static void shutdownAll() {
        List<Rs> all = contexts();
        for (int i = all.size() - 1; i >= 0; i--) {
            Rs rs = all.get(i);
            try {
                rs.beginTeardown();
                rs.shutdown();
            } catch (RuntimeException e) {
                rs.plugin().getLogger().log(Level.WARNING, "Could not shut down cleanly", e);
            }
        }
    }
}
