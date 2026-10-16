package com.rs.lib.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import com.rs.lib.Rs;
import com.rs.lib.internal.LibMessages;

/**
 * Player preferences of one plugin ({@code rs().settings()}), shown together with every other
 * plugin's in one menu ({@code /rslib settings} or {@link #open(Player)}).
 *
 * <pre>
 * // onInnerEnable
 * rs().settings().register(PlayerOption.toggle("trade-requests", true)
 *         .name("&amp;aTrade requests").icon(Material.EMERALD).build());
 * deaths = rs().settings().channel(PlayerOption.toggle("death-messages", true)
 *         .name("&amp;cDeath messages").icon(Material.SKELETON_SKULL).build());
 *
 * // anywhere, any thread
 * if (!rs().settings().enabled(target, "trade-requests")) { ... }
 * deaths.send("deaths.broadcast", Ph.of("player", victim.getName()));
 * </pre>
 *
 * <ul>
 *   <li><b>Reads are memory.</b> A player's values are read in the background when they join
 *   (and, for players already online, when the plugin registers its first option) and dropped
 *   when they leave. Every read answers at once from memory, from any thread; until the join read
 *   is back, it answers the default.</li>
 *   <li><b>Only what differs from the default is stored</b>, in the plugin's database (table
 *   {@value SettingsStore#TABLE}); setting a value back to its default deletes the row, so a
 *   default changed in a later version reaches everyone who never changed it. A value set while
 *   the join read is out is newer and wins over the row.</li>
 *   <li><b>Permissions:</b> a player without an option's permission does not see it in the
 *   menu, cannot change it, and reads its default.</li>
 *   <li><b>Channels</b> ({@link #channel}) are toggles too: a message sent through one skips the
 *   players who turned it off.</li>
 * </ul>
 *
 * <p>Needs {@code RsSpec.builder().db()}: registering an option without it throws. Register in
 * {@code onInnerEnable}; registering a key again replaces it.</p>
 */
public final class SettingsManager {

    /** Opens the common menu with {@code /rslib settings}. */
    public static final String PERMISSION = "rslib.settings";

    /** Every plugin's settings, in the order they registered their first option; the menu lists them so. */
    static final List<SettingsManager> LIVE = new CopyOnWriteArrayList<>();
    private static final Listener SHARED = new SharedListener();

    private final SettingsHost host;
    private final Object registryLock = new Object();
    /** Copy-on-write under {@link #registryLock}: key to option, in registration order. */
    private volatile Map<String, PlayerOption> options = Map.of();
    private final Map<UUID, Values> players = new ConcurrentHashMap<>();
    private volatile SettingsStore store;
    private volatile boolean closed;

    /** One online player's stored values (only those that differ from the default). */
    static final class Values {
        final Map<String, String> stored = new ConcurrentHashMap<>();
        /** Settings changed since the join read started; the read does not overwrite them. Guarded by this. */
        final Set<String> touched = ConcurrentHashMap.newKeySet();
        volatile boolean loaded;
    }

    /** Created by the context. */
    public SettingsManager(Rs rs) {
        this(SettingsHost.of(Objects.requireNonNull(rs, "rs")));
        rs.onQuit(player -> forget(player.getUniqueId()));
    }

    SettingsManager(SettingsHost host) {
        this.host = host;
    }

    /** RsLib's listener for joins and the settings menu; RsLib registers it once. */
    public static Listener sharedListener() {
        return SHARED;
    }

    /**
     * Opens the menu with every plugin's settings in RsLib's own language ({@code lang} in its
     * config.yml): {@code /rslib settings}.
     */
    public static void openCommon(Player player) {
        SettingsMenu.open(Objects.requireNonNull(player, "player"), LibMessages::text);
    }

    /** Closes every open settings menu; RsLib calls it when it stops. */
    public static void closeMenus() {
        SettingsMenu.closeAll();
    }

    // ------------------------------------------------------------------ registering

    /**
     * Registers {@code option}, replacing one with the same key. The first registration creates
     * the table and reads the values of the players already online.
     *
     * @throws UnsupportedOperationException when the plugin did not declare {@code db()}
     * @throws IllegalStateException         when the plugin is shutting down
     */
    public PlayerOption register(PlayerOption option) {
        Objects.requireNonNull(option, "option");
        if (closed) {
            throw new IllegalStateException(host.owner() + " is shutting down; no setting can be registered");
        }
        boolean first = false;
        synchronized (registryLock) {
            if (store == null) {
                store = new SettingsStore(host.db(), host.owner(), host.logger());
                first = true;
            }
            Map<String, PlayerOption> copy = new LinkedHashMap<>(options);
            copy.put(option.key(), option);
            options = Collections.unmodifiableMap(copy);
        }
        if (first) {
            if (!LIVE.contains(this)) {
                LIVE.add(this);
            }
            for (Player player : List.copyOf(host.online())) {
                load(player.getUniqueId());
            }
        }
        return option;
    }

    /**
     * Registers {@code toggle} and returns a channel tied to it: a message sent through the
     * channel reaches only the players who have the toggle on.
     *
     * @throws IllegalArgumentException when {@code toggle} is not a {@link PlayerOption.Kind#TOGGLE}
     */
    public BroadcastChannel channel(PlayerOption toggle) {
        Objects.requireNonNull(toggle, "toggle");
        if (toggle.kind() != PlayerOption.Kind.TOGGLE) {
            throw new IllegalArgumentException("A channel needs a toggle option: " + toggle.key());
        }
        register(toggle);
        return new BroadcastChannel(this, host, toggle.key());
    }

    /** The registered options, in registration order. */
    public List<PlayerOption> options() {
        return List.copyOf(options.values());
    }

    /** The option registered as {@code key}, or null. */
    public PlayerOption option(String key) {
        return key == null ? null : options.get(key);
    }

    // ------------------------------------------------------------------ reading

    /**
     * The value of {@code key} for {@code player} as text; the default until their values are
     * read, and always for a player without the option's permission.
     *
     * @throws IllegalArgumentException when no option has that key
     */
    public String value(Player player, String key) {
        PlayerOption option = require(key);
        return option.allows(player) ? current(player.getUniqueId(), option) : option.defaultValue();
    }

    /** {@link #value(Player, String)} by id, without the permission check (the player may be offline: then the default). */
    public String value(UUID player, String key) {
        return current(Objects.requireNonNull(player, "player"), require(key));
    }

    /** Whether the toggle {@code key} is on for {@code player}. */
    public boolean enabled(Player player, String key) {
        return Boolean.parseBoolean(value(player, require(key, PlayerOption.Kind.TOGGLE).key()));
    }

    /** The number {@code key} for {@code player}. */
    public int number(Player player, String key) {
        return Integer.parseInt(value(player, require(key, PlayerOption.Kind.NUMBER).key()));
    }

    /** Whether the join read of {@code player} is back. */
    public boolean isLoaded(UUID player) {
        Values values = player == null ? null : players.get(player);
        return values != null && values.loaded;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Sets {@code key} for {@code player} now and saves it in the background. Returns false, and
     * changes nothing, when the key is unknown, the option cannot hold {@code value} or the player
     * lacks its permission.
     */
    public boolean set(Player player, String key, String value) {
        PlayerOption option = option(key);
        if (option == null || !option.allows(player)) {
            return false;
        }
        return apply(player.getUniqueId(), option, value);
    }

    /**
     * {@link #set(Player, String, String)} by id, for a player who may be offline (imports,
     * admin commands); no permission check.
     */
    public boolean set(UUID player, String key, String value) {
        PlayerOption option = option(key);
        return option != null && apply(Objects.requireNonNull(player, "player"), option, value);
    }

    /** One menu click: flips a toggle, moves a choice or a number; false when nothing changed. */
    public boolean click(Player player, String key, boolean forward) {
        PlayerOption option = option(key);
        if (option == null || !option.allows(player)) {
            return false;
        }
        String now = current(player.getUniqueId(), option);
        String next = option.next(now, forward);
        return !next.equals(now) && apply(player.getUniqueId(), option, next);
    }

    // ------------------------------------------------------------------ menu

    /** Opens the menu with every plugin's settings, in this plugin's language. */
    public void open(Player player) {
        SettingsMenu.open(Objects.requireNonNull(player, "player"), host::text);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Waits for the pending writes and forgets everything; called by the context before the database closes. */
    public void shutdown() {
        closed = true;
        LIVE.remove(this);
        SettingsStore current = store;
        if (current != null) {
            current.close();
        }
        players.clear();
    }

    // ------------------------------------------------------------------ internals

    String owner() {
        return host.owner();
    }

    boolean closed() {
        return closed;
    }

    /** Completes after every read and write queued so far; for tests. */
    CompletableFuture<?> idle() {
        SettingsStore current = store;
        return current == null ? CompletableFuture.completedFuture(null) : current.idle();
    }

    /** Starts the join read of {@code player} unless it is out or done. */
    void load(UUID player) {
        SettingsStore current = store;
        if (current == null || closed || host.shuttingDown()) {
            return;
        }
        Values values = new Values();
        if (players.putIfAbsent(player, values) != null) {
            return;
        }
        current.load(player).whenComplete((rows, error) -> {
            if (error != null) {
                host.logger().log(Level.WARNING, "Could not read the settings of player " + player
                        + "; they keep their defaults", error);
                return;
            }
            synchronized (values) {
                for (Map.Entry<String, String> row : rows.entrySet()) {
                    if (!values.touched.contains(row.getKey())) {
                        values.stored.put(row.getKey(), row.getValue());
                    }
                }
                values.loaded = true;
            }
        });
    }

    /** The player left: their values are dropped (every change is already saved or queued). */
    void forget(UUID player) {
        players.remove(player);
    }

    private String current(UUID player, PlayerOption option) {
        Values values = players.get(player);
        String stored = values == null ? null : values.stored.get(option.key());
        String valid = option.normalize(stored);
        return valid != null ? valid : option.defaultValue();
    }

    private boolean apply(UUID player, PlayerOption option, String raw) {
        String value = option.normalize(raw);
        SettingsStore current = store;
        if (value == null || current == null || closed) {
            return false;
        }
        boolean isDefault = value.equals(option.defaultValue());
        Values values = players.get(player);
        if (values != null) {
            synchronized (values) {
                values.touched.add(option.key());
                if (isDefault) {
                    values.stored.remove(option.key());
                } else {
                    values.stored.put(option.key(), value);
                }
            }
        }
        current.write(player, option.key(), isDefault ? null : value);
        return true;
    }

    private PlayerOption require(String key) {
        PlayerOption option = option(Objects.requireNonNull(key, "key"));
        if (option == null) {
            throw new IllegalArgumentException(host.owner() + " has no setting '" + key + "'");
        }
        return option;
    }

    private PlayerOption require(String key, PlayerOption.Kind kind) {
        PlayerOption option = require(key);
        if (option.kind() != kind) {
            throw new IllegalArgumentException("Setting '" + key + "' of " + host.owner() + " is a "
                    + option.kind() + ", not a " + kind);
        }
        return option;
    }

    /** The options {@code player} may see, of every plugin, in menu order. */
    static List<SettingsMenu.Entry> entriesFor(Player player) {
        List<SettingsMenu.Entry> entries = new ArrayList<>();
        for (SettingsManager manager : LIVE) {
            if (manager.closed) {
                continue;
            }
            for (PlayerOption option : manager.options.values()) {
                if (option.allows(player)) {
                    entries.add(new SettingsMenu.Entry(manager, option));
                }
            }
        }
        return entries;
    }

    /** Joins (every plugin's settings) and the clicks of the settings menu. */
    private static final class SharedListener implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onJoin(PlayerJoinEvent event) {
            UUID player = event.getPlayer().getUniqueId();
            for (SettingsManager manager : LIVE) {
                try {
                    manager.load(player);
                } catch (RuntimeException e) {
                    manager.host.logger().log(Level.WARNING, "Could not start reading the settings of " + player, e);
                }
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onClick(InventoryClickEvent event) {
            SettingsMenu.handleClick(event);
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onDrag(InventoryDragEvent event) {
            SettingsMenu.handleDrag(event);
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            SettingsMenu.handleClose(event);
        }
    }
}
