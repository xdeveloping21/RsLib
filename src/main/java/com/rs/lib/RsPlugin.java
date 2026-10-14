package com.rs.lib;

import java.util.logging.Level;

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
            if (!isEnabled()) {
                abortedQuietly(e);
            } else {
                getLogger().log(Level.SEVERE, "Error while enabling " + getName() + "; disabling it.", e);
                getServer().getPluginManager().disablePlugin(this);
            }
            return;
        }
        if (isEnabled()) {
            context.commands().applyLang();
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
            getLogger().severe(getName() + " needs RsLib API level " + required + " but the installed RsLib has "
                    + installed + ". Update RsLib from https://rsdevelopment.dev");
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
            if (!isEnabled()) {
                abortedQuietly(e);
            } else {
                getLogger().log(Level.SEVERE, "Error before enabling " + getName() + "; disabling it.", e);
                getServer().getPluginManager().disablePlugin(this);
            }
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

    /** The plugin disabled itself and then threw: one line, the trace only at FINE. */
    private void abortedQuietly(Throwable e) {
        String reason = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        getLogger().severe("Enable aborted: " + reason);
        getLogger().log(Level.FINE, "Enable aborted", e);
    }
}
