package com.rs.lib.gui;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.action.ActionEngine;
import com.rs.lib.action.TestEngines;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.papi.RsPapi;
import com.rs.lib.scheduler.TaskHandle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Menus without a server: a fake host that records windows, sounds, builds and timers, players
 * and inventories made of proxies, and stacks that are only a label. A rendered stack's label is
 * {@code <item id>[locals]} (plus {@code /disabled} for the nav-disabled look and
 * {@code +over(<label>)} for a plugin stack), and {@code @<menu>:<slot>} once stamped.
 */
final class GuiKit {

    /**
     * Some inventory types and their sizes: {@code InventoryType} itself cannot be loaded without
     * a server (its constants reach the registries).
     */
    static final InventoryTypes TYPES = name -> Map.of("DROPPER", 9, "DISPENSER", 9, "HOPPER", 5,
            "BARREL", 27, "SHULKER_BOX", 27).getOrDefault(name, -1);

    final TestEngines.Engine engine = TestEngines.create();
    final List<String> warnings = new ArrayList<>();
    final List<String> logged = new ArrayList<>();
    final List<String> sounds = new ArrayList<>();
    final List<String> debug = new ArrayList<>();
    final Deque<Runnable> nextTick = new ArrayDeque<>();
    final List<Timer> timers = new ArrayList<>();
    final Map<UUID, Inventory> open = new HashMap<>();
    final Map<ItemAppearance, String> looks = new IdentityHashMap<>();
    private final Logger logger = Logger.getAnonymousLogger();
    final Host host = new Host();
    final GuiManager manager = new GuiManager(host);
    int builds;
    final List<Consumer<String>> skinRefreshes = new ArrayList<>();
    int stamps;
    int writes;
    int windowsOpened;
    boolean owns = true;
    boolean shuttingDown;
    /** How the menus parsed from now on resolve placeholders; null is "locals only". */
    ItemAppearance.TextResolver resolver;
    /** When set, the next window does not open (another plugin cancelled the open event). */
    boolean refuseNextOpen;

    GuiKit() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    // ------------------------------------------------------------------ menus

    GuiDef parse(String id, String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        GuiDef def = GuiParser.parse(id, "guis/" + id + ".yml", config,
                resolver != null ? resolver : ItemAppearance.TextResolver.of((RsPapi) null), engine.engine(),
                (path, message) -> warnings.add((path.isEmpty() ? "" : path + ": ") + message), logger, TYPES);
        for (GuiItemDef item : def.items()) {
            label(item);
        }
        for (GuiTemplate template : def.templates().values()) {
            label(template.item());
        }
        return def;
    }

    private void label(GuiItemDef item) {
        looks.put(item.appearance(), item.id());
        if (item.navDisabled() != null) {
            looks.put(item.navDisabled(), item.id() + "/disabled");
        }
    }

    Gui gui(String id, String yaml) {
        return manager.add(parse(id, yaml));
    }

    /** Every warning: the parser's and the action engine's. */
    List<String> allWarnings() {
        List<String> all = new ArrayList<>(warnings);
        all.addAll(engine.warnings());
        all.addAll(logged);
        return all;
    }

    void tick() {
        while (!nextTick.isEmpty()) {
            nextTick.poll().run();
        }
        engine.tick();
    }

    // ------------------------------------------------------------------ what is shown

    /** The label of what cell {@code slot} of {@code player}'s open menu holds, or null. */
    String at(TestPlayer player, int slot) {
        Inventory inventory = open.get(player.id);
        if (inventory == null) {
            return null;
        }
        ItemStack stack = inventory.getItem(slot);
        return stack == null ? null : ((TestStack) stack).label;
    }

    /** {@link #at} without the stamp. */
    String shown(TestPlayer player, int slot) {
        String label = at(player, slot);
        if (label == null) {
            return null;
        }
        int stamp = label.lastIndexOf('@');
        return stamp < 0 ? label : label.substring(0, stamp);
    }

    Inventory inventoryOf(TestPlayer player) {
        return open.get(player.id);
    }

    /** The player closes the window (Esc). */
    void closeByPlayer(TestPlayer player) {
        Inventory was = open.remove(player.id);
        if (was != null) {
            GuiListener.closed(was);
        }
    }

    // ------------------------------------------------------------------ fakes

    /** A stack that is only a label. */
    static final class TestStack extends ItemStack {
        final String label;
        final ItemMeta meta;

        TestStack(String label) {
            this(label, null);
        }

        @SuppressWarnings("deprecation")
        TestStack(String label, ItemMeta meta) {
            super();
            this.label = label;
            this.meta = meta;
        }

        /** A stack whose persistent data holds {@code keys}. */
        static TestStack withKeys(String label, NamespacedKey... keys) {
            Set<NamespacedKey> set = Set.of(keys);
            PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(GuiKit.class.getClassLoader(),
                    new Class<?>[] {PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getKeys" -> set;
                        case "isEmpty" -> set.isEmpty();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(GuiKit.class.getClassLoader(),
                    new Class<?>[] {ItemMeta.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getPersistentDataContainer" -> data;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            return new TestStack(label, meta);
        }

        @Override
        public TestStack clone() {
            return new TestStack(label, meta);
        }

        @Override
        public ItemMeta getItemMeta() {
            return meta;
        }

        @Override
        public Material getType() {
            return Material.STONE;
        }

        @Override
        public int getAmount() {
            return 1;
        }

        @Override
        public boolean hasItemMeta() {
            return meta != null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestStack stack && stack.label.equals(label);
        }

        @Override
        public int hashCode() {
            return label.hashCode();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    record Timer(UUID player, long period, Runnable task, Handle handle) {
    }

    static final class Handle implements TaskHandle {
        boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** Runs every live timer once. */
    void fireTimers() {
        for (Timer timer : new ArrayList<>(timers)) {
            if (!timer.handle().cancelled) {
                timer.task().run();
            }
        }
    }

    long liveTimers() {
        return timers.stream().filter(t -> !t.handle().cancelled).count();
    }

    /** A player made of a proxy. */
    final class TestPlayer {
        final UUID id = UUID.randomUUID();
        final String name;
        final Set<String> permissions = new LinkedHashSet<>();
        final List<String> messages = new ArrayList<>();
        boolean online = true;
        ItemStack cursor;
        /** Storage 0-35 (hotbar 0-8), then mainHand / offHand separately. */
        final ItemStack[] storage = new ItemStack[36];
        ItemStack mainHand;
        ItemStack offHand;
        final Player player;
        final PlayerInventory inventory;

        TestPlayer(String name) {
            this.name = name;
            this.inventory = (PlayerInventory) Proxy.newProxyInstance(GuiKit.class.getClassLoader(),
                    new Class<?>[] {PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getItem" -> args[0] instanceof Integer i ? storage[i] : null;
                        case "setItem" -> {
                            if (args[0] instanceof Integer i) {
                                storage[i] = (ItemStack) args[1];
                            }
                            yield null;
                        }
                        case "getItemInMainHand" -> mainHand;
                        case "setItemInMainHand" -> {
                            mainHand = (ItemStack) args[0];
                            yield null;
                        }
                        case "getItemInOffHand" -> offHand;
                        case "setItemInOffHand" -> {
                            offHand = (ItemStack) args[0];
                            yield null;
                        }
                        case "getSize" -> 36;
                        case "getHolder" -> null;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "TestPlayerInventory[" + name + "]";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            this.player = (Player) Proxy.newProxyInstance(GuiKit.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "isOnline" -> online;
                        case "getInventory" -> inventory;
                        case "getItemOnCursor" -> cursor;
                        case "setItemOnCursor" -> {
                            cursor = (ItemStack) args[0];
                            yield null;
                        }
                        case "hasPermission" -> args[0] instanceof String p && permissions.contains(p);
                        case "isOp" -> false;
                        case "sendMessage" -> {
                            messages.add(args[0] instanceof Component c
                                    ? PlainTextComponentSerializer.plainText().serialize(c) : String.valueOf(args[0]));
                            yield null;
                        }
                        case "closeInventory" -> {
                            closeByPlayer(this);
                            yield null;
                        }
                        case "updateInventory" -> null;
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "TestPlayer[" + name + "]";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    TestPlayer player(String name) {
        return new TestPlayer(name);
    }

    /** A chest-like inventory made of a proxy. */
    Inventory inventory(InventoryHolder holder, int size) {
        ItemStack[] cells = new ItemStack[size];
        return (Inventory) Proxy.newProxyInstance(GuiKit.class.getClassLoader(), new Class<?>[] {Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHolder" -> holder;
                    case "getSize" -> size;
                    case "getItem" -> cells[(Integer) args[0]];
                    case "setItem" -> {
                        writes++;
                        cells[(Integer) args[0]] = (ItemStack) args[1];
                        yield null;
                    }
                    case "getViewers" -> {
                        List<HumanEntity> viewers = new ArrayList<>();
                        yield viewers;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestInventory" + size;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** The fake server. */
    final class Host implements GuiHost {

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public void debug(Supplier<String> message) {
            debug.add(message.get());
        }

        @Override
        public JavaPlugin plugin() {
            return null;
        }

        @Override
        public Rs rs() {
            return null;
        }

        @Override
        public ActionEngine actions() {
            return engine.engine();
        }

        @Override
        public boolean shuttingDown() {
            return shuttingDown;
        }

        @Override
        public boolean ownsHere(Player player) {
            return owns;
        }

        @Override
        public void runFor(Player player, Runnable task) {
            nextTick.add(task);
        }

        @Override
        public TaskHandle later(Player player, long delayTicks, Runnable task) {
            Handle handle = new Handle();
            nextTick.add(() -> {
                if (!handle.cancelled) {
                    task.run();
                }
            });
            return handle;
        }

        @Override
        public TaskHandle timer(Player player, long periodTicks, Runnable task) {
            Handle handle = new Handle();
            timers.add(new Timer(player.getUniqueId(), periodTicks, task, handle));
            return handle;
        }

        @Override
        public void nextTick(Runnable task) {
            nextTick.add(task);
        }

        @Override
        public Inventory createInventory(RsGuiHolder holder, GuiDef def, Component title) {
            Inventory inventory = inventory(holder, def.size());
            titles.put(inventory, PlainTextComponentSerializer.plainText().serialize(title));
            return inventory;
        }

        @Override
        public void openWindow(Player player, Inventory inventory) {
            if (refuseNextOpen) {
                refuseNextOpen = false;
                return;
            }
            windowsOpened++;
            Inventory was = open.put(player.getUniqueId(), inventory);
            if (was != null && was != inventory) {
                GuiListener.closed(was);
            }
        }

        @Override
        public boolean viewing(Player player, Inventory inventory) {
            return inventory != null && open.get(player.getUniqueId()) == inventory;
        }

        @Override
        public void closeWindow(Player player) {
            Inventory was = open.remove(player.getUniqueId());
            if (was != null) {
                GuiListener.closed(was);
            }
        }

        @Override
        public void sound(Player player, String spec) {
            if (spec != null && !spec.isEmpty()) {
                sounds.add(spec);
            }
        }

        @Override
        public Component title(GuiDef def, Player viewer, Ph[] phs) {
            return Component.text(def.resolver().resolve(def.title(), viewer, ItemAppearance.locals(phs)));
        }

        @Override
        public ItemStack build(ItemAppearance look, Player viewer, Ph[] phs, ItemStack overlay,
                               Consumer<String> skinRefresh) {
            builds++;
            if (skinRefresh != null) {
                skinRefreshes.add(skinRefresh);
            }
            StringBuilder label = new StringBuilder(String.valueOf(looks.get(look)));
            if (phs.length > 0) {
                label.append('[');
                for (int i = 0; i < phs.length; i++) {
                    label.append(i == 0 ? "" : ",").append(phs[i].key()).append('=').append(phs[i].value());
                }
                label.append(']');
            }
            if (overlay != null) {
                label.append("+over(").append(((TestStack) overlay).label).append(')');
            }
            return new TestStack(label.toString());
        }

        @Override
        public ItemStack stamp(ItemStack proto, String guiId, int slot) {
            stamps++;
            return new TestStack(((TestStack) proto).label + "@" + guiId + ":" + slot);
        }
    }

    final Map<Inventory, String> titles = new IdentityHashMap<>();

    /** The title shown to {@code player}. */
    String title(TestPlayer player) {
        return titles.get(open.get(player.id));
    }
}
