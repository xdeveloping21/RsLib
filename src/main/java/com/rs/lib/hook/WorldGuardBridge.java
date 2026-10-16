package com.rs.lib.hook;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

/**
 * WorldGuard regions and flags without depending on WorldGuard: everything goes through
 * reflection, so RsLib and the plugins asking load and work on a server without it.
 *
 * <pre>
 * if (WorldGuardBridge.regionIds(player.getLocation()).contains("spawn")) { ... }
 * if (!WorldGuardBridge.allows(player, location, "pvp")) { return; }
 * </pre>
 *
 * <ul>
 *   <li>Without WorldGuard (not installed, disabled, or a version whose API does not match)
 *   every location is outside every region and every flag allows. A failure is reported once
 *   in the console and the answers fall back the same way.</li>
 *   <li>Region ids are WorldGuard's (lower case), highest priority first, and belong to the
 *   world of the location asked about. The global region is not listed.</li>
 *   <li>A flag allows unless WorldGuard says {@code DENY} for it at that location: a flag no
 *   region sets, an unknown flag name and a flag that is not a state flag all allow.</li>
 *   <li>The answer about WorldGuard being there is kept until it is enabled or disabled again
 *   (RsLib listens for both).</li>
 * </ul>
 *
 * <p>Ask from the thread that owns the location (the main thread on Paper).</p>
 */
public final class WorldGuardBridge {

    private static final Logger LOG = Logger.getLogger("RsLib");
    private static final String PLUGIN = "WorldGuard";
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Listener LISTENER = new Watcher();

    /** Null while not looked up; {@link Access#NONE} when WorldGuard cannot be used. */
    private static volatile Access access;

    private WorldGuardBridge() {
    }

    /** True when WorldGuard is installed, enabled and its API answers. */
    public static boolean available() {
        return access() != Access.NONE;
    }

    /** The ids of the WorldGuard regions at {@code location}, highest priority first; empty without WorldGuard. */
    public static List<String> regionIds(Location location) {
        Access current = access();
        if (current == Access.NONE || !hasWorld(location)) {
            return List.of();
        }
        try {
            return current.regionIds(location);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed("list the regions", e);
            return List.of();
        }
    }

    /** True when {@code location} is inside the WorldGuard region {@code id} (ignoring case); false without WorldGuard. */
    public static boolean isIn(Location location, String id) {
        if (id == null) {
            return false;
        }
        String wanted = id.toLowerCase(Locale.ROOT);
        for (String found : regionIds(location)) {
            if (found.toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Whether state flag {@code flag} allows at {@code location} for nobody in particular; true without WorldGuard. */
    public static boolean allows(Location location, String flag) {
        return allows(null, location, flag);
    }

    /**
     * Whether state flag {@code flag} allows at {@code location} for {@code player} (their
     * membership counts, as WorldGuard judges it; null for nobody). True without WorldGuard.
     */
    public static boolean allows(Player player, Location location, String flag) {
        Access current = access();
        if (current == Access.NONE || !hasWorld(location) || flag == null) {
            return true;
        }
        try {
            return current.allows(player, location, flag);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed("read the flag " + flag, e);
            return true;
        }
    }

    /** Keeps the answer in step with WorldGuard being enabled or disabled; RsLib registers it. */
    public static Listener listener() {
        return LISTENER;
    }

    /** Forgets the answer; the next question looks WorldGuard up again. */
    public static void invalidate() {
        access = null;
    }

    // ------------------------------------------------------------------ internals

    private static boolean hasWorld(Location location) {
        if (location == null) {
            return false;
        }
        try {
            return location.getWorld() != null;
        } catch (IllegalArgumentException unloaded) {
            return false;
        }
    }

    private static Access access() {
        Access current = access;
        if (current == null) {
            current = lookUp();
            access = current;
        }
        return current;
    }

    private static Access lookUp() {
        if (Bukkit.getServer() == null) {
            return Access.NONE;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (plugin == null || !plugin.isEnabled()) {
            return Access.NONE;
        }
        return connect(plugin.getClass().getClassLoader());
    }

    /** The access through WorldGuard's classes as {@code loader} sees them, or {@link Access#NONE}. */
    static Access connect(ClassLoader loader) {
        try {
            return new Access(loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce("connect", "[RsLib] WorldGuard is installed but its API could not be reached ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "); WorldGuard regions are ignored and every flag allows.", null);
            return Access.NONE;
        }
    }

    /** Uses {@code loader}'s WorldGuard classes from now on (null: none); for tests. */
    static void use(ClassLoader loader) {
        access = loader == null ? Access.NONE : connect(loader);
    }

    private static void failed(String what, Throwable e) {
        Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
        warnOnce("call:" + what, "[RsLib] WorldGuard could not " + what + " (" + cause.getClass().getSimpleName()
                + "); answering as if there were no region.", cause);
    }

    private static void warnOnce(String key, String message, Throwable cause) {
        if (WARNED.add(key)) {
            LOG.log(Level.WARNING, message, cause);
        }
    }

    /** The warnings given so far; for tests. */
    static Set<String> warned() {
        return WARNED;
    }

    /** The methods of WorldGuard 7 that the questions need, found once. */
    static final class Access {

        static final Access NONE = new Access();

        private final Method getInstance;
        private final Method getPlatform;
        private final Method getRegionContainer;
        private final Method createQuery;
        private final Method adapt;
        private final Method getApplicableRegions;
        private final Method getId;
        private final Method getFlagRegistry;
        private final Method getFlag;
        private final Class<?> stateFlag;
        private final Method queryState;
        private final Method pluginInstance;
        private final Method wrapPlayer;

        private Access() {
            getInstance = getPlatform = getRegionContainer = createQuery = adapt = getApplicableRegions = getId = null;
            getFlagRegistry = getFlag = queryState = pluginInstance = wrapPlayer = null;
            stateFlag = null;
        }

        /** Methods are reached through the declared return types, so implementation classes never matter. */
        Access(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> worldGuard = Class.forName("com.sk89q.worldguard.WorldGuard", true, loader);
            getInstance = worldGuard.getMethod("getInstance");
            getPlatform = worldGuard.getMethod("getPlatform");
            getRegionContainer = getPlatform.getReturnType().getMethod("getRegionContainer");
            createQuery = getRegionContainer.getReturnType().getMethod("createQuery");
            adapt = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, loader)
                    .getMethod("adapt", Location.class);
            Class<?> query = createQuery.getReturnType();
            getApplicableRegions = query.getMethod("getApplicableRegions", adapt.getReturnType());
            getId = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, loader)
                    .getMethod("getId");
            getFlagRegistry = worldGuard.getMethod("getFlagRegistry");
            getFlag = getFlagRegistry.getReturnType().getMethod("get", String.class);
            stateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", true, loader);
            Class<?> associable = Class.forName("com.sk89q.worldguard.protection.association.RegionAssociable",
                    true, loader);
            queryState = query.getMethod("queryState", adapt.getReturnType(), associable,
                    Array.newInstance(stateFlag, 0).getClass());
            Class<?> plugin = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin", true, loader);
            pluginInstance = plugin.getMethod("inst");
            wrapPlayer = plugin.getMethod("wrapPlayer", Player.class);
        }

        private Object query() throws ReflectiveOperationException {
            Object platform = getPlatform.invoke(getInstance.invoke(null));
            return createQuery.invoke(getRegionContainer.invoke(platform));
        }

        List<String> regionIds(Location location) throws ReflectiveOperationException {
            Object set = getApplicableRegions.invoke(query(), adapt.invoke(null, location));
            List<String> ids = new ArrayList<>();
            if (set instanceof Iterable<?> regions) {
                for (Object region : regions) {
                    ids.add((String) getId.invoke(region));
                }
            }
            return List.copyOf(ids);
        }

        boolean allows(Player player, Location location, String flagName) throws ReflectiveOperationException {
            Object flag = getFlag.invoke(getFlagRegistry.invoke(getInstance.invoke(null)), flagName);
            if (flag == null || !stateFlag.isInstance(flag)) {
                warnOnce("flag:" + flagName, "[RsLib] '" + flagName + "' is not a WorldGuard state flag on this"
                        + " server; it always allows.", null);
                return true;
            }
            Object flags = Array.newInstance(stateFlag, 1);
            Array.set(flags, 0, flag);
            Object subject = player == null ? null : wrapPlayer.invoke(pluginInstance.invoke(null), player);
            Object state = queryState.invoke(query(), adapt.invoke(null, location), subject, flags);
            return !(state instanceof Enum<?> value && value.name().equals("DENY"));
        }
    }

    /** WorldGuard coming or going: the next question looks it up again. */
    private static final class Watcher implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnable(PluginEnableEvent event) {
            if (event.getPlugin().getName().equals(PLUGIN)) {
                invalidate();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDisable(PluginDisableEvent event) {
            if (event.getPlugin().getName().equals(PLUGIN)) {
                // WorldGuard still reports itself enabled during its own disable.
                access = Access.NONE;
            }
        }
    }
}
