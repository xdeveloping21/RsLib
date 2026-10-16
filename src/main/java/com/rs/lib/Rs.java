package com.rs.lib;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.action.ActionEngine;
import com.rs.lib.bossbar.BossBarUtil;
import com.rs.lib.command.RsCommands;
import com.rs.lib.cooldown.Cooldowns;
import com.rs.lib.cron.RsCron;
import com.rs.lib.db.DbConfig;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.internal.MainHops;
import com.rs.lib.debug.RsDebug;
import com.rs.lib.discord.DiscordWebhook;
import com.rs.lib.economy.EconomyBridge;
import com.rs.lib.hook.SoftDependency;
import com.rs.lib.gui.GuiManager;
import com.rs.lib.hologram.HologramUtil;
import com.rs.lib.input.Inputs;
import com.rs.lib.item.ItemActions;
import com.rs.lib.item.ItemRegistry;
import com.rs.lib.lang.RsLang;
import com.rs.lib.leaderboard.LeaderboardCache;
import com.rs.lib.modifier.Modifiers;
import com.rs.lib.papi.RsPapi;
import com.rs.lib.price.Prices;
import com.rs.lib.region.SelectionManager;
import com.rs.lib.reload.ReloadManager;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.snapshot.Snapshots;
import com.rs.lib.teleport.Teleports;
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
    private final ActionEngine actions;
    private final RsLang lang;
    private final Cooldowns cooldowns;
    private final EconomyBridge economy;
    private final Prices prices;
    private final Modifiers modifiers;
    private final LeaderboardCache leaderboards;
    private final RsCron cron;
    private final Teleports teleports;
    private final BossBarUtil bossbars;
    private final HologramUtil holograms;
    private final DiscordWebhook discord;
    private final RsDb db;
    private final RsCommands commands;
    private final ReloadManager reload;
    private final UpdateChecker updates;
    private final Inputs inputs;
    private final Snapshots snapshots;
    private final ItemRegistry items;
    private final SelectionManager selections;
    private final GuiManager guis;
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
        this.actions = new ActionEngine(this);
        this.lang = spec.lang() ? new RsLang(this, yml == null ? null : yml.config()) : null;
        this.cooldowns = new Cooldowns(this);
        this.economy = new EconomyBridge(this);
        this.prices = new Prices(this);
        this.modifiers = new Modifiers(this);
        this.leaderboards = new LeaderboardCache(this);
        this.cron = new RsCron(this);
        this.bossbars = new BossBarUtil(this);
        this.holograms = new HologramUtil(this);
        this.discord = new DiscordWebhook(this);
        this.db = spec.db() ? new RsDb(this, DbConfig.load(plugin,
                yml == null ? null : yml.config().getSection("database"))) : null;
        this.commands = new RsCommands(this, lang, spec.debugCommand(), spec.commandPriority());
        this.reload = new ReloadManager(this);
        reload.register(discord::resetDeadWebhooks);
        this.updates = new UpdateChecker(this);
        this.items = new ItemRegistry(this);
        items.bindActions(ItemActions.engine(this));
        if (spec.items() != null) {
            if (yml != null) {
                items.loadAll(yml.managed(spec.items()));
            } else {
                plugin.getLogger().warning("RsSpec items(\"" + spec.items() + "\") needs config(...); no items loaded.");
            }
        }
        this.selections = new SelectionManager(this);
        this.teleports = spec.teleports() ? new Teleports(this) : null;
        this.snapshots = new Snapshots(this);
        this.inputs = new Inputs(this);
        this.guis = spec.guis() ? new GuiManager(this) : null;
        if (guis != null) {
            guis.load();
        }
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

    /** Action lists ({@code [message]}, {@code [console]}, ...) and requirements. */
    public ActionEngine actions() {
        return actions;
    }

    /** Questions asked in chat. */
    public Inputs inputs() {
        return inputs;
    }

    /** Player snapshots kept on disk ({@code plugins/<Plugin>/snapshots}). */
    public Snapshots snapshots() {
        return snapshots;
    }

    /** Cooldowns per category and player. */
    public Cooldowns cooldowns() {
        return cooldowns;
    }

    /** Money: Vault, console commands or a backend of the plugin. */
    public EconomyBridge economy() {
        return economy;
    }

    /** Sell prices of items: the plugin's own list, then EssentialsX's worth.yml. */
    public Prices prices() {
        return prices;
    }

    /** Multipliers on what players earn (money, experience, drops), by permission and by provider. */
    public Modifiers modifiers() {
        return modifiers;
    }

    /** Leaderboards refreshed by async queries. */
    public LeaderboardCache leaderboards() {
        return leaderboards;
    }

    /** Jobs at calendar times ({@code "0 4 * * *"}, {@code "daily 04:00"}), on the main thread. */
    public RsCron cron() {
        return cron;
    }

    /** Teleports with a wait. Needs {@code RsSpec.builder().teleports()}. */
    public Teleports teleports() {
        if (teleports == null) {
            throw missing("teleports()", "teleports()");
        }
        return teleports;
    }

    /** Boss bars by id. */
    public BossBarUtil bossbars() {
        return bossbars;
    }

    /** Text holograms by id (never saved in the world). */
    public HologramUtil holograms() {
        return holograms;
    }

    /** Discord webhook messages, sent in the background. */
    public DiscordWebhook discord() {
        return discord;
    }

    /** The plugin's database. Needs {@code RsSpec.builder().db()}. */
    public RsDb db() {
        if (db == null) {
            throw missing("db()", "db()");
        }
        return db;
    }

    public RsCommands commands() {
        return commands;
    }

    public ReloadManager reload() {
        return reload;
    }

    /** Update checks; idle unless the spec declares {@code updates()} or the plugin calls it. */
    public UpdateChecker updates() {
        return updates;
    }

    /** Registered physical items. */
    public ItemRegistry items() {
        return items;
    }

    /** Selection wands and sessions ({@link com.rs.lib.region.Cuboid} regions). */
    public SelectionManager selections() {
        return selections;
    }

    /** Menus from {@code guis/}. Needs {@code RsSpec.builder().guis()}. */
    public GuiManager guis() {
        if (guis == null) {
            throw missing("guis()", "guis()");
        }
        return guis;
    }

    /** Reloads config, messages, items, menus, commands and every registered {@code Reloadable}. */
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

    public RsDb dbOrNull() {
        return db;
    }

    public GuiManager guisOrNull() {
        return guis;
    }

    /** Arms the update watch the spec declares; called once the plugin has enabled. */
    void armUpdates() {
        if (spec.updates()) {
            updates.watch(spec.updatesProduct() != null ? spec.updatesProduct() : updates.ownProductId());
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
        // Chat questions end before menus close, so a SHUT_DOWN callback finds no menu to reopen.
        step("chat questions", inputs::shutdown);
        if (guis != null) {
            step("menus", guis::shutdown);
        }
        step("update checks", updates::shutdown);
        step("commands", commands::unregisterAll);
        step("items", items::shutdown);
        step("selections", selections::shutdown);
        if (yml != null) {
            step("pending file writes", yml::flushAll);
        }
        if (teleports != null) {
            step("teleports", teleports::shutdown);
        }
        step("leaderboards", leaderboards::shutdown);
        step("cron jobs", cron::shutdown);
        step("snapshots", snapshots::shutdown);
        step("tasks", scheduler::cancelAll);
        step("pending main-thread results", () -> MainHops.release(plugin));
        if (db != null) {
            step("player data", db::flushPlayerCaches);
        }
        step("cooldowns", cooldowns::shutdown);
        step("modifiers", modifiers::shutdown);
        if (db != null) {
            step("database", db::shutdown);
        }
        step("soft dependencies", () -> SoftDependency.disableAllOf(plugin));
        step("actions", actions::shutdown);
        step("placeholder expansions", papi::shutdown);
        step("boss bars", bossbars::hideAll);
        step("holograms", holograms::shutdown);
        step("Discord queue", discord::drain);
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
