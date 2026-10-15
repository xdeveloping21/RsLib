package com.rs.lib.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.Objects;

import org.slf4j.Logger;

import com.rs.lib.text.RsText;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;

/**
 * The context of a Velocity plugin built on RsLib: its managed {@code config.yml}, a scheduler
 * and command registration, with the same text pipeline as the Paper side. Create it in the
 * plugin's {@code ProxyInitializeEvent} handler (Velocity only links the plugin instance to its
 * plugin once the constructor has returned, and commands and tasks need that link), and declare
 * the dependency {@code "rslib"} in the plugin's {@code @Plugin} or {@code velocity-plugin.json}.
 *
 * <p>There is no messaging between servers. Velocity cancels a plugin's tasks and commands when
 * the proxy stops, so there is nothing to close.</p>
 */
public final class Rsv {

    /** The config file in the data folder and its default copy at the root of the plugin jar. */
    static final String CONFIG = "config.yml";

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final RsvScheduler scheduler;
    private volatile RsvConfig config;

    private Rsv(Object plugin, ProxyServer proxy, Logger logger, Path dataDir) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        this.scheduler = new RsvScheduler(plugin, proxy.getScheduler());
    }

    /**
     * Creates the context and loads {@code <dataDir>/config.yml} (see {@link RsvConfig#load}),
     * with the {@code config.yml} of the jar that holds {@code plugin}'s class as the defaults.
     *
     * @param plugin the plugin instance Velocity created (the one annotated {@code @Plugin})
     */
    public static Rsv create(Object plugin, ProxyServer proxy, Logger logger, Path dataDir) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(dataDir, "dataDir");
        Rsv rsv = new Rsv(plugin, proxy, logger, dataDir);
        rsv.reloadConfig();
        return rsv;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public Path dataDir() {
        return dataDir;
    }

    /** The config read by the last {@link #create} or {@link #reloadConfig()}. */
    public RsvConfig config() {
        return config;
    }

    public RsvScheduler scheduler() {
        return scheduler;
    }

    /** Reads {@code config.yml} again (adding the keys it lacks) and returns the new config. */
    public RsvConfig reloadConfig() {
        RsvConfig loaded = RsvConfig.load(dataDir.resolve(CONFIG), openDefaults(), logger);
        config = loaded;
        return loaded;
    }

    /** Config text ({@code &} codes, hex, MiniMessage, RsLib tags) to a component; see {@link RsText#color}. */
    public Component color(String text) {
        return RsText.color(text);
    }

    /**
     * Registers {@code command} under {@code name} and {@code aliases}, owned by this plugin.
     *
     * @return the registration, to pass to {@link CommandManager#unregister(CommandMeta)}
     */
    public CommandMeta command(String name, Command command, String... aliases) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
        CommandManager manager = proxy.getCommandManager();
        CommandMeta meta = manager.metaBuilder(name)
                .aliases(aliases == null ? new String[0] : aliases)
                .plugin(plugin)
                .build();
        manager.register(meta, command);
        return meta;
    }

    /** The jar copy of the config, or null when the plugin jar has none. */
    private InputStream openDefaults() {
        Class<?> type = plugin.getClass();
        URL url = defaultsUrl(type.getClassLoader(), codeSource(type), CONFIG);
        if (url == null) {
            return null;
        }
        try {
            URLConnection connection = url.openConnection();
            // A cached jar connection keeps the jar open, and Windows then refuses to replace it.
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException | RuntimeException e) {
            logger.error("Could not open the default {} inside the plugin jar", CONFIG, e);
            return null;
        }
    }

    private static URL codeSource(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null ? null : source.getLocation();
    }

    /**
     * The resource {@code name} from the jar (or folder) at {@code own}; the copy the loader
     * finds when {@code own} is unknown, and none for a class of the JDK itself. A class loader
     * also sees the resources of its parents, so a copy elsewhere (the proxy jar, for one) must
     * not stand in for a missing one.
     */
    static URL defaultsUrl(ClassLoader loader, URL own, String name) {
        if (loader == null) {
            return null;
        }
        try {
            if (own == null) {
                return loader.getResource(name);
            }
            String base = own.toString();
            String inJar = "jar:" + base + "!/";
            Enumeration<URL> all = loader.getResources(name);
            while (all.hasMoreElements()) {
                URL candidate = all.nextElement();
                String text = candidate.toString();
                if (text.startsWith(inJar) || (!base.endsWith(".jar") && text.startsWith(base))) {
                    return candidate;
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
