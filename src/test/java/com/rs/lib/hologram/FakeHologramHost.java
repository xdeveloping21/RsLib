package com.rs.lib.hologram;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import com.rs.lib.scheduler.TaskHandle;

import net.kyori.adventure.text.Component;

/**
 * A hologram host without a server: worlds whose chunks the test loads and unloads, text
 * displays that record what was done to them, and a task queue the test runs as the next tick.
 */
final class FakeHologramHost implements HologramHost {

    final List<String> warnings = new ArrayList<>();
    final Deque<Runnable> queue = new ArrayDeque<>();
    final List<Timer> timers = new ArrayList<>();
    final List<FakeDisplay> spawned = new ArrayList<>();
    final Map<UUID, FakePlayer> players = new HashMap<>();
    final Map<String, FakeWorld> worlds = new HashMap<>();
    UnaryOperator<String> papi = UnaryOperator.identity();
    boolean owns = true;
    boolean cancelSpawns;
    /** Where players stand, for {@link #anyoneNear}; null means "someone is always near". */
    List<Standing> standing;
    /** How many proximity checks were made, and the radius of the last one. */
    int nearChecks;
    int lastRadius;
    /** False plays a server older than 1.20.2, which cannot glide displays. */
    boolean canGlide = true;
    private final Logger logger = Logger.getAnonymousLogger();
    private final String pluginName;

    FakeHologramHost(String pluginName) {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        this.pluginName = pluginName;
    }

    FakeWorld addWorld(String name) {
        return worlds.computeIfAbsent(name, FakeWorld::new);
    }

    /** Runs everything queued, as the next tick would. */
    void tick() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    /** Displays spawned that are still in the world. */
    List<FakeDisplay> alive() {
        return spawned.stream().filter(d -> d.valid).toList();
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public String ownerName() {
        return pluginName;
    }

    @Override
    public void hide(Player player, Entity entity) {
        player.hideEntity(null, entity);
    }

    @Override
    public void show(Player player, Entity entity) {
        player.showEntity(null, entity);
    }

    @Override
    public World world(String name) {
        FakeWorld world = worlds.get(name);
        return world == null ? null : world.world;
    }

    @Override
    public boolean ownsHere(Location location) {
        return owns;
    }

    @Override
    public boolean ownsHere(Entity entity) {
        return owns;
    }

    @Override
    public void at(Location location, Runnable task) {
        queue.add(task);
    }

    @Override
    public void onEntity(Entity entity, Runnable task) {
        queue.add(task);
    }

    @Override
    public TaskHandle every(long periodTicks, Runnable task) {
        Timer timer = new Timer(periodTicks, task);
        timers.add(timer);
        return timer;
    }

    @Override
    public String placeholders(String line) {
        return papi.apply(line);
    }

    @Override
    public Player player(UUID id) {
        FakePlayer player = players.get(id);
        return player == null ? null : player.player;
    }

    @Override
    public TextDisplay spawn(Location location) {
        FakeDisplay display = new FakeDisplay(location);
        display.valid = !cancelSpawns;
        spawned.add(display);
        return display.entity;
    }

    /** A player standing in {@code world}, chunk {@code chunkX}, {@code chunkZ}. */
    record Standing(UUID id, String world, int chunkX, int chunkZ) {
    }

    /** Puts a player at chunk {@code chunkX}, {@code chunkZ} of {@code world}; returns its id. */
    UUID stand(String world, int chunkX, int chunkZ) {
        if (standing == null) {
            standing = new ArrayList<>();
        }
        UUID id = UUID.randomUUID();
        standing.add(new Standing(id, world, chunkX, chunkZ));
        return id;
    }

    @Override
    public boolean anyoneNear(String world, int chunkX, int chunkZ, int radiusChunks, Set<UUID> except) {
        nearChecks++;
        lastRadius = radiusChunks;
        if (standing == null) {
            return true;
        }
        for (Standing player : standing) {
            if (player.world().equals(world) && HologramUtil.within(player.chunkX() - chunkX, player.chunkZ() - chunkZ,
                    radiusChunks) && !except.contains(player.id())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void teleport(TextDisplay display, Location to) {
        for (FakeDisplay fake : spawned) {
            if (fake.entity == display) {
                fake.calls.add("teleport");
                fake.at = to;
                fake.chunkX = to.getBlockX() >> 4;
                fake.chunkZ = to.getBlockZ() >> 4;
            }
        }
    }

    @Override
    public boolean teleportDuration(TextDisplay display, int ticks) {
        if (!canGlide) {
            return false;
        }
        for (FakeDisplay fake : spawned) {
            if (fake.entity == display) {
                fake.calls.add("teleportDuration " + ticks);
                fake.teleportTicks = ticks;
            }
        }
        return true;
    }

    /** A repeating task the test fires by hand. */
    static final class Timer implements TaskHandle {

        final long period;
        final Runnable task;
        boolean cancelled;

        Timer(long period, Runnable task) {
            this.period = period;
            this.task = task;
        }

        void fire() {
            if (!cancelled) {
                task.run();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** A world whose loaded chunks the test decides. Reading its entities fails the test. */
    static final class FakeWorld {

        final String name;
        final Set<Long> loaded = new HashSet<>();
        final World world;

        FakeWorld(String name) {
            this.name = name;
            this.world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {World.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> name;
                        case "isChunkLoaded" -> args.length == 2
                                && loaded.contains(Hologram.chunkKey((Integer) args[0], (Integer) args[1]));
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "World " + name;
                        default -> throw new AssertionError("RsLib must not call World." + method.getName());
                    });
        }

        void load(int chunkX, int chunkZ) {
            loaded.add(Hologram.chunkKey(chunkX, chunkZ));
        }

        void unload(int chunkX, int chunkZ, FakeHologramHost host) {
            loaded.remove(Hologram.chunkKey(chunkX, chunkZ));
            // Non-persistent entities go with the chunk.
            for (FakeDisplay display : host.spawned) {
                if (display.chunkX == chunkX && display.chunkZ == chunkZ && !display.persistent) {
                    display.valid = false;
                }
            }
        }
    }

    /** A text display that records what was done to it. */
    static final class FakeDisplay {

        final UUID id = UUID.randomUUID();
        final Map<String, Object> data = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final TextDisplay entity;
        int chunkX;
        int chunkZ;
        Location at;
        int teleportTicks = -1;
        float viewRange = 1f;
        boolean shadowed;
        boolean seeThrough;
        Color background = Color.fromARGB(HologramStyle.DEFAULT_BACKGROUND);
        TextDisplay.TextAlignment alignment = TextDisplay.TextAlignment.CENTER;
        int lineWidth = HologramStyle.DEFAULT_LINE_WIDTH;
        byte textOpacity = (byte) -1;
        Transformation transformation = new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1, 1, 1),
                new AxisAngle4f());
        boolean valid = true;
        boolean persistent = true;
        boolean removed;
        Display.Billboard billboard = Display.Billboard.FIXED;
        Component text = Component.empty();

        FakeDisplay(Location at) {
            this(at.getBlockX() >> 4, at.getBlockZ() >> 4);
            this.at = at;
        }

        FakeDisplay(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> data.get(args[0].toString());
                        case "has" -> data.containsKey(args[0].toString());
                        case "set" -> {
                            calls.add("pdc.set " + args[0]);
                            data.put(args[0].toString(), args[2]);
                            yield null;
                        }
                        case "remove" -> {
                            calls.add("pdc.remove " + args[0]);
                            data.remove(args[0].toString());
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            entity = (TextDisplay) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {TextDisplay.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "isValid" -> valid;
                        case "getPersistentDataContainer" -> pdc;
                        case "remove" -> {
                            calls.add("remove");
                            removed = true;
                            valid = false;
                            yield null;
                        }
                        case "setPersistent" -> {
                            calls.add("setPersistent " + args[0]);
                            persistent = (Boolean) args[0];
                            yield null;
                        }
                        case "isPersistent" -> persistent;
                        case "setBillboard" -> {
                            calls.add("setBillboard " + args[0]);
                            billboard = (Display.Billboard) args[0];
                            yield null;
                        }
                        case "getBillboard" -> billboard;
                        case "setShadowed" -> {
                            calls.add("setShadowed " + args[0]);
                            shadowed = (Boolean) args[0];
                            yield null;
                        }
                        case "setSeeThrough" -> {
                            calls.add("setSeeThrough " + args[0]);
                            seeThrough = (Boolean) args[0];
                            yield null;
                        }
                        case "setBackgroundColor" -> {
                            calls.add("setBackgroundColor");
                            background = (Color) args[0];
                            yield null;
                        }
                        case "setAlignment" -> {
                            calls.add("setAlignment " + args[0]);
                            alignment = (TextDisplay.TextAlignment) args[0];
                            yield null;
                        }
                        case "setLineWidth" -> {
                            calls.add("setLineWidth " + args[0]);
                            lineWidth = (Integer) args[0];
                            yield null;
                        }
                        case "setTextOpacity" -> {
                            calls.add("setTextOpacity " + args[0]);
                            textOpacity = (Byte) args[0];
                            yield null;
                        }
                        case "getTransformation" -> transformation;
                        case "setTransformation" -> {
                            calls.add("setTransformation");
                            transformation = (Transformation) args[0];
                            yield null;
                        }
                        case "setViewRange" -> {
                            calls.add("setViewRange " + args[0]);
                            viewRange = (Float) args[0];
                            yield null;
                        }
                        case "text" -> {
                            if (args == null || args.length == 0) {
                                yield text;
                            }
                            calls.add("text");
                            text = (Component) args[0];
                            yield null;
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "TextDisplay " + id;
                        default -> {
                            calls.add(method.getName());
                            throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        /** A display already in the world with a mark (another plugin's, RsLib's or none). */
        static FakeDisplay marked(String key, String value) {
            FakeDisplay display = new FakeDisplay(0, 0);
            if (key != null) {
                display.data.put(key, value);
            }
            return display;
        }

        Object mark() {
            return data.get(HologramUtil.MARK.toString());
        }
    }

    /** A player that records which entities were hidden from it. */
    static final class FakePlayer {

        final UUID id = UUID.randomUUID();
        final Set<Entity> hidden = new HashSet<>();
        final Player player;

        FakePlayer() {
            player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "hideEntity" -> {
                            hidden.add((Entity) args[1]);
                            yield null;
                        }
                        case "showEntity" -> {
                            hidden.remove((Entity) args[1]);
                            yield null;
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "FakePlayer";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
