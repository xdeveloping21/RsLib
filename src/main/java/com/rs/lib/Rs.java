package com.rs.lib;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.command.RsCommands;
import com.rs.lib.debug.RsDebug;
import com.rs.lib.hook.SoftDependency;
import com.rs.lib.lang.RsLang;
import com.rs.lib.papi.RsPapi;
import com.rs.lib.reload.ReloadManager;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.update.UpdateChecker;
import com.rs.lib.yml.YmlManager;

/**
 * Everything RsLib gives one plugin, reached with {@link RsPlugin#rs()}.
 *
 * <p>Modules are created once, in a fixed order, from the plugin's {@link RsSpec}. A module
 * the spec did not declare throws {@link UnsupportedOperationException} from its accessor,
 * naming the builder call that would enable it.</p>
 */
public final class Rs {

    private final JavaPlugin plugin;
    private final RsSpec spec;
    private final RsScheduler scheduler;
    private final RsPapi papi;
    private final YmlManager yml;
    private final RsDebug debug;
    private final RsLang lang;
    private final RsCommands commands;
    private final ReloadManager reload;
    private final UpdateChecker updates;
    private final List<Consumer<Player>> quitCallbacks = new CopyOnWriteArrayList<>();

    private volatile boolean shuttingDown;
    private volatile boolean shutDown;

    Rs(JavaPlugin plugin, RsSpec spec) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.spec = Objects.requireNonNull(spec, "spec");
        this.scheduler = new RsScheduler(plugin);
        this.papi = new RsPapi(plugin);
        this.yml = spec.config() == null ? null : new YmlManager(this, spec.config());
        this.debug = new RsDebug(plugin, yml == null ? null : yml.config());
        this.lang = spec.lang() ? new RsLang(this, yml == null ? null : yml.config()) : null;
        this.commands = new RsCommands(this, lang, spec.debugCommand(), spec.commandPriority());
        this.reload = new ReloadManager(this);
        this.updates = new UpdateChecker(this);
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public RsSpec spec() {
        return spec;
    }

    public RsScheduler scheduler() {
        return scheduler;
    }

    public RsPapi papi() {
        return papi;
    }

    public RsDebug debug() {
        return debug;
    }

    /** Config and other YAML files. Needs {@code RsSpec.builder().config("config.yml")}. */
    public YmlManager yml() {
        if (yml == null) {
            throw missing("yml()", "config(\"config.yml\")");
        }
        return yml;
    }

    /** Messages. Needs {@code RsSpec.builder().lang()}. */
    public RsLang lang() {
        if (lang == null) {
            throw missing("lang()", "lang()");
        }
        return lang;
    }

    public RsCommands commands() {
        return commands;
    }

    public ReloadManager reload() {
        return reload;
    }

    /** Update checks; idle unless the spec declares {@code updates(...)} or the plugin calls it. */
    public UpdateChecker updates() {
        return updates;
    }

    /** Reloads config, messages, commands and every registered {@code Reloadable}. */
    public void reloadAll() {
        reload.reloadPlugin();
    }

    /** True from the moment the plugin starts shutting down: saves become synchronous. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /** Runs {@code callback} when a player leaves the server (not on kick handling of other plugins). */
    public void onQuit(Consumer<Player> callback) {
        quitCallbacks.add(Objects.requireNonNull(callback, "callback"));
    }

    /** Null-safe accessors used inside RsLib while the context is still being built. */
    public YmlManager ymlOrNull() {
        return yml;
    }

    public RsLang langOrNull() {
        return lang;
    }

    public RsDebug debugOrNull() {
        return debug;
    }

    /** Arms the update watch the spec declares; called once the plugin has enabled. */
    void armUpdates() {
        if (spec.updatesRepo() != null) {
            updates.watch(spec.updatesRepo(), spec.updatesTagPrefix());
        }
    }

    void beginTeardown() {
        shuttingDown = true;
    }

    /** Runs the quit callbacks; called by RsLib's shared listener. */
    public void handleQuit(Player player) {
        for (Consumer<Player> callback : quitCallbacks) {
            try {
                callback.accept(player);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A quit callback failed for " + player.getName(), e);
            }
        }
    }

    /**
     * Takes everything down in a fixed order. Safe to call more than once; {@link RsPlugin}
     * calls it after {@code onInnerDisable()}.
     */
    public void shutdown() {
        if (shutDown) {
            return;
        }
        shutDown = true;
        shuttingDown = true;
        step("update checks", updates::shutdown);
        step("commands", commands::unregisterAll);
        if (yml != null) {
            step("pending file writes", yml::flushAll);
        }
        step("tasks", scheduler::cancelAll);
        step("soft dependencies", () -> SoftDependency.disableAllOf(plugin));
        quitCallbacks.clear();
        RsLib.detach(plugin);
    }

    private void step(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Could not shut down " + what, e);
        }
    }

    private UnsupportedOperationException missing(String accessor, String builderCall) {
        return new UnsupportedOperationException(plugin.getName() + " called rs()." + accessor
                + " without declaring it: add ." + builderCall + " to buildSpec().");
    }
}
