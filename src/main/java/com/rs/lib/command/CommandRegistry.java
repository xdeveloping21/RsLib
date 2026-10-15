package com.rs.lib.command;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.RsLibPlugin;
import com.rs.lib.compat.RsVersion;

/**
 * Puts roots in the server's command map and takes them out, for every plugin at once.
 *
 * <p>Paper's map of known commands forwards to Brigadier only through {@code get}, {@code put}
 * and {@code remove(key, value)}, so those are the only calls made on it. Everything here runs
 * on the main thread; {@link RsCommands} moves calls from other threads there first.</p>
 */
final class CommandRegistry {

    /** A command that held a key before a root with priority took it. */
    record Displaced(String pluginName, WeakReference<Command> command) {
    }

    private static final Set<RootCommand> ROOTS = new LinkedHashSet<>();
    /** The plugin whose scheduler holds the pending publish, or null when none is pending. */
    private static volatile Plugin publisher;
    private static volatile boolean mapChanged;

    private CommandRegistry() {
    }

    private static CommandMap map() {
        return Bukkit.getCommandMap();
    }

    private static Map<String, Command> known() {
        return map().getKnownCommands();
    }

    static String prefix(RootCommand root) {
        return root.owner().getName().toLowerCase(Locale.ROOT) + ":";
    }

    /** The command at {@code key} in the server's command map, or null. */
    static Command holderOf(String key) {
        return known().get(key);
    }

    // ---------------------------------------------------------------- connect and disconnect

    static void connect(RootCommand root) {
        if (root.registered) {
            refresh(root);
            return;
        }
        String name = root.getName();
        PluginCommand declared = declaredCommand(root);
        if (declared != null) {
            root.pluginCommand = declared;
            root.adapter = new Adapter(root);
            declared.setExecutor(root.adapter);
            declared.setTabCompleter(root.adapter);
            Command occupant = known().get(name);
            if (occupant == root) {
                root.holdsName = true;
            } else if (root.priority && occupant != null && occupant != declared && takeable(root, occupant)) {
                root.holdsName = take(root, name, occupant);
            }
        } else {
            root.holdsName = claim(root, name, root.priority);
            boolean prefixed = claim(root, prefix(root) + name, false);
            if (!root.dynamic) {
                root.commands.logOnce("undeclared:" + name, Level.WARNING, "/" + name
                        + " is not declared in plugin.yml; add it under 'commands:' so the server lists it as "
                        + root.owner().getName() + "'s.");
            }
            if (!root.holdsName) {
                reportLostName(root, name, prefixed);
            }
            root.register(map());
        }
        root.registered = true;
        ROOTS.add(root);
        reconcileAliases(root);
        changed();
    }

    /** The plugin.yml command named exactly like the root (not one of its aliases), or null. */
    private static PluginCommand declaredCommand(RootCommand root) {
        PluginCommand declared;
        try {
            declared = root.owner().getCommand(root.getName());
        } catch (UnsupportedOperationException pluginWithoutPluginYml) {
            return null;
        }
        if (declared == null || declared.getPlugin() != root.owner()
                || !declared.getName().equalsIgnoreCase(root.getName())) {
            return null;
        }
        return declared;
    }

    /** The root a plugin.yml command hands over to, or null. */
    static RootCommand rootBehind(PluginCommand command) {
        return command.getExecutor() instanceof Adapter adapter ? adapter.root : null;
    }

    /** Same instance registered again (a reload): aliases are read again and taken keys recovered. */
    private static void refresh(RootCommand root) {
        reconcileAliases(root);
        if (root.priority) {
            recover(root);
        }
    }

    static void disconnect(RootCommand root) {
        if (detach(root)) {
            reconcileSiblings(root.owner());
        }
    }

    /** Takes out every root in {@code roots}, then lets the siblings left take the freed keys once. */
    static void disconnectAll(Collection<RootCommand> roots) {
        Set<JavaPlugin> owners = new LinkedHashSet<>();
        for (RootCommand root : roots) {
            if (detach(root)) {
                owners.add(root.owner());
            }
        }
        for (JavaPlugin owner : owners) {
            reconcileSiblings(owner);
        }
    }

    /** Takes the root and its keys out of the map; false when it was not in it. */
    private static boolean detach(RootCommand root) {
        if (!root.registered) {
            return false;
        }
        String prefix = prefix(root);
        for (String alias : List.copyOf(root.activeAliases)) {
            release(root, alias);
            release(root, prefix + alias);
        }
        for (String alias : root.prefixedOnly) {
            release(root, prefix + alias);
        }
        if (root.holdsName) {
            release(root, root.getName());
        }
        release(root, prefix + root.getName());
        root.unregister(map());
        if (root.pluginCommand != null && root.pluginCommand.getExecutor() == root.adapter) {
            root.pluginCommand.setExecutor(null);
            root.pluginCommand.setTabCompleter(null);
        }
        root.pluginCommand = null;
        root.adapter = null;
        root.holdsName = false;
        root.activeAliases.clear();
        root.prefixedOnly.clear();
        root.displaced.clear();
        root.wantedAliases = Set.of();
        root.registered = false;
        ROOTS.remove(root);
        changed();
        return true;
    }

    /** The roots of {@code owner} still registered take the keys its other roots let go. */
    private static void reconcileSiblings(JavaPlugin owner) {
        if (!owner.isEnabled()) {
            // Shutting down: its other roots are about to go too.
            return;
        }
        for (RootCommand sibling : List.copyOf(ROOTS)) {
            if (sibling.owner() == owner) {
                reconcileAliases(sibling);
                if (sibling.priority) {
                    recover(sibling);
                }
            }
        }
    }

    // ---------------------------------------------------------------- aliases

    /** Aliases the root should have now: from config when set, else the builder's. */
    private static Set<String> desiredAliases(RootCommand root) {
        Collection<String> source = null;
        if (root.aliasSupplier != null) {
            try {
                source = root.aliasSupplier.get();
            } catch (RuntimeException e) {
                root.owner().getLogger().log(Level.WARNING, "Could not read the aliases of /" + root.getName()
                        + "; using the default ones", e);
            }
        }
        boolean fromCode = source == null;
        if (fromCode) {
            source = root.staticAliases;
        }
        Set<String> fixed = new LinkedHashSet<>();
        if (root.pluginCommand != null) {
            for (String alias : root.pluginCommand.getAliases()) {
                fixed.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String alias : source) {
            if (alias == null) {
                continue;
            }
            String clean = alias.trim().toLowerCase(Locale.ROOT);
            if (clean.isEmpty() || clean.indexOf(' ') >= 0 || clean.indexOf(':') >= 0
                    || clean.equals(root.getName()) || fixed.contains(clean)) {
                continue;
            }
            wanted.add(clean);
        }
        if (fromCode && !root.dynamic && !wanted.isEmpty()) {
            root.commands.logOnce("static-aliases:" + root.getName(), Level.WARNING, "Aliases " + wanted + " of /"
                    + root.getName() + " are not declared in plugin.yml.");
        }
        return wanted;
    }

    static void reconcileAliases(RootCommand root) {
        Set<String> wanted = desiredAliases(root);
        root.wantedAliases = Set.copyOf(wanted);
        String prefix = prefix(root);
        for (String alias : List.copyOf(root.activeAliases)) {
            if (!wanted.contains(alias)) {
                root.activeAliases.remove(alias);
                release(root, alias);
                release(root, prefix + alias);
            }
        }
        for (String alias : List.copyOf(root.prefixedOnly)) {
            if (!wanted.contains(alias)) {
                root.prefixedOnly.remove(alias);
                release(root, prefix + alias);
            }
        }
        for (String alias : wanted) {
            if (root.activeAliases.contains(alias)) {
                continue;
            }
            boolean bare = claim(root, alias, root.priority);
            boolean prefixed = claim(root, prefix + alias, false);
            if (bare) {
                root.activeAliases.add(alias);
                root.prefixedOnly.remove(alias);
            } else {
                if (prefixed) {
                    root.prefixedOnly.add(alias);
                }
                reportLostName(root, alias, prefixed);
            }
        }
    }

    private static void reportLostName(RootCommand root, String key, boolean prefixedHeld) {
        Command occupant = known().get(key);
        String holder = occupant == null ? "another command" : ownerName(occupant);
        if (prefixedHeld) {
            root.commands.logOnce("lost:" + key, root.dynamic ? Level.INFO : Level.WARNING, "/" + key
                    + " belongs to " + holder + "; " + root.owner().getName() + " answers it as /" + prefix(root) + key
                    + ".");
        } else {
            root.commands.logOnce("lost:" + key, Level.WARNING, "Could not register /" + key + " or /" + prefix(root)
                    + key + ": both are taken.");
        }
    }

    // ---------------------------------------------------------------- keys

    /** Puts the root at {@code key} when free, or takes it when {@code mayTake} allows. */
    private static boolean claim(RootCommand root, String key, boolean mayTake) {
        Command occupant = known().get(key);
        if (occupant == root) {
            return true;
        }
        if (occupant == null) {
            known().put(key, root);
            changed();
            return true;
        }
        return mayTake && takeable(root, occupant) && take(root, key, occupant);
    }

    private static boolean take(RootCommand root, String key, Command occupant) {
        if (key.indexOf(':') >= 0) {
            return false;
        }
        String holder = ownerName(occupant);
        root.displaced.putIfAbsent(key, new Displaced(holder, new WeakReference<>(occupant)));
        known().put(key, root);
        changed();
        if (occupant instanceof RootCommand other) {
            other.activeAliases.remove(key);
            if (other.getName().equals(key)) {
                other.holdsName = false;
            } else if (known().get(prefix(other) + key) == other) {
                other.prefixedOnly.add(key);
            }
        }
        root.commands.logOnce("took:" + key, Level.INFO, "Took /" + key + " from " + holder + "; " + holder
                + " still answers as /" + holder.toLowerCase(Locale.ROOT) + ":" + key + ".");
        return true;
    }

    /** Takes the root out of {@code key} and gives the key back to whoever had it, when possible. */
    private static void release(RootCommand root, String key) {
        Displaced before = root.displaced.remove(key);
        if (!known().remove(key, root)) {
            return;
        }
        changed();
        if (before == null || Bukkit.isStopping() || known().get(key) != null) {
            return;
        }
        String prefixed = before.pluginName().toLowerCase(Locale.ROOT) + ":" + key;
        Command back = before.command().get();
        if (back == null) {
            back = known().get(prefixed);
        }
        if (back == null || !returnable(back, before.pluginName(), key, prefixed)) {
            return;
        }
        known().put(key, back);
        if (back instanceof RootCommand other) {
            if (other.getName().equals(key)) {
                other.holdsName = true;
            } else {
                other.activeAliases.add(key);
                other.prefixedOnly.remove(key);
            }
        }
    }

    private static boolean returnable(Command back, String pluginName, String key, String prefixed) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        if (back instanceof RootCommand other) {
            return other.registered && other.wants(key);
        }
        return known().get(prefixed) == back;
    }

    /** Only other plugins' commands and other roots without priority can be taken. */
    private static boolean takeable(RootCommand root, Command occupant) {
        if (occupant instanceof RootCommand other) {
            return other.owner() != root.owner() && !other.priority;
        }
        Plugin plugin = pluginOf(occupant);
        return plugin != null && plugin != root.owner();
    }

    /** The plugin behind a command, or null for the server's own commands and aliases. */
    static Plugin pluginOf(Command command) {
        if (command instanceof PluginIdentifiableCommand identified && !(command instanceof BukkitCommand)) {
            return identified.getPlugin();
        }
        try {
            return JavaPlugin.getProvidingPlugin(command.getClass());
        } catch (IllegalArgumentException | IllegalStateException notAPluginClass) {
            return null;
        }
    }

    private static String ownerName(Command command) {
        Plugin plugin = pluginOf(command);
        return plugin == null ? "the server" : plugin.getName();
    }

    // ---------------------------------------------------------------- priority recovery

    /** Takes back the keys of {@code root} that a plugin enabled later took. */
    private static void recover(RootCommand root) {
        List<String> keys = new ArrayList<>(root.activeAliases);
        if (root.holdsName || root.pluginCommand == null) {
            keys.add(0, root.getName());
        }
        for (String key : keys) {
            Command occupant = known().get(key);
            if (occupant == null || occupant == root || occupant == root.pluginCommand) {
                continue;
            }
            if (takeable(root, occupant) && take(root, key, occupant) && key.equals(root.getName())) {
                root.holdsName = true;
            }
        }
    }

    /** Remembers the newly enabled plugin as the owner of keys a root holds, to give them back later. */
    private static void noteDisplaced(RootCommand root, Plugin enabled) {
        String prefix = enabled.getName().toLowerCase(Locale.ROOT) + ":";
        List<String> keys = new ArrayList<>(root.activeAliases);
        if (root.holdsName) {
            keys.add(root.getName());
        }
        for (String key : keys) {
            if (root.displaced.containsKey(key)) {
                continue;
            }
            Command theirs = known().get(prefix + key);
            if (theirs != null && pluginOf(theirs) == enabled) {
                root.displaced.put(key, new Displaced(enabled.getName(), new WeakReference<>(theirs)));
            }
        }
    }

    /** RsLib's shared listener: roots with priority take back what later plugins took. */
    static final class PriorityListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            for (RootCommand root : List.copyOf(ROOTS)) {
                if (!root.priority || root.owner() == event.getPlugin()) {
                    continue;
                }
                try {
                    recover(root);
                    noteDisplaced(root, event.getPlugin());
                } catch (RuntimeException e) {
                    root.owner().getLogger().log(Level.WARNING, "Could not keep /" + root.getName(), e);
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onServerLoad(ServerLoadEvent event) {
            for (RootCommand root : List.copyOf(ROOTS)) {
                if (!root.priority) {
                    continue;
                }
                try {
                    recover(root);
                } catch (RuntimeException e) {
                    root.owner().getLogger().log(Level.WARNING, "Could not keep /" + root.getName(), e);
                }
            }
        }
    }

    // ---------------------------------------------------------------- publishing

    /**
     * Players see the new command list once, on the next tick, however many roots changed in
     * this one. Publishing on every change during a reload breaks Paper's async tree builder.
     */
    private static void changed() {
        mapChanged = true;
        Plugin pending = publisher;
        if (Bukkit.isStopping() || pending != null && pending.isEnabled()) {
            return;
        }
        publisher = null;
        boolean main = Bukkit.isPrimaryThread();
        Plugin via = scheduler(main);
        if (via == null) {
            if (main) {
                publish();
            } else {
                Bukkit.getLogger().log(Level.FINE, "[RsLib] No enabled plugin to publish the command list from; "
                        + "it goes out with the next change on the main thread.");
            }
            return;
        }
        try {
            Bukkit.getScheduler().runTask(via, CommandRegistry::publish);
            publisher = via;
        } catch (RuntimeException cannotSchedule) {
            if (main) {
                publish();
            }
        }
    }

    /**
     * RsLib when it is enabled. Off the main thread, players can only be updated from a task, so
     * any enabled plugin that owns a root will do; null when there is none.
     */
    private static Plugin scheduler(boolean main) {
        RsLibPlugin lib = RsLibPlugin.running();
        if (lib != null && lib.isEnabled()) {
            return lib;
        }
        if (main) {
            return null;
        }
        for (RootCommand root : List.copyOf(ROOTS)) {
            if (root.owner().isEnabled()) {
                return root.owner();
            }
        }
        return null;
    }

    private static void publish() {
        publisher = null;
        if (!mapChanged || Bukkit.isStopping()) {
            return;
        }
        mapChanged = false;
        if (NEEDS_SYNC && syncCommands()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    /**
     * Before Paper 1.20.6 players run commands through a dispatcher the server builds once at
     * start, so a key added later cannot be run until the server's {@code syncCommands()}
     * rebuilds it (and sends every player the new tree). From 1.20.6 on the map forwards to
     * the dispatcher by itself and {@link Player#updateCommands()} is enough.
     */
    private static final boolean NEEDS_SYNC = !RsVersion.supports(20, 6);

    /** The server's {@code syncCommands()}, looked up once; null when it has none. */
    private static Method syncMethod;
    private static boolean syncLooked;

    /** True when the server rebuilt its dispatcher and sent the tree to every player. */
    private static boolean syncCommands() {
        if (!syncLooked) {
            syncLooked = true;
            syncMethod = findSync(Bukkit.getServer().getClass());
            if (syncMethod == null) {
                Bukkit.getLogger().log(Level.WARNING, "[RsLib] This server has no syncCommands(); commands added"
                        + " after start may need a restart to run.");
            }
        }
        if (syncMethod == null) {
            return false;
        }
        try {
            syncMethod.invoke(Bukkit.getServer());
            return true;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            Bukkit.getLogger().log(Level.FINE, "[RsLib] syncCommands() failed; sending the list to each player", failed);
            syncMethod = null;
            return false;
        }
    }

    private static Method findSync(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod("syncCommands");
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException absent) {
                // keep looking up the hierarchy
            }
        }
        return null;
    }

    /** Hands a plugin.yml command over to its root. */
    private static final class Adapter implements TabExecutor {

        private final RootCommand root;

        Adapter(RootCommand root) {
            this.root = root;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            return root.execute(sender, label, args);
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return root.tabComplete(sender, alias, args);
        }
    }
}
