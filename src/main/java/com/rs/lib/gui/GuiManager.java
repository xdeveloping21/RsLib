package com.rs.lib.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import com.rs.lib.Rs;
import com.rs.lib.action.ActionHandler;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlManager;

/**
 * The menus of one plugin ({@code rs().guis()}, declared with {@code RsSpec.builder().guis()}).
 *
 * <h2>Files</h2>
 * <p>Each {@code plugins/<Plugin>/guis/<id>.yml} is one menu; its id is the file name without
 * {@code .yml}. The {@code guis/*.yml} files directly inside the plugin's jar are seeded when
 * missing and merged with the jar copy (new keys added, edits kept) on every start and reload.
 * Files are read in alphabetical order, leaving out the backups the merge makes
 * ({@code old-<name>-<date>.yml}). Every menu loads on its own: a broken file is reported and
 * skipped, and every value written wrong is reported once per load with the file and the key,
 * then falls back to its default. Extra folders ({@link #loadFolder}) give ids
 * {@code <namespace>:<file>}.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Loading closes every open menu of the plugin first. Menus close (without close actions)
 * when their player leaves, on reload and when the plugin stops; nothing opens while the plugin
 * shuts down. With {@link #reopenAfterReload(boolean)} the menus open at a reload come back at
 * the same page for players still online.</p>
 *
 * <h2>Anti-theft</h2>
 * <p>Every stack a menu puts in its inventory carries {@code <plugin>:}{@value #ITEM_TAG} in its
 * persistent data. RsLib's shared listener deletes any stamped stack that leaves a menu (click,
 * number key, off-hand key, drag, use, swap, drop, death, item spawn); stacks with
 * {@value #LEGACY_ITEM_TAG}, the stamp SnLib menus use, are treated the same way outside menus.</p>
 */
public final class GuiManager {

    /** Name of the persistent data key stamped on every menu stack, under the plugin's namespace. */
    public static final String ITEM_TAG = "rslib_gui_item";
    /** The key name SnLib menus stamp; recognised so their stacks cannot leave menus either. */
    public static final String LEGACY_ITEM_TAG = "snlib_gui_item";
    /** The folder of the plugin's menus. */
    public static final String FOLDER = "guis";

    private static final Set<GuiManager> LIVE = ConcurrentHashMap.newKeySet();

    private final Rs rs;
    private final GuiHost host;
    private final GuiWarnings warnings;
    private final Map<String, Gui> guis = new LinkedHashMap<>();
    private final Map<String, RsYml> files = new LinkedHashMap<>();
    private final Map<String, String> folders = new LinkedHashMap<>();
    private final Set<GuiSession> open = ConcurrentHashMap.newKeySet();
    private volatile boolean shutDown;
    private volatile boolean reopenAfterReload;
    private boolean emptyCheckDone;

    /** Created by the context when the spec declares {@code guis()}. */
    public GuiManager(Rs rs) {
        this.rs = Objects.requireNonNull(rs, "rs");
        this.host = GuiHost.of(rs);
        this.warnings = new GuiWarnings(host.logger());
        rs.actions().menuOpener((player, id, context) -> {
            Gui gui = get(id.trim());
            if (gui == null) {
                return false;
            }
            gui.open(player);
            return true;
        });
        rs.onQuit(this::quit);
        LIVE.add(this);
    }

    /** Without a plugin, for tests: menus are added with {@link #add(GuiDef)}. */
    GuiManager(GuiHost host) {
        this.rs = null;
        this.host = host;
        this.warnings = new GuiWarnings(host.logger());
    }

    /** The listener RsLib registers once for the clicks, drags and closes of every plugin's menus. */
    public static Listener sharedListener() {
        return new GuiListener();
    }

    /** The anti-theft listener RsLib registers once for the whole server. */
    public static Listener protectionListener() {
        return new GuiProtection();
    }

    // ------------------------------------------------------------------ loading

    /**
     * Seeds and mounts {@code guis/} and the extra folders, then parses every menu from the files
     * as they were last read (a reload of the plugin reads them first). Closes every open menu of
     * the plugin first. Each menu stays the same {@link Gui} object across loads.
     */
    public void load() {
        if (rs == null) {
            return;
        }
        YmlManager yml = rs.ymlOrNull();
        if (yml == null) {
            GuiWarnings.once(host.logger(), "RsSpec guis() needs config(\"config.yml\"); no menus are loaded.");
            return;
        }
        List<Reopen> again = reopenAfterReload ? openNow() : List.of();
        warnings.forget();
        closeAll();
        Map<String, RsYml> found = new LinkedHashMap<>();
        Set<String> shipped = new LinkedHashSet<>(GuiFiles.shipped(rs.plugin().getClass(), FOLDER, host.logger()));
        for (String name : shipped) {
            mount(found, name, FOLDER + "/" + name + GuiFiles.EXTENSION, yml, true);
        }
        for (String name : GuiFiles.onDisk(new File(rs.plugin().getDataFolder(), FOLDER))) {
            if (!shipped.contains(name)) {
                mount(found, name, FOLDER + "/" + name + GuiFiles.EXTENSION, yml, false);
            }
        }
        Map<String, String> extra;
        synchronized (guis) {
            extra = new LinkedHashMap<>(folders);
        }
        for (Map.Entry<String, String> folder : extra.entrySet()) {
            for (String name : GuiFiles.onDisk(new File(rs.plugin().getDataFolder(), folder.getKey()))) {
                String id = folder.getValue() + ":" + name;
                if (found.containsKey(id)) {
                    warnings.warn(folder.getKey() + "/" + name + GuiFiles.EXTENSION, "", "the menu id " + id
                            + " is already taken; this file is skipped");
                    continue;
                }
                mount(found, id, folder.getKey() + "/" + name + GuiFiles.EXTENSION, yml, false);
            }
        }
        Map<String, GuiDef> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, RsYml> entry : found.entrySet()) {
            GuiDef def = parse(entry.getKey(), entry.getValue());
            if (def != null) {
                parsed.put(entry.getKey(), def);
            }
        }
        install(parsed);
        synchronized (guis) {
            for (Map.Entry<String, RsYml> old : files.entrySet()) {
                if (!found.containsKey(old.getKey())) {
                    unmount(yml, old.getValue());
                }
            }
            files.clear();
            files.putAll(found);
        }
        reopen(again);
        checkNotEmpty();
    }

    /** Reads every menu file from disk again (merging the jar copies), then {@link #load()}. */
    public void reload() {
        List<RsYml> mounted;
        synchronized (guis) {
            mounted = new ArrayList<>(files.values());
        }
        for (RsYml file : mounted) {
            try {
                file.reload();
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not read " + file.name() + " again", e);
            }
        }
        load();
    }

    /**
     * Also loads the menus in {@code folder} (relative to the plugin folder), with ids
     * {@code <namespace>:<file>}, now and on every load. Each folder needs its own namespace; an
     * id that is already taken is skipped with a warning (the first one wins).
     */
    public void loadFolder(String folder, String namespace) {
        Objects.requireNonNull(folder, "folder");
        if (namespace == null || namespace.isBlank() || namespace.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Menu namespace must be a word without ':', got '" + namespace + "'");
        }
        String path = YmlPath.normalize(folder);
        synchronized (guis) {
            for (Map.Entry<String, String> known : folders.entrySet()) {
                if (known.getValue().equals(namespace) && !known.getKey().equals(path)) {
                    host.logger().warning("Menu namespace '" + namespace + "' is already used by " + known.getKey()
                            + "; " + path + " is not loaded.");
                    return;
                }
            }
            folders.put(path, namespace);
        }
        load();
    }

    /** Stops loading {@code folder}; its menus close and are removed. */
    public void unloadFolder(String folder) {
        String path = YmlPath.normalize(Objects.requireNonNull(folder, "folder"));
        boolean removed;
        synchronized (guis) {
            removed = folders.remove(path) != null;
        }
        if (removed) {
            load();
        }
    }

    private void mount(Map<String, RsYml> found, String id, String path, YmlManager yml, boolean shipped) {
        try {
            found.put(id, shipped ? yml.managed(path) : yml.load(path));
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not read menu file " + path, e);
        }
    }

    private void unmount(YmlManager yml, RsYml file) {
        try {
            yml.unmount(file.name());
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }

    private GuiDef parse(String id, RsYml file) {
        try {
            return GuiParser.parse(id, file.name(), file.getSection(""), ItemAppearance.TextResolver.of(file),
                    rs.actions(), (path, message) -> warnings.warn(file.name(), path, message), host.logger());
        } catch (RuntimeException | LinkageError broken) {
            host.logger().log(Level.WARNING, "Menu " + file.name() + " could not be loaded; skipped", broken);
            return null;
        }
    }

    /** Swaps in {@code parsed}: known menus keep their {@link Gui}, missing ones go away. */
    private void install(Map<String, GuiDef> parsed) {
        synchronized (guis) {
            guis.keySet().retainAll(parsed.keySet());
            for (Map.Entry<String, GuiDef> entry : parsed.entrySet()) {
                Gui gui = guis.get(entry.getKey());
                if (gui == null) {
                    guis.put(entry.getKey(), new Gui(this, entry.getValue()));
                } else {
                    gui.def(entry.getValue());
                }
            }
        }
    }

    /** Adds or replaces one menu (tests and plugins that build menus in code). */
    Gui add(GuiDef def) {
        synchronized (guis) {
            Gui gui = guis.get(def.id());
            if (gui == null) {
                gui = new Gui(this, def);
                guis.put(def.id(), gui);
            } else {
                gui.def(def);
            }
            return gui;
        }
    }

    /** At start, one tick later (so folders registered in onEnable count): warns when there is no menu at all. */
    void checkNotEmpty() {
        if (emptyCheckDone) {
            return;
        }
        emptyCheckDone = true;
        try {
            host.nextTick(() -> {
                if (!shutDown && ids().isEmpty()) {
                    host.logger().warning("No menus were loaded: " + FOLDER + "/ has no .yml files.");
                }
            });
        } catch (RuntimeException notEnabled) {
            // Constructed outside a running plugin.
        }
    }

    // ------------------------------------------------------------------ reading

    /** The menu {@code id} ({@code ns:id} for extra folders), or null. */
    public Gui get(String id) {
        if (id == null) {
            return null;
        }
        synchronized (guis) {
            return guis.get(id);
        }
    }

    /** Every loaded menu id, in load order. */
    public List<String> ids() {
        synchronized (guis) {
            return new ArrayList<>(guis.keySet());
        }
    }

    /** Adds the action {@code [tag]} to the plugin's action engine (same as {@code rs().actions().register}). */
    public void registerAction(String tag, ActionHandler handler) {
        host.actions().register(tag, handler);
    }

    /** Every open session of the plugin's menus. */
    public List<GuiSession> openSessions() {
        return new ArrayList<>(open);
    }

    /** Closes every open menu of this plugin, without close actions. */
    public void closeAll() {
        for (GuiSession session : openSessions()) {
            session.close();
        }
    }

    /** Closes every open menu of {@code owner}, whichever plugin's manager is asked. */
    public void closeAll(Plugin owner) {
        for (GuiManager manager : LIVE) {
            if (manager.host.plugin() == owner) {
                manager.closeAll();
            }
        }
    }

    /**
     * With true, a {@link #load()} (a reload) opens again, at the same page, the menus that were
     * open, for players still online and menus that still exist. Binds are not kept: a menu
     * that needs the plugin's data should be reopened by the plugin instead. Off by default.
     */
    public void reopenAfterReload(boolean reopen) {
        this.reopenAfterReload = reopen;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Closes every menu and stops opening new ones; called by the context when the plugin stops. */
    public void shutdown() {
        shutDown = true;
        closeAll();
        LIVE.remove(this);
    }

    void quit(Player player) {
        UUID id = player.getUniqueId();
        for (GuiSession session : openSessions()) {
            if (session.viewerId().equals(id)) {
                session.close();
            }
        }
    }

    private record Reopen(UUID player, String id, int page) {
    }

    private List<Reopen> openNow() {
        List<Reopen> out = new ArrayList<>();
        for (GuiSession session : openSessions()) {
            out.add(new Reopen(session.viewerId(), session.guiId(), session.page()));
        }
        return out;
    }

    private void reopen(List<Reopen> again) {
        for (Reopen entry : again) {
            Player player = Bukkit.getPlayer(entry.player());
            Gui gui = get(entry.id());
            if (player != null && player.isOnline() && gui != null) {
                gui.open(player, entry.page());
            }
        }
    }

    // ------------------------------------------------------------------ package

    GuiHost host() {
        return host;
    }

    GuiWarnings warnings() {
        return warnings;
    }

    boolean refusesOpening() {
        return shutDown || host.shuttingDown();
    }

    void track(GuiSession session) {
        open.add(session);
    }

    void untrack(GuiSession session) {
        open.remove(session);
    }

    /** Folder paths as {@link YmlManager} normalises them. */
    private static final class YmlPath {
        static String normalize(String folder) {
            String path = folder.trim().replace('\\', '/');
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.isEmpty() || path.contains("..")) {
                throw new IllegalArgumentException("Menu folder '" + folder + "' is not inside the plugin folder");
            }
            return path;
        }
    }
}
