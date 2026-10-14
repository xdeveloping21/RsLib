package com.rs.lib.papi;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PlaceholderAPI for one plugin, safe when PlaceholderAPI is not installed.
 *
 * <p>No PlaceholderAPI type is referenced here: they live in {@code PapiBridge}, loaded only
 * once PlaceholderAPI is found enabled. {@link #apply} resolves only on the main thread, so
 * async code never runs other plugins' expansions by accident; off the main thread the
 * tokens are left as written. {@link #applyHere} resolves on any thread, for places such as
 * Paper's async chat where that is expected.</p>
 */
public final class RsPapi {

    private final JavaPlugin plugin;
    private volatile Boolean available;

    public RsPapi(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** True when PlaceholderAPI is installed, enabled and usable. */
    public boolean available() {
        Boolean known = available;
        if (known == null) {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            known = papi != null && papi.isEnabled();
            available = known;
        }
        return known;
    }

    /** Forgets whether PlaceholderAPI is present (called when it is enabled or disabled). */
    public void invalidate() {
        available = null;
    }

    /** Resolves {@code %...%} tokens on the main thread; elsewhere returns the text unchanged. */
    public String apply(Player viewer, String text) {
        if (!Bukkit.isPrimaryThread()) {
            return text;
        }
        return applyHere(viewer, text);
    }

    public List<String> apply(Player viewer, List<String> lines) {
        if (lines == null || !Bukkit.isPrimaryThread()) {
            return lines;
        }
        return applyHere(viewer, lines);
    }

    /** Resolves on the current thread. If an expansion throws, the text comes back unchanged. */
    public String applyHere(Player viewer, String text) {
        if (text == null || text.indexOf('%') < 0 || !available()) {
            return text;
        }
        try {
            return PapiBridge.set(viewer, text);
        } catch (LinkageError broken) {
            available = false;
            plugin.getLogger().log(Level.WARNING, "PlaceholderAPI could not be used; placeholders stay as written.", broken);
            return text;
        } catch (RuntimeException expansionFailed) {
            return text;
        }
    }

    public List<String> applyHere(Player viewer, List<String> lines) {
        if (lines == null) {
            return null;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(applyHere(viewer, line));
        }
        return out;
    }
}
