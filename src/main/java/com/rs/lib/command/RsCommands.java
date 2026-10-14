package com.rs.lib.command;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.ApiStatus;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.RsLibPlugin;
import com.rs.lib.lang.RsLang;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlManager;

/**
 * The plugin's commands, reached with {@code rs().commands()}.
 *
 * <pre>{@code
 * rs().commands().root("rskits")
 *         .permission("rskits.admin")
 *         .description("Kit administration")
 *         .aliasesFromConfig()
 *         .sub("give").description("Give a kit")
 *             .arg("player", Args.onlinePlayer())
 *             .arg("kit", Args.oneOf(kits::names))
 *             .executes(ctx -> give(ctx.player("player"), ctx.get("kit")))
 *         .and()
 *         .register();
 * }</pre>
 *
 * <p>Every root gets {@code reload} ({@code <plugin>.admin.reload}) and {@code help} unless it
 * declares its own or calls {@code withoutDefaults()}; with {@code RsSpec.debugCommand()} it
 * also gets {@code debug} ({@code <plugin>.admin.debug}).</p>
 */
public final class RsCommands {

    /** Config key read by {@link RootBuilder#aliasesFromConfig()}. */
    public static final String CONFIG_ALIASES_KEY = "command.aliases";

    private final Rs rs;
    private final RsLang lang;
    private final boolean debugCommand;
    private final boolean commandPriority;
    private final List<RootCommand> roots = new ArrayList<>();
    private final Set<String> logged = new HashSet<>();

    public RsCommands(Rs rs, RsLang lang, boolean debugCommand, boolean commandPriority) {
        this.rs = Objects.requireNonNull(rs, "rs");
        this.lang = lang;
        this.debugCommand = debugCommand;
        this.commandPriority = commandPriority;
    }

    /** Starts a command; nothing reaches the server until {@link RootBuilder#register()}. */
    public RootBuilder root(String name) {
        return new RootBuilder(word(name, "command name"));
    }

    /** The roots of this plugin, in the order they were registered. */
    public List<RootCommand> roots() {
        synchronized (roots) {
            return List.copyOf(roots);
        }
    }

    /** Takes every root of this plugin out of the server (the plugin is shutting down). */
    public void unregisterAll() {
        for (RootCommand root : roots()) {
            onMain(() -> CommandRegistry.disconnect(root));
        }
        synchronized (roots) {
            roots.clear();
        }
    }

    /** Registers the same roots again: aliases are read from config again and taken names recovered. */
    public void reRegisterAll() {
        for (RootCommand root : roots()) {
            onMain(() -> CommandRegistry.connect(root));
        }
    }

    /**
     * Writes the {@code commands:} block of the language file and applies its texts. RsLib calls
     * it after {@code onInnerEnable()} and on reload; call it after registering a root later.
     */
    public void applyLang() {
        if (lang == null) {
            return;
        }
        try {
            CommandLang.apply(lang, roots());
        } catch (RuntimeException | LinkageError e) {
            rs.plugin().getLogger().log(Level.WARNING, "Could not apply the command texts of the language file", e);
        }
    }

    /**
     * The root behind a command from the server's command map: the command itself, or the
     * root a plugin.yml command hands over to. Null when it is not an RsLib root.
     */
    public static RootCommand rootOf(Command command) {
        if (command instanceof RootCommand root) {
            return root;
        }
        if (command instanceof PluginCommand declared) {
            return CommandRegistry.rootBehind(declared);
        }
        return null;
    }

    /** The listener that keeps names taken with {@code commandPriority()}; RsLib registers it once. */
    @ApiStatus.Internal
    public static Listener priorityListener() {
        return new CommandRegistry.PriorityListener();
    }

    JavaPlugin plugin() {
        return rs.plugin();
    }

    void register(RootCommand root) {
        synchronized (roots) {
            if (!roots.contains(root)) {
                RootCommand replaced = null;
                for (RootCommand existing : roots) {
                    if (existing.getName().equals(root.getName())) {
                        replaced = existing;
                    }
                }
                if (replaced != null) {
                    roots.remove(replaced);
                    RootCommand old = replaced;
                    onMain(() -> CommandRegistry.disconnect(old));
                }
                roots.add(root);
            }
        }
        onMain(() -> CommandRegistry.connect(root));
    }

    void unregister(RootCommand root) {
        synchronized (roots) {
            roots.remove(root);
        }
        onMain(() -> CommandRegistry.disconnect(root));
    }

    /** Sends one of RsLib's messages, through the plugin's language file when it has one. */
    void send(CommandSender sender, String key, Ph... phs) {
        if (lang != null) {
            lang.send(sender, key, phs);
            return;
        }
        String template = Defaults.get(key);
        sender.sendMessage(RsText.color(RsText.applyLocals(template, phs)));
    }

    /** Logs {@code message} once per enable of the plugin for each {@code id}. */
    void logOnce(String id, Level level, String message) {
        boolean first;
        synchronized (logged) {
            first = logged.add(id);
        }
        if (first) {
            rs.plugin().getLogger().log(level, message);
        }
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        RsLibPlugin lib = RsLibPlugin.running();
        JavaPlugin owner = lib != null && lib.isEnabled() ? lib : rs.plugin();
        Bukkit.getScheduler().runTask(owner, action);
    }

    /** A command word: trimmed, lowercase, one word. */
    static String word(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The " + what + " is empty");
        }
        String clean = text.trim().toLowerCase(Locale.ROOT);
        if (clean.indexOf(' ') >= 0 || clean.indexOf(':') >= 0 || clean.indexOf('.') >= 0) {
            throw new IllegalArgumentException("The " + what + " '" + text + "' must be one word without ':' or '.'");
        }
        return clean;
    }

    /** Fails when two siblings answer to the same word. */
    static void claimWords(Set<String> taken, String name, List<String> aliases, String where) {
        if (!taken.add(name)) {
            throw new IllegalStateException("'" + name + "' is declared twice under '" + where + "'");
        }
        for (String alias : aliases) {
            if (!taken.add(alias)) {
                throw new IllegalStateException("Alias '" + alias + "' is used twice under '" + where + "'");
            }
        }
    }

    /** Declares one command and its subcommands. */
    public final class RootBuilder {

        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private final List<SubCommandBuilder> subs = new ArrayList<>();
        private Supplier<? extends Collection<String>> aliasSupplier;
        private String permission;
        private String description = "";
        private boolean withoutDefaults;
        private Consumer<RootContext> onEmpty;
        private String fallbackSub;
        private boolean dynamic;

        private RootBuilder(String name) {
            this.name = name;
        }

        public RootBuilder aliases(String... aliases) {
            for (String alias : aliases) {
                this.aliases.add(word(alias, "alias"));
            }
            return this;
        }

        /** Aliases read on every register and reload; null keeps the ones from {@link #aliases(String...)}. */
        public RootBuilder aliases(Supplier<? extends Collection<String>> supplier) {
            this.aliasSupplier = Objects.requireNonNull(supplier, "supplier");
            return this;
        }

        /** Aliases from {@code command.aliases} in config.yml; an empty list removes them all. */
        public RootBuilder aliasesFromConfig() {
            return aliasesFromConfig(CONFIG_ALIASES_KEY);
        }

        /** Aliases from {@code key} in config.yml. When the key is missing, the code's aliases apply. */
        public RootBuilder aliasesFromConfig(String key) {
            Objects.requireNonNull(key, "key");
            return aliases(() -> configAliases(key));
        }

        /** Needed for the command and every subcommand. */
        public RootBuilder permission(String permission) {
            this.permission = permission == null || permission.isBlank() ? null : permission.trim();
            return this;
        }

        public RootBuilder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public SubCommandBuilder sub(String name) {
            SubCommandBuilder sub = new SubCommandBuilder(this, name);
            subs.add(sub);
            return sub;
        }

        /** No automatic {@code reload} and {@code help}; declare your own if you need them. */
        public RootBuilder withoutDefaults() {
            this.withoutDefaults = true;
            return this;
        }

        /** Runs when the command is typed alone; without it, the help is shown. */
        public RootBuilder onEmpty(Consumer<RootContext> action) {
            this.onEmpty = Objects.requireNonNull(action, "action");
            return this;
        }

        /**
         * A first word that is not a subcommand goes to this subcommand as its first argument
         * ({@code /trade Steve} runs {@code /trade request Steve}). It must be a first-level leaf.
         */
        public RootBuilder fallbackSub(String name) {
            this.fallbackSub = word(name, "fallback subcommand");
            return this;
        }

        /** Registered from code only (not in plugin.yml); collisions are reported quietly. */
        public RootBuilder dynamic() {
            this.dynamic = true;
            return this;
        }

        public RootCommand register() {
            List<Node> top = new ArrayList<>();
            Set<String> taken = new HashSet<>();
            for (SubCommandBuilder sub : subs) {
                claimWords(taken, sub.name(), sub.aliases(), name);
                top.add(sub.build(""));
            }
            RootCommand[] self = new RootCommand[1];
            String pluginId = rs.plugin().getName().toLowerCase(Locale.ROOT);
            if (!withoutDefaults) {
                addDefault(top, taken, "reload", pluginId + ".admin.reload", "Reload the configuration", ctx -> {
                    rs.reloadAll();
                    send(ctx.sender(), "rslib.reload-done");
                });
                addDefault(top, taken, "help", null, "Show this help", ctx -> {
                    String page = ctx.raw(0);
                    int number = 1;
                    if (page != null) {
                        try {
                            number = Integer.parseInt(page.trim());
                        } catch (NumberFormatException notANumber) {
                            number = 1;
                        }
                    }
                    self[0].sendHelp(ctx.sender(), ctx.label(), number);
                });
            }
            if (debugCommand) {
                addDefault(top, taken, "debug", pluginId + ".admin.debug", "Turn debug messages on or off", ctx -> {
                    boolean on = rs.debug().toggle();
                    send(ctx.sender(), on ? "rslib.debug-on" : "rslib.debug-off", Ph.of("plugin", rs.plugin().getName()));
                });
            }
            Node fallback = null;
            if (fallbackSub != null) {
                for (Node node : top) {
                    if (node.answersTo(fallbackSub)) {
                        fallback = node;
                        break;
                    }
                }
                if (fallback == null || fallback.group()) {
                    throw new IllegalStateException("fallbackSub('" + fallbackSub + "') of /" + name
                            + " must name a first-level subcommand without children");
                }
            }
            RootCommand root = new RootCommand(RsCommands.this, name, permission, description, aliases, aliasSupplier,
                    dynamic, commandPriority, onEmpty, top, fallback);
            self[0] = root;
            RsCommands.this.register(root);
            return root;
        }

        private void addDefault(List<Node> top, Set<String> taken, String word, String perm, String text,
                Consumer<CommandContext> run) {
            if (taken.contains(word)) {
                return;
            }
            taken.add(word);
            List<Node.ArgSpec> args = word.equals("help")
                    ? List.of(new Node.ArgSpec("page", Args.string(), true)) : List.of();
            top.add(new Node(word, List.of(), word, perm, null, text, true, true, false, args, List.of(), run, List.of()));
        }

        private Collection<String> configAliases(String key) {
            YmlManager yml = rs.ymlOrNull();
            if (yml == null) {
                return null;
            }
            RsYml config = yml.config();
            if (!config.isSet(key)) {
                return null;
            }
            return config.getStringList(key, null);
        }
    }

    /** RsLib's own messages, used as they are when the plugin has no language module. */
    private static final class Defaults {

        private static final YamlConfiguration MESSAGES = load();

        static String get(String key) {
            String value = MESSAGES.getString(key);
            return value == null ? "<missing:" + key + ">" : value;
        }

        private static YamlConfiguration load() {
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream in = RsCommands.class.getResourceAsStream("/rslib-messages_en.yml")) {
                if (in != null) {
                    yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (Exception unreadable) {
                Bukkit.getLogger().log(Level.WARNING, "[RsLib] Could not read rslib-messages_en.yml", unreadable);
            }
            return yaml;
        }
    }
}
