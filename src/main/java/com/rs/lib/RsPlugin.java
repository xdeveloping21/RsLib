package com.rs.lib;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Base class of every plugin built on RsLib.
 *
 * <p>{@link #onEnable()} and {@link #onDisable()} are final; a plugin fills these hooks:</p>
 * <ol>
 *     <li>{@link #requiredApiLevel()}: always {@code return RsApi.LEVEL;}.</li>
 *     <li>{@link #onPreEnable()}: optional gate that runs before RsLib writes any file
 *     (license checks go here). Return false, or disable the plugin, to stop.</li>
 *     <li>{@link #buildSpec()}: which modules the plugin uses.</li>
 *     <li>{@link #onInnerEnable()}: the plugin's own start; {@link #rs()} is ready.</li>
 *     <li>{@link #onInnerDisable()}: optional final save; every module is still alive and
 *     YAML saves are written immediately.</li>
 * </ol>
 */
public abstract class RsPlugin extends JavaPlugin {

    private volatile Rs rs;

    /** Return {@code RsApi.LEVEL}; the compiler stores the number inside this plugin. */
    protected abstract int requiredApiLevel();

    protected RsSpec buildSpec() {
        return RsSpec.empty();
    }

    /** Runs before RsLib touches the plugin folder. Default: continue. */
    protected boolean onPreEnable() {
        return true;
    }

    protected abstract void onInnerEnable();

    protected void onInnerDisable() {
    }

    /** This plugin's RsLib context; null before {@code onInnerEnable()} and after shutdown. */
    public final Rs rs() {
        return rs;
    }

    @Override
    public final void onEnable() {
        rs = null;
        if (!handshake() || !preEnable()) {
            return;
        }
        Rs context;
        try {
            context = RsLib.init(this, buildSpec());
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Could not start RsLib for " + getName(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        rs = context;
        try {
            onInnerEnable();
        } catch (RuntimeException | LinkageError e) {
            hookFailed(getLogger(), "onInnerEnable", e, isEnabled(),
                    () -> getServer().getPluginManager().disablePlugin(this));
            return;
        }
        if (isEnabled()) {
            context.commands().applyLang();
            context.armUpdates();
        }
    }

    @Override
    public final void onDisable() {
        Rs context = rs;
        if (context == null) {
            return;
        }
        context.beginTeardown();
        try {
            onInnerDisable();
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Error while disabling " + getName(), e);
        } finally {
            context.shutdown();
            rs = null;
        }
    }

    private boolean handshake() {
        int installed;
        try {
            installed = RsLibPlugin.get().apiLevel();
        } catch (IllegalStateException | NoClassDefFoundError notLoaded) {
            getLogger().severe("RsLib is not enabled. Install RsLib and add 'depend: [RsLib]' to plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        int required = requiredApiLevel();
        if (installed < required) {
            getLogger().severe(handshakeMessage(required, installed));
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }

    private boolean preEnable() {
        boolean proceed;
        try {
            proceed = onPreEnable();
        } catch (RuntimeException | LinkageError e) {
            hookFailed(getLogger(), "onPreEnable", e, isEnabled(),
                    () -> getServer().getPluginManager().disablePlugin(this));
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        if (!proceed) {
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }

    /**
     * The line logged when the installed RsLib is older than the plugin needs. The plugin's
     * logger already starts it with {@code [<Plugin>]}.
     */
    static String handshakeMessage(int required, int installed) {
        return "Requires RsLib API level " + required + " (installed: " + installed
                + "). Update RsLib.jar (restart required): https://rsdevelopment.dev/plugins/rslib";
    }

    /**
     * A hook threw. When the plugin had already disabled itself that was its own refusal: one
     * line, the trace only at FINE. Otherwise it blew up: SEVERE with the trace, and the plugin
     * is disabled.
     */
    static void hookFailed(Logger logger, String hook, Throwable e, boolean stillEnabled, Runnable disable) {
        if (!stillEnabled) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            logger.severe("Enable aborted: " + reason);
            logger.log(Level.FINE, "Enable aborted", e);
            return;
        }
        logger.log(Level.SEVERE, hook + " failed", e);
        disable.run();
    }
}
