package com.rs.lib;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.compat.RsVersion;
import com.rs.lib.debug.RsDebug;
import com.rs.lib.internal.ListenerHub;
import com.rs.lib.item.ItemRegistry;
import com.rs.lib.text.RsText;
import com.rs.lib.update.internal.SelfUpdater;
import com.rs.lib.update.internal.UpdateHub;
import com.rs.lib.util.PlayerNames;

/**
 * The RsLib plugin itself: loads at STARTUP, registers the listeners every Rs plugin shares,
 * keeps itself up to date ({@link SelfUpdater}) and offers {@code /rslib}. It has a small
 * context of its own for {@code plugins/RsLib/config.yml}, whose {@code debug} section drives
 * {@link #libDebug()}.
 */
public final class RsLibPlugin extends JavaPlugin {

    private static volatile RsLibPlugin instance;

    private SelfUpdater selfUpdater;
    private Rs own;

    /** The running RsLib; throws if RsLib is not enabled. */
    public static RsLibPlugin get() {
        RsLibPlugin current = instance;
        if (current == null) {
            throw new IllegalStateException("RsLib is not enabled");
        }
        return current;
    }

    /** The running RsLib, or null. */
    public static RsLibPlugin running() {
        return instance;
    }

    /**
     * RsLib's own debug output ({@code debug} in plugins/RsLib/config.yml, {@code /rslib debug}),
     * or null while RsLib is not enabled. For RsLib's internals.
     */
    public static RsDebug libDebug() {
        RsLibPlugin current = instance;
        Rs context = current == null ? null : current.own;
        return context == null ? null : context.debug();
    }

    /** The API level of this jar. */
    public int apiLevel() {
        return RsApi.LEVEL;
    }

    @Override
    public void onEnable() {
        instance = this;
        try {
            own = new Rs(this, RsSpec.builder().config(SelfUpdater.CONFIG_FILE).build());
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "RsLib's config.yml could not be loaded; debug output stays off", e);
        }
        getLogger().info("Server " + RsVersion.describe() + (RsVersion.isFolia() ? " (Folia)" : ""));
        ListenerHub.registerAll(this);
        ItemRegistry.startShared(this);
        try {
            PlayerNames.start(this);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "The player name directory could not start", e);
        }
        PluginCommand command = getCommand("rslib");
        if (command != null) {
            AdminCommand admin = new AdminCommand();
            command.setExecutor(admin);
            command.setTabCompleter(admin);
        }
        try {
            selfUpdater = SelfUpdater.start(this);
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Automatic updates could not start", e);
        }
        getLogger().info("RsLib " + getPluginMeta().getVersion() + " enabled (API level " + RsApi.LEVEL + ").");
    }

    @Override
    public void onDisable() {
        if (selfUpdater != null) {
            selfUpdater.shutdown();
            selfUpdater = null;
        }
        RsLib.shutdownAll();
        ItemRegistry.shutdownShared();
        PlayerNames.shutdown();
        UpdateHub.shutdown();
        if (own != null) {
            own.beginTeardown();
            own.shutdown();
            own = null;
        }
        RsText.clearCache();
        instance = null;
    }

    private final class AdminCommand implements TabExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            String sub = args.length == 0 ? "version" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload" -> {
                    if (!sender.hasPermission("rslib.admin.reload")) {
                        sender.sendMessage("You do not have permission.");
                        return true;
                    }
                    if (args.length < 2) {
                        if (own == null) {
                            sender.sendMessage("RsLib's config.yml is not loaded.");
                            return true;
                        }
                        own.reloadAll();
                        sender.sendMessage("RsLib's config.yml reloaded.");
                        return true;
                    }
                    Plugin target = Bukkit.getPluginManager().getPlugin(args[1]);
                    Rs rs = target == null ? null : RsLib.context(target);
                    if (rs == null) {
                        sender.sendMessage(args[1] + " is not a running plugin built on RsLib.");
                        return true;
                    }
                    rs.reloadAll();
                    sender.sendMessage(target.getName() + " reloaded.");
                }
                case "version" -> {
                    if (!sender.hasPermission("rslib.admin.version")) {
                        sender.sendMessage("You do not have permission.");
                        return true;
                    }
                    String users = RsLib.contexts().stream().map(rs -> rs.plugin().getName())
                            .collect(Collectors.joining(", "));
                    sender.sendMessage("RsLib " + getPluginMeta().getVersion() + " (API level " + RsApi.LEVEL
                            + ") on " + RsVersion.describe() + ". Plugins: " + (users.isEmpty() ? "none" : users));
                }
                case "update" -> {
                    if (!sender.hasPermission(SelfUpdater.PERMISSION)) {
                        sender.sendMessage("You do not have permission.");
                        return true;
                    }
                    if (selfUpdater == null) {
                        sender.sendMessage("The RsLib updater is not running.");
                        return true;
                    }
                    selfUpdater.runNow(sender);
                }
                case "debug" -> {
                    if (!sender.hasPermission("rslib.admin.debug")) {
                        sender.sendMessage("You do not have permission.");
                        return true;
                    }
                    RsDebug debug = libDebug();
                    if (debug == null) {
                        sender.sendMessage("RsLib's config.yml is not loaded.");
                        return true;
                    }
                    sender.sendMessage("RsLib debug: " + (debug.toggle() ? "ON" : "OFF"));
                }
                default -> sender.sendMessage("Usage: /" + label + " <version|reload [plugin]|update|debug>");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return filter(List.of("version", "reload", "update", "debug"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
                return filter(RsLib.contexts().stream().map(rs -> rs.plugin().getName()).toList(), args[1]);
            }
            return List.of();
        }

        private List<String> filter(List<String> options, String typed) {
            String lower = typed.toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
        }
    }
}
