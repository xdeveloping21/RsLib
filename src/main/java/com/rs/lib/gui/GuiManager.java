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
 * {@code <namespace>:<file>}, and their top-level {@code *.yml} in the jar are seeded and merged
 * the same way.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Loading closes every open menu of the plugin first; loading or unloading one extra folder
 * closes only the open menus of its namespace. Menus close (without close actions)
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
        mountFolder(found, FOLDER, null, Set.of(), yml);
        Map<String, String> extra;
        synchronized (guis) {
            extra = new LinkedHashMap<>(folders);
        }
        for (Map.Entry<String, String> folder : extra.entrySet()) {
            mountFolder(found, folder.getKey(), folder.getValue(), Set.of(), yml);
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
     * {@code <namespace>:<file>}, now and on every load. Its top-level {@code *.yml} in the jar
     * are seeded and merged like {@code guis/}. Calling it again for the same folder reads its
     * files from disk again. Only the open menus of that namespace close.
     *
     * @throws IllegalArgumentException when the namespace is not a word without {@code :}, or
     *         another folder already uses it
     */
    public void loadFolder(String folder, String namespace) {
        Objects.requireNonNull(folder, "folder");
        if (namespace == null || namespace.isBlank() || namespace.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Menu namespace must be a word without ':', got '" + namespace + "'");
        }
        String path = YmlPath.normalize(folder);
        String previous;
        synchronized (guis) {
            for (Map.Entry<String, String> known : folders.entrySet()) {
                if (known.getValue().equals(namespace) && !known.getKey().equals(path)) {
                    throw new IllegalArgumentException("Menu namespace '" + namespace + "' is already used by "
                            + known.getKey() + "; give " + path + " its own namespace");
                }
            }
            previous = folders.put(path, namespace);
        }
        if (previous != null && !previous.equals(namespace)) {
            dropNamespace(previous);
        }
        rereadFolder(path);
        loadNamespace(path, namespace);
    }

    /** Stops loading {@code folder}; its open menus close and its menus are removed. Others stay open. */
    public void unloadFolder(String folder) {
        String path = YmlPath.normalize(Objects.requireNonNull(folder, "folder"));
        String namespace;
        synchronized (guis) {
            namespace = folders.remove(path);
        }
        if (namespace != null) {
            dropNamespace(namespace);
        }
    }

    /**
     * Seeds and mounts the menus of {@code folder} into {@code found}: the files the jar ships
     * as managed, the ones only on disk as plain. Ids are {@code <namespace>:<file>}, or the
     * bare file name for a null namespace; an id already in {@code found} or {@code taken} is
     * skipped with a warning.
     */
    private void mountFolder(Map<String, RsYml> found, String folder, String namespace, Set<String> taken,
            YmlManager yml) {
        Map<String, Boolean> names = GuiFiles.mounts(GuiFiles.shipped(rs.plugin().getClass(), folder, host.logger()),
                GuiFiles.onDisk(new File(rs.plugin().getDataFolder(), folder)));
        for (Map.Entry<String, Boolean> name : names.entrySet()) {
            String id = namespace == null ? name.getKey() : namespace + ":" + name.getKey();
            String path = folder + "/" + name.getKey() + GuiFiles.EXTENSION;
            if (found.containsKey(id) || taken.contains(id)) {
                warnings.warn(path, "", "the menu id " + id + " is already taken; this file is skipped");
                continue;
            }
            mount(found, id, path, yml, name.getValue());
        }
    }

    /** Reads again from disk (merging the jar copies) the mounted files under {@code path}. */
    private void rereadFolder(String path) {
        String prefix = path + "/";
        List<RsYml> mounted = new ArrayList<>();
        synchronized (guis) {
            for (RsYml file : files.values()) {
                if (file.name().startsWith(prefix)) {
                    mounted.add(file);
                }
            }
        }
        for (RsYml file : mounted) {
            try {
                file.reload();
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not read " + file.name() + " again", e);
            }
        }
    }

    /**
     * {@link #load()} for one extra folder: only the open menus of {@code namespace} close, and
     * only its menus are parsed and swapped in.
     */
    private void loadNamespace(String path, String namespace) {
        String prefix = namespace + ":";
        List<Reopen> again = reopenAfterReload ? openNow(prefix) : List.of();
        closeNamespace(prefix);
        YmlManager yml = rs == null ? null : rs.ymlOrNull();
        if (yml == null) {
            if (rs != null) {
                GuiWarnings.once(host.logger(), "RsSpec guis() needs config(\"config.yml\"); no menus are loaded.");
            }
            return;
        }
        warnings.forget(path + "/");
        Set<String> taken;
        synchronized (guis) {
            taken = new LinkedHashSet<>(guis.keySet());
        }
        taken.removeIf(id -> id.startsWith(prefix));
        Map<String, RsYml> found = new LinkedHashMap<>();
        mountFolder(found, path, namespace, taken, yml);
        Map<String, GuiDef> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, RsYml> entry : found.entrySet()) {
            GuiDef def = parse(entry.getKey(), entry.getValue());
            if (def != null) {
                parsed.put(entry.getKey(), def);
            }
        }
        List<RsYml> stale = new ArrayList<>();
        synchronized (guis) {
            guis.keySet().removeIf(id -> id.startsWith(prefix) && !parsed.containsKey(id));
            for (Map.Entry<String, GuiDef> entry : parsed.entrySet()) {
                Gui gui = guis.get(entry.getKey());
                if (gui == null) {
                    guis.put(entry.getKey(), new Gui(this, entry.getValue()));
                } else {
                    gui.def(entry.getValue());
                }
            }
            files.entrySet().removeIf(old -> {
                if (!old.getKey().startsWith(prefix)) {
                    return false;
                }
                if (!found.containsKey(old.getKey())) {
                    stale.add(old.getValue());
                }
                return true;
            });
            files.putAll(found);
        }
        for (RsYml file : stale) {
            unmount(yml, file);
        }
        reopen(again);
    }

    /** Closes the open menus of {@code namespace}, removes its menus and unmounts their files. */
    private void dropNamespace(String namespace) {
        String prefix = namespace + ":";
        closeNamespace(prefix);
        List<RsYml> gone = new ArrayList<>();
        synchronized (guis) {
            guis.keySet().removeIf(id -> id.startsWith(prefix));
            files.entrySet().removeIf(file -> file.getKey().startsWith(prefix) && gone.add(file.getValue()));
        }
        YmlManager yml = rs == null ? null : rs.ymlOrNull();
        if (yml != null) {
            for (RsYml file : gone) {
                unmount(yml, file);
            }
        }
    }

    /** Closes, without close actions, the open menus whose id starts with {@code prefix}. */
    private void closeNamespace(String prefix) {
        for (GuiSession session : openSessions()) {
            if (session.guiId().startsWith(prefix)) {
                session.close();
            }
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
        return openNow("");
    }

    /** The open menus whose id starts with {@code prefix}, to open again after a load. */
    private List<Reopen> openNow(String prefix) {
        List<Reopen> out = new ArrayList<>();
        for (GuiSession session : openSessions()) {
            if (session.guiId().startsWith(prefix)) {
                out.add(new Reopen(session.viewerId(), session.guiId(), session.page()));
            }
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
