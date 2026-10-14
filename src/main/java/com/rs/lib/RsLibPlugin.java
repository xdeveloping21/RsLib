package com.rs.lib;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.compat.RsVersion;
import com.rs.lib.internal.ListenerHub;
import com.rs.lib.text.RsText;

/**
 * The RsLib plugin itself: loads at STARTUP, registers the listeners every Rs plugin shares
 * and offers {@code /rslib}.
 */
public final class RsLibPlugin extends JavaPlugin {

    private static volatile RsLibPlugin instance;

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

    /** The API level of this jar. */
    public int apiLevel() {
        return RsApi.LEVEL;
    }

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("Server " + RsVersion.describe() + (RsVersion.isFolia() ? " (Folia)" : ""));
        ListenerHub.registerAll(this);
        PluginCommand command = getCommand("rslib");
        if (command != null) {
            AdminCommand admin = new AdminCommand();
            command.setExecutor(admin);
            command.setTabCompleter(admin);
        }
        getLogger().info("RsLib " + getPluginMeta().getVersion() + " enabled (API level " + RsApi.LEVEL + ").");
    }

    @Override
    public void onDisable() {
        RsLib.shutdownAll();
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
                        sender.sendMessage("Usage: /" + label + " reload <plugin>");
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
                default -> sender.sendMessage("Usage: /" + label + " <version|reload <plugin>>");
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return filter(List.of("version", "reload"), args[0]);
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
