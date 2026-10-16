package com.rs.lib;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.audit.AuditCommand;
import com.rs.lib.compat.RsVersion;
import com.rs.lib.debug.RsDebug;
import com.rs.lib.hook.SoftDependency;
import com.rs.lib.internal.AdminInfo;
import com.rs.lib.internal.ListenerHub;
import com.rs.lib.item.ItemRegistry;
import com.rs.lib.text.RsText;
import com.rs.lib.update.internal.SelfUpdater;
import com.rs.lib.update.internal.UpdateHub;
import com.rs.lib.util.PlayerNames;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YamlUpdater;

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

    /**
     * RsLib's own {@code plugins/RsLib/config.yml}, or null while RsLib is not enabled or the
     * file could not be loaded. For RsLib's internals (the {@code notices} and {@code audit}
     * settings), read on every use so a reload applies.
     */
    public static RsYml libConfig() {
        RsLibPlugin current = instance;
        Rs context = current == null ? null : current.own;
        return context == null ? null : context.ymlOrNull() == null ? null : context.ymlOrNull().config();
    }

    /** The API level of this jar. */
    public int apiLevel() {
        return RsApi.LEVEL;
    }

    @Override
    public void onEnable() {
        instance = this;
        try {
            // updates(): "RsLib has a new version" one minute after start and every six hours,
            // even with auto-update off; the self-updater's pass comes after it.
            own = new Rs(this, RsSpec.builder().config(SelfUpdater.CONFIG_FILE).updates().build());
            RsYml config = own.yml().config();
            applyMergeSwitch(config);
            config.onReload(() -> {
                applyMergeSwitch(config);
                if (selfUpdater != null) {
                    selfUpdater.reload();
                }
            });
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
        if (own != null) {
            own.armUpdates();
        }
        getLogger().info("RsLib " + getPluginMeta().getVersion() + " enabled (API level " + RsApi.LEVEL + ").");
    }

    /** {@code update-configs} (true by default): whether every plugin's YAML files get the keys they lack. */
    private void applyMergeSwitch(RsYml config) {
        boolean enabled = config.getBoolean("update-configs", true);
        YamlUpdater.setMergeEnabled(enabled, new File(getDataFolder(), SelfUpdater.CONFIG_FILE));
        if (!enabled) {
            getLogger().info("update-configs is false: missing keys are reported, not added to the files.");
        }
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
        YamlUpdater.setMergeEnabled(true, null);
        instance = null;
    }

    /** The subcommands in the order help lists them, each with its permission (null: none). */
    private enum Sub {
        VERSION("rslib.admin.version", "the library version, API level and server version"),
        PLUGINS("rslib.admin.plugins", "the plugins hooked to RsLib"),
        INTEGRATIONS("rslib.admin.integrations", "the registered soft-dependency hooks"),
        ITEMINFO("rslib.admin.iteminfo", "the PDC tags of the held item"),
        UPDATE(SelfUpdater.PERMISSION, "the self-updater state, and a check now"),
        RELOAD("rslib.admin.reload", "reload RsLib's config.yml, or one plugin's files"),
        LOGS(AuditCommand.PERMISSION, "the audit log of the plugins, newest first"),
        DEBUG("rslib.admin.debug", "turn RsLib's debug output on or off"),
        HELP(null, "the subcommands you can use");

        final String permission;
        final String what;

        Sub(String permission, String what) {
            this.permission = permission;
            this.what = what;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean allowed(CommandSender sender) {
            return permission == null || sender.hasPermission(permission);
        }

        static Sub of(String name) {
            for (Sub sub : values()) {
                if (sub.label().equalsIgnoreCase(name)) {
                    return sub;
                }
            }
            return null;
        }
    }

    private static final String USAGE = "<version|plugins|integrations|iteminfo|update|reload|logs|debug|help>";

    private final class AdminCommand implements TabExecutor {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            Sub sub = args.length == 0 ? Sub.HELP : Sub.of(args[0]);
            if (sub == null) {
                sender.sendMessage("Usage: /" + label + " " + USAGE);
                return true;
            }
            if (!sub.allowed(sender)) {
                sender.sendMessage("You do not have permission.");
                return true;
            }
            switch (sub) {
                case VERSION -> {
                    sender.sendMessage("RsLib version: " + getPluginMeta().getVersion());
                    sender.sendMessage("API level: " + RsApi.LEVEL);
                    sender.sendMessage("Server: " + Bukkit.getBukkitVersion() + " (detected: " + RsVersion.describe()
                            + (RsVersion.isFolia() ? " Folia" : "") + ")");
                }
                case PLUGINS -> AdminInfo.plugins(RsLib.contexts().stream()
                        .map(rs -> new AdminInfo.Consumer(rs.plugin().getName(), rs.plugin().getPluginMeta().getVersion()))
                        .toList()).forEach(sender::sendMessage);
                case INTEGRATIONS -> AdminInfo.integrations(hooks()).forEach(sender::sendMessage);
                case ITEMINFO -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("Only a player can use this.");
                        return true;
                    }
                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) {
                        sender.sendMessage("Hold an item in your main hand.");
                        return true;
                    }
                    ItemMeta meta = item.getItemMeta();
                    AdminInfo.itemInfo(item.getType().name(), meta == null ? null : meta.getPersistentDataContainer())
                            .forEach(sender::sendMessage);
                }
                case UPDATE -> {
                    if (selfUpdater == null) {
                        sender.sendMessage("The RsLib updater is not running.");
                        return true;
                    }
                    selfUpdater.runNow(sender);
                }
                case RELOAD -> reload(sender, args.length < 2 ? null : args[1]);
                case LOGS -> AuditCommand.run(RsLibPlugin.this, sender, label, Arrays.copyOfRange(args, 1, args.length));
                case DEBUG -> {
                    RsDebug debug = libDebug();
                    if (debug == null) {
                        sender.sendMessage("RsLib's config.yml is not loaded.");
                        return true;
                    }
                    sender.sendMessage("Debug: " + (debug.toggle() ? "ON" : "OFF"));
                }
                case HELP -> {
                    sender.sendMessage("RsLib commands:");
                    for (Sub each : Sub.values()) {
                        if (each.allowed(sender)) {
                            sender.sendMessage("- /" + label + " " + each.label()
                                    + (each == Sub.RELOAD ? " [plugin]" : each == Sub.LOGS ? " " + AuditCommand.USAGE : "")
                                    + ": " + each.what);
                        }
                    }
                }
            }
            return true;
        }

        /** Without a name (or with RsLib's), RsLib's own config.yml; with one, that plugin's files. */
        private void reload(CommandSender sender, String name) {
            if (name == null || name.equalsIgnoreCase(getName())) {
                if (own == null) {
                    sender.sendMessage("RsLib's config.yml is not loaded.");
                    return;
                }
                own.reloadAll();
                sender.sendMessage("RsLib configuration reloaded (debug + update-configs + auto-update).");
                sender.sendMessage("A reload never reloads classes: updating the RsLib jar requires a server restart.");
                return;
            }
            Plugin target = Bukkit.getPluginManager().getPlugin(name);
            if (target == null) {
                sender.sendMessage("Plugin not found: " + name);
                return;
            }
            Rs rs = RsLib.context(target);
            if (rs == null) {
                sender.sendMessage("Plugin " + target.getName() + " is not hooked to RsLib.");
                return;
            }
            rs.reloadAll();
            sender.sendMessage("Configuration of " + target.getName() + " reloaded.");
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return filter(Arrays.stream(Sub.values()).filter(s -> s.allowed(sender)).map(Sub::label).toList(),
                        args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reload") && Sub.RELOAD.allowed(sender)) {
                List<String> names = new ArrayList<>();
                names.add(getName());
                RsLib.contexts().forEach(rs -> names.add(rs.plugin().getName()));
                return filter(names, args[1]);
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("logs") && Sub.LOGS.allowed(sender)) {
                return AuditCommand.complete(Arrays.copyOfRange(args, 1, args.length));
            }
            return List.of();
        }

        /**
         * Every live soft-dependency hook of the plugins built on RsLib (Vault, through their
         * economy), plus PlaceholderAPI for each one that lists it in depend or softdepend.
         */
        private List<AdminInfo.Hook> hooks() {
            List<AdminInfo.Hook> hooks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            SoftDependency.forEachRegistered(dep -> {
                if (dep.owner() == RsLibPlugin.this) {
                    return;
                }
                if (seen.add(dep.owner().getName() + "\n" + dep.pluginName().toLowerCase(Locale.ROOT))) {
                    hooks.add(new AdminInfo.Hook(dep.owner().getName(), dep.pluginName(), dep.isAvailable()));
                }
            });
            for (Rs rs : RsLib.contexts()) {
                var meta = rs.plugin().getPluginMeta();
                boolean usesPapi = meta.getPluginDependencies().contains("PlaceholderAPI")
                        || meta.getPluginSoftDependencies().contains("PlaceholderAPI");
                if (usesPapi && seen.add(rs.plugin().getName() + "\nplaceholderapi")) {
                    hooks.add(new AdminInfo.Hook(rs.plugin().getName(), "PlaceholderAPI", rs.papi().available()));
                }
            }
            return hooks;
        }

        private List<String> filter(List<String> options, String typed) {
            String lower = typed.toLowerCase(Locale.ROOT);
            return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
        }
    }
}
