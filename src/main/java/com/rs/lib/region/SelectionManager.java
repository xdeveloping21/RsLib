package com.rs.lib.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.event.RsSelectionCompleteEvent;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.util.InvUtil;
import com.rs.lib.util.LocationUtil;

/**
 * A WorldEdit-style selection wand, reached with {@code rs().selections()}: a player marks two
 * corners of a region and the plugin gets a {@link Cuboid}.
 *
 * <pre>
 * SelectionSpec arena = SelectionSpec.builder("arena").onSelect(box -&gt; save(box)).build();
 * rs().selections().registerSpec(arena);   // on enable, so wands given earlier keep working
 * rs().selections().giveWand(player, arena);
 * </pre>
 *
 * <h2>The wand</h2>
 * <p>A wand is any item marked under {@code <plugin>:}{@value #WAND_TAG} (the plugin's name in
 * lower case) with the spec's id as text, so wands of one spec stack, and keep working after a
 * relog or a restart once the plugin registers the spec again. Left click on a block sets the
 * first position, right click the second (main hand only). The click is cancelled before
 * anything else, at the lowest priority and even when already cancelled, so a wand never breaks
 * or uses a block, whatever protection plugins say. Without a session for that spec, the click
 * opens one.</p>
 *
 * <h2>Setting a position</h2>
 * <ol>
 *   <li>A player without the spec's permission gets {@code rslib.selection.no-permission}
 *   (unless the spec is silent) and nothing is stored.</li>
 *   <li>The block is stored, the particles are drawn again, {@code rslib.selection.pos1-set}
 *   (or {@code pos2-set}) is sent with {@code {x} {y} {z} {world}}, then
 *   {@code rslib.selection.different-worlds} when the two positions are in different worlds,
 *   then the spec's {@code onUpdate} runs.</li>
 *   <li>With both positions in one world: a box over the spec's maximum volume gets
 *   {@code rslib.selection.too-big} ({@code {volume} {max}}); otherwise
 *   {@link RsSelectionCompleteEvent} fires and, when nobody cancelled it, {@code onSelect} runs
 *   with the box and, if the spec says so ({@link SelectionSpec#completeEnds()}), the session
 *   ends.</li>
 * </ol>
 * <p>A silent spec sends none of these messages. {@link SelectionSession#setPos1} and
 * {@link SelectionSession#setPos2} run steps 2 and 3 the same way.</p>
 *
 * <h2>Sessions</h2>
 * <p>One session per player and plugin, never saved. While it has a position (or a timeout) a
 * task on the player's thread draws it every {@code interval-ticks}, ends it with
 * {@code rslib.selection.timeout} once the spec's timeout passes without a new position (no
 * message when the spec is silent), and ends it when the player is
 * gone. A session that ends without completing (cancelled, replaced by {@link #begin}, timed out,
 * player left) runs the spec's {@code onCancel}; shutting the plugin down ends every session
 * without callbacks.</p>
 */
public final class SelectionManager {

    /** The key name of the wand mark, under the plugin's namespace. */
    public static final String WAND_TAG = "rslib_selection_wand";

    static final String POS1_SET = "rslib.selection.pos1-set";
    static final String POS2_SET = "rslib.selection.pos2-set";
    static final String DIFFERENT_WORLDS = "rslib.selection.different-worlds";
    static final String TOO_BIG = "rslib.selection.too-big";
    static final String NO_PERMISSION = "rslib.selection.no-permission";
    static final String TIMEOUT = "rslib.selection.timeout";

    private static final long MILLIS_PER_TICK = 50L;

    /** Managers with a registered spec, by namespace: the only ones whose wands can be clicked. */
    private static final Map<String, SelectionManager> ARMED = new ConcurrentHashMap<>();
    private static final Listener LISTENER = new SelectionWandListener();

    private final SelectionHost host;
    private final String namespace;
    private final NamespacedKey wandKey;
    private final Map<String, SelectionSpec> specs = new ConcurrentHashMap<>();
    private final Map<UUID, SelectionSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    /** Created by the context. */
    public SelectionManager(Rs rs) {
        this(SelectionHost.of(Objects.requireNonNull(rs, "rs")));
        rs.onQuit(player -> handleRendererOffline(player.getUniqueId()));
    }

    SelectionManager(SelectionHost host) {
        this.host = host;
        this.namespace = host.namespace();
        this.wandKey = new NamespacedKey(namespace, WAND_TAG);
    }

    /** The wand listener every plugin shares; RsLib registers it. */
    public static Listener wandListener() {
        return LISTENER;
    }

    // ------------------------------------------------------------------ specs and wands

    /** Makes the spec's wands work (also after a restart) without starting a session. Replaces a spec with the same id. */
    public void registerSpec(SelectionSpec spec) {
        Objects.requireNonNull(spec, "spec");
        specs.put(spec.id(), spec);
        if (!stopped) {
            ARMED.put(namespace, this);
        }
    }

    /** A new wand for {@code spec} (registering it). Main thread. */
    public ItemStack createWand(SelectionSpec spec) {
        registerSpec(spec);
        ItemStack wand = host.wandBase(spec);
        ItemMeta meta = wand.getItemMeta();
        if (meta == null) {
            host.logger().warning("The wand of selection '" + spec.id() + "' is " + wand.getType()
                    + ", which cannot carry the wand mark; it will not work as a wand.");
            return wand;
        }
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, spec.id());
        wand.setItemMeta(meta);
        return wand;
    }

    /** Gives {@code player} a wand for {@code spec}; what does not fit drops at their feet. On the player's thread. */
    public void giveWand(Player player, SelectionSpec spec) {
        InvUtil.giveItems(player, createWand(spec));
    }

    /** True when {@code item} is a wand of this plugin. */
    public boolean isWand(ItemStack item) {
        return wandSpecId(item) != null;
    }

    /** The spec id a wand of this plugin carries, or null when {@code item} is not one. */
    public String wandSpecId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : specIdIn(meta.getPersistentDataContainer());
    }

    String specIdIn(PersistentDataContainer pdc) {
        return pdc.get(wandKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ sessions

    /**
     * Starts a session of {@code spec} for {@code player} (registering the spec) and returns it.
     * A session the player already had ends first and runs its own {@code onCancel}.
     *
     * @throws IllegalStateException after {@link #shutdown()}
     */
    public SelectionSession begin(Player player, SelectionSpec spec) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(spec, "spec");
        if (stopped) {
            throw new IllegalStateException("The selections of " + namespace + " are shut down");
        }
        registerSpec(spec);
        SelectionSession next = new SelectionSession(player.getUniqueId(), spec, host.nowMillis(), this);
        SelectionSession previous = sessions.put(player.getUniqueId(), next);
        if (previous != null && end(previous)) {
            runCancel(previous);
        }
        if (spec.timeoutTicks() > 0) {
            startRenderer(player, next);
        }
        return next;
    }

    /** The player's session, or null. */
    public SelectionSession current(Player player) {
        return player == null ? null : current(player.getUniqueId());
    }

    /** The player's session, or null. */
    public SelectionSession current(UUID player) {
        return player == null ? null : sessions.get(player);
    }

    /** Ends the player's session without a message, running {@code onCancel}; true when there was one. */
    public boolean cancel(Player player) {
        return player != null && cancel(player.getUniqueId());
    }

    /** Ends the player's session without a message, running {@code onCancel}; true when there was one. */
    public boolean cancel(UUID player) {
        SelectionSession session = current(player);
        if (session == null || !end(session)) {
            return false;
        }
        runCancel(session);
        return true;
    }

    /** Ends every session without messages or callbacks; wands stop working for this plugin. */
    public void shutdown() {
        stopped = true;
        ARMED.remove(namespace, this);
        for (SelectionSession session : new ArrayList<>(sessions.values())) {
            end(session);
        }
    }

    /** The player of {@code session} is gone: the session ends without a message and runs {@code onCancel}. */
    public void handleRendererOffline(SelectionSession session) {
        if (session != null && end(session)) {
            runCancel(session);
        }
    }

    /** Same as {@link #handleRendererOffline(SelectionSession)} for the player's current session. */
    public void handleRendererOffline(UUID player) {
        handleRendererOffline(current(player));
    }

    /** {@code session} ran out of time: it ends with the timeout message (unless silent) and runs {@code onCancel}. */
    public void handleRendererTimeout(SelectionSession session) {
        if (session == null || !end(session)) {
            return;
        }
        if (!session.spec().silent()) {
            Player player = host.online(session.playerId());
            if (player != null) {
                send(player, TIMEOUT);
            }
        }
        runCancel(session);
    }

    /** Same as {@link #handleRendererTimeout(SelectionSession)} for the player's current session. */
    public void handleRendererTimeout(UUID player) {
        handleRendererTimeout(current(player));
    }

    // ------------------------------------------------------------------ wand clicks

    /**
     * A wand of spec {@code specId} clicked {@code block}: left sets the first position, right
     * the second. Called by the shared listener on the player's thread; see the class notes.
     */
    public void handleWandClick(Player player, String specId, boolean left, Location block) {
        if (stopped || host.shuttingDown() || player == null || specId == null || block == null) {
            return;
        }
        SelectionSession session = sessions.get(player.getUniqueId());
        boolean same = session != null && session.spec().id().equals(specId);
        SelectionSpec spec = same ? session.spec() : specs.get(specId);
        if (spec == null) {
            return;
        }
        if (!spec.permission().isEmpty() && !player.hasPermission(spec.permission())) {
            if (!spec.silent()) {
                send(player, NO_PERMISSION);
            }
            return;
        }
        if (!same) {
            session = begin(player, spec);
        }
        setPosition(player, session, left, block);
    }

    /**
     * {@link SelectionSession#setPos1}/{@code setPos2} from a plugin: the click pipeline without
     * the permission check. False (nothing done) when the session ended, is not this player's
     * current one, or the player is offline; the session then only stores the position.
     */
    boolean applyPosition(SelectionSession session, boolean left, Location block) {
        if (stopped || session.ended() || sessions.get(session.playerId()) != session) {
            return false;
        }
        Player player = host.online(session.playerId());
        if (player == null) {
            return false;
        }
        setPosition(player, session, left, block);
        return true;
    }

    private void setPosition(Player player, SelectionSession session, boolean left, Location block) {
        SelectionSpec spec = session.spec();
        World world = SelectionSession.worldOf(block);
        Location at = new Location(world, block.getBlockX(), block.getBlockY(), block.getBlockZ());
        if (left) {
            session.store1(at);
        } else {
            session.store2(at);
        }
        session.touch(host.nowMillis());
        startRenderer(player, session);
        draw(player, session);
        boolean silent = spec.silent();
        if (!silent) {
            send(player, left ? POS1_SET : POS2_SET, Ph.of("x", at.getBlockX()), Ph.of("y", at.getBlockY()),
                    Ph.of("z", at.getBlockZ()), Ph.of("world", world == null ? "" : world.getName()));
        }
        Cuboid box = session.cuboid();
        if (session.rawPos1() != null && session.rawPos2() != null && box == null && !silent) {
            send(player, DIFFERENT_WORLDS);
        }
        run(spec.onUpdate(), session, "onUpdate");
        if (box == null || session.ended()) {
            return;
        }
        if (spec.maxVolume() > 0 && box.size() > spec.maxVolume()) {
            if (!silent) {
                send(player, TOO_BIG, Ph.of("volume", box.size()), Ph.of("max", spec.maxVolume()));
            }
            return;
        }
        if (!host.fire(new RsSelectionCompleteEvent(player, host.plugin(), spec.id(), box))) {
            return;
        }
        run(spec.onSelect(), box, "onSelect");
        if (spec.completeEnds()) {
            end(session);
        }
    }

    // ------------------------------------------------------------------ drawing

    /** Starts the drawing task of {@code session} unless it runs already. */
    private void startRenderer(Player player, SelectionSession session) {
        if (session.renderer != null || session.ended()) {
            return;
        }
        try {
            session.renderer = host.timer(player, session.spec().refreshIntervalTicks(), () -> tick(player, session));
        } catch (RuntimeException notScheduled) {
            // The plugin is going down: nothing more to draw.
        }
    }

    /** One run of the drawing task. */
    void tick(Player player, SelectionSession session) {
        if (session.ended() || sessions.get(session.playerId()) != session) {
            stopRenderer(session);
            return;
        }
        if (!player.isOnline()) {
            handleRendererOffline(session);
            return;
        }
        long timeout = session.spec().timeoutTicks();
        if (timeout > 0 && host.nowMillis() - session.lastActivityMillis() >= timeout * MILLIS_PER_TICK) {
            handleRendererTimeout(session);
            return;
        }
        if (!session.hasAnyPosition()) {
            if (timeout <= 0) {
                stopRenderer(session);
            }
            return;
        }
        draw(player, session);
    }

    /** Draws {@code session} for its viewers once. */
    void draw(Player owner, SelectionSession session) {
        Location a = session.rawPos1();
        Location b = session.rawPos2();
        Cuboid box = session.cuboid();
        if (box != null) {
            drawBox(owner, session.spec(), box, SelectionSession.worldOf(a), false);
            return;
        }
        if (a != null) {
            drawBox(owner, session.spec(), single(a), SelectionSession.worldOf(a), true);
        }
        if (b != null) {
            drawBox(owner, session.spec(), single(b), SelectionSession.worldOf(b), true);
        }
    }

    private void drawBox(Player owner, SelectionSpec spec, Cuboid box, World world, boolean marker) {
        if (world == null) {
            return;
        }
        double range = spec.renderDistance();
        double rangeSquared = range * range;
        boolean crosses = !marker && spec.maxRenderVolume() > 0 && box.size() > spec.maxRenderVolume();
        Location scratch = new Location(null, 0, 0, 0);
        for (Player viewer : viewers(owner, spec, world)) {
            Location eye = viewer.getLocation(scratch);
            if (SelectionSession.worldOf(eye) != world
                    || LocationUtil.distanceToBoxSquared(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(),
                    box.maxZ(), eye) > rangeSquared) {
                continue;
            }
            SelectionRenderer.PointSink sink = (x, y, z) -> host.particle(viewer, spec.particle(), x, y, z,
                    spec.particleData());
            if (crosses) {
                SelectionRenderer.corners(box, spec.particleBudget(), sink);
            } else {
                SelectionRenderer.outline(box, spec.step(), spec.particleBudget(), sink);
            }
        }
    }

    /**
     * Who sees the particles: the owner, or with {@code WORLD} every player in the world that
     * this thread may touch (on Folia, those in the owner's region, which holds everyone near).
     */
    private Collection<? extends Player> viewers(Player owner, SelectionSpec spec, World world) {
        if (spec.visibility() == SelectionSpec.Visibility.OWNER_ONLY) {
            return List.of(owner);
        }
        List<Player> here = new ArrayList<>();
        for (Player viewer : host.playersIn(world)) {
            if (viewer == owner || host.ownsHere(viewer)) {
                here.add(viewer);
            }
        }
        return here;
    }

    private static Cuboid single(Location at) {
        World world = SelectionSession.worldOf(at);
        return Cuboid.of(world == null ? "" : world.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private static void stopRenderer(SelectionSession session) {
        TaskHandle task = session.renderer;
        if (task != null) {
            task.cancel();
        }
    }

    // ------------------------------------------------------------------ internals

    /** Ends {@code session} once: removed, drawing stopped. True for the first caller. */
    private boolean end(SelectionSession session) {
        if (!session.end()) {
            return false;
        }
        sessions.remove(session.playerId(), session);
        stopRenderer(session);
        return true;
    }

    private void runCancel(SelectionSession session) {
        run(session.spec().onCancel(), session.playerId(), "onCancel");
    }

    private <T> void run(Consumer<T> callback, T value, String what) {
        if (callback == null) {
            return;
        }
        try {
            callback.accept(value);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "The " + what + " callback of a selection failed", e);
        }
    }

    private void send(Player player, String key, Ph... phs) {
        try {
            host.send(player, key, phs);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not send the selection message " + key, e);
        }
    }

    /** The managers with a registered spec; for the listener. */
    static Collection<SelectionManager> armed() {
        return ARMED.values();
    }

    static boolean noneArmed() {
        return ARMED.isEmpty();
    }

    /** Sessions running in this manager; for tests. */
    int sessionCount() {
        return sessions.size();
    }
}
