package com.rs.lib.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import com.rs.lib.Rs;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;

/**
 * Text holograms by id, shown with real {@link TextDisplay} entities (no packets, no NMS),
 * reached with {@code rs().holograms()}.
 *
 * <pre>
 * rs().holograms().spawn("crate-1", location, List.of("&amp;6Vote crate", "&amp;7%server_online% online"));
 * rs().holograms().refreshEvery("crate-1", 40);
 * </pre>
 *
 * <ul>
 *   <li><b>Never saved in the world.</b> The entity is not persistent: when its chunk unloads it
 *   is gone, and when the chunk loads again a new one is spawned on the next tick. A restart
 *   or a crash leaves nothing behind, so there is nothing to purge. A hologram whose chunk is
 *   not loaded costs nothing: no entity, no placeholder work, and {@link #spawn} never loads a
 *   chunk.</li>
 *   <li>Each entity carries the mark {@link #MARK} = {@code <Plugin>:<id>}. Entities without
 *   it, including any other plugin's, are never
 *   adopted, changed or removed.</li>
 *   <li>Each line goes through PlaceholderAPI with no viewer and then the full text pipeline;
 *   the lines are joined with line breaks. The billboard is {@code CENTER} unless set.</li>
 *   <li>A refresh ({@link #refreshEvery}) does no placeholder work while no player is near
 *   enough to see the hologram; it renders again the moment a player starts receiving the
 *   entity, or at the next refresh once one is near.</li>
 *   <li>{@link #moveTo} glides the entity to its new place on Paper 1.20.2 and later.</li>
 *   <li>{@link #hideFrom}/{@link #showTo} use Paper's per-plugin entity visibility and are
 *   applied again to every new entity, until the player leaves.</li>
 *   <li>An id that does not exist is reported once per id, as a warning.</li>
 * </ul>
 *
 * <p>Thread-safe; entity work runs on the thread that owns the chunk (the main thread on
 * Paper, the region on Folia). When the plugin shuts down every hologram is deleted.</p>
 */
public final class HologramUtil {

    /** RsLib's mark on its hologram entities; the value is {@code <Plugin>:<id>}. */
    public static final NamespacedKey MARK = Objects.requireNonNull(NamespacedKey.fromString("rslib:rslib_hologram"));

    /** The teleport duration {@link #moveTo(String, Location)} uses, in ticks. */
    public static final int DEFAULT_MOVE_TICKS = 3;
    /** The longest teleport duration the client interpolates, in ticks. */
    public static final int MAX_MOVE_TICKS = 59;
    /** Blocks a display is seen from at view range 1, before the client's entity distance setting. */
    static final int BLOCKS_PER_VIEW_RANGE = 64;

    /** Live hologram sets by plugin name, for {@link #adopt}. */
    private static final Map<String, HologramUtil> LIVE = new ConcurrentHashMap<>();

    private final HologramHost host;
    private final String ownerName;
    private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Created by the context. */
    public HologramUtil(Rs rs) {
        this(HologramHost.of(rs));
        rs.onQuit(player -> forgetViewer(player.getUniqueId()));
    }

    HologramUtil(HologramHost host) {
        this.host = host;
        this.ownerName = host.ownerName();
        LIVE.put(ownerName, this);
    }

    /** The chunk listener every plugin shares; RsLib registers it. */
    public static Listener chunkListener() {
        return HologramChunkListener.INSTANCE;
    }

    /**
     * Shows {@code lines} at {@code location} under {@code id}, replacing a hologram with the
     * same id. A location without a (loaded) world is reported and ignored. When the chunk is
     * not loaded, the hologram appears once it is.
     */
    public void spawn(String id, Location location, List<String> lines) {
        Objects.requireNonNull(id, "id");
        World world = worldOf(location);
        if (world == null) {
            host.logger().warning("Hologram '" + id + "' has no loaded world in its location; not spawned.");
            return;
        }
        Hologram hologram = new Hologram(this, id, location, copy(lines));
        Hologram previous = holograms.put(id, hologram);
        if (previous != null) {
            destroy(previous);
        }
        HologramChunkListener.INSTANCE.add(hologram, hologram.place);
        place(hologram, world);
    }

    /** New text. While the chunk is not loaded it is only kept, and shown when the hologram is back. */
    public void setLines(String id, List<String> lines) {
        Hologram hologram = known(id);
        if (hologram == null) {
            return;
        }
        hologram.lines = copy(lines);
        update(hologram, true, false);
    }

    /** How the hologram turns to face players; null is {@code CENTER}. */
    public void setBillboard(String id, Display.Billboard billboard) {
        Hologram hologram = known(id);
        if (hologram == null) {
            return;
        }
        Display.Billboard value = billboard == null ? Display.Billboard.CENTER : billboard;
        hologram.billboard = value;
        TextDisplay display = hologram.entity;
        if (Hologram.alive(display)) {
            onEntityThread(display, () -> {
                if (hologram.entity == display) {
                    display.setBillboard(value);
                }
            });
        }
    }

    /**
     * Renders the lines again (placeholders included) every {@code intervalTicks}, replacing
     * an earlier refresh; zero or less stops refreshing. Nothing is rendered while the chunk is
     * not loaded, nor while no player who may see it is in the same world within its view
     * range (see {@link #setViewRange}) plus one chunk, counted in chunks; it renders as soon as
     * a player starts receiving the entity, or on the next refresh once one is near. Every
     * refresh of the plugin runs on one shared ticker.
     */
    public void refreshEvery(String id, long intervalTicks) {
        Hologram hologram = known(id);
        if (hologram == null) {
            return;
        }
        synchronized (hologram) {
            stopRefresh(hologram);
            if (intervalTicks <= 0 || hologram.deleted) {
                return;
            }
            try {
                hologram.refresh = host.every(intervalTicks, () -> update(hologram, false, true));
            } catch (RuntimeException notScheduled) {
                host.logger().log(Level.WARNING, "Could not schedule the refresh of hologram '" + id + "'", notScheduled);
            }
        }
    }

    /** Shows the hologram again to a player it was hidden from. */
    public void showTo(Player player, String id) {
        Hologram hologram = known(id);
        if (hologram == null || player == null) {
            return;
        }
        hologram.hiddenFrom.remove(player.getUniqueId());
        TextDisplay display = hologram.entity;
        if (Hologram.alive(display)) {
            wake(hologram);
            onEntityThread(display, () -> host.show(player, display));
        }
    }

    /**
     * Moves the hologram to {@code to}, gliding over {@link #DEFAULT_MOVE_TICKS} ticks; see
     * {@link #moveTo(String, Location, int)}.
     */
    public void moveTo(String id, Location to) {
        moveTo(id, to, DEFAULT_MOVE_TICKS);
    }

    /**
     * Moves the hologram to {@code to}. When its entity exists and {@code to} is in a loaded chunk
     * of the same world, the entity is teleported there and players see it glide over
     * {@code teleportTicks} ticks (0 to {@value #MAX_MOVE_TICKS}; 0 jumps) on Paper 1.20.2 and
     * later; older servers jump. Otherwise the entity is removed and the hologram appears at
     * {@code to} once that chunk is loaded. A location without a loaded world is reported and
     * ignored.
     */
    public void moveTo(String id, Location to, int teleportTicks) {
        Hologram hologram = known(id);
        if (hologram == null) {
            return;
        }
        World world = worldOf(to);
        if (world == null) {
            host.logger().warning("Hologram '" + id + "' cannot move to a location without a loaded world; not moved.");
            return;
        }
        int ticks = Math.max(0, Math.min(MAX_MOVE_TICKS, teleportTicks));
        Hologram.Place target = Hologram.Place.of(to);
        Hologram.Place from;
        TextDisplay display;
        synchronized (hologram) {
            if (hologram.deleted) {
                return;
            }
            from = hologram.place;
            hologram.place = target;
            if (!from.sameChunk(target)) {
                HologramChunkListener.INSTANCE.remove(hologram, from);
                HologramChunkListener.INSTANCE.add(hologram, target);
            }
            display = hologram.entity;
        }
        if (Hologram.alive(display) && from.world().equals(target.world())
                && world.isChunkLoaded(target.chunkX(), target.chunkZ())) {
            Location at = target.location(world);
            onEntityThread(display, () -> {
                synchronized (hologram) {
                    if (hologram.entity != display || hologram.place != target || !Hologram.alive(display)) {
                        return;
                    }
                    if (hologram.teleportTicks != ticks && host.teleportDuration(display, ticks)) {
                        hologram.teleportTicks = ticks;
                    }
                    host.teleport(display, at);
                }
            });
            return;
        }
        if (Hologram.alive(display)) {
            synchronized (hologram) {
                if (hologram.entity == display) {
                    hologram.entity = null;
                }
            }
            HologramChunkListener.INSTANCE.fresh(display.getUniqueId());
            onEntityThread(display, display::remove);
        }
        place(hologram, world);
    }

    /**
     * How far away the hologram can be seen, as a multiple of {@value #BLOCKS_PER_VIEW_RANGE}
     * blocks (the client's entity distance setting applies on top); refreshes run while a player
     * is within it. Zero, a negative or a non-finite value restores the default, 1.
     */
    public void setViewRange(String id, float range) {
        Hologram hologram = known(id);
        if (hologram == null) {
            return;
        }
        boolean valid = range > 0 && Float.isFinite(range);
        hologram.viewRange = valid ? range : 0f;
        float value = valid ? range : 1f;
        TextDisplay display = hologram.entity;
        if (Hologram.alive(display)) {
            onEntityThread(display, () -> {
                if (hologram.entity == display) {
                    display.setViewRange(value);
                }
            });
        }
        // A wider range may bring players in: their text must not wait for the next refresh.
        wake(hologram);
    }

    /** Hides the hologram from one player, until {@link #showTo} or until they leave. */
    public void hideFrom(Player player, String id) {
        Hologram hologram = known(id);
        if (hologram == null || player == null) {
            return;
        }
        hologram.hiddenFrom.add(player.getUniqueId());
        TextDisplay display = hologram.entity;
        if (Hologram.alive(display)) {
            onEntityThread(display, () -> host.hide(player, display));
        }
    }

    /** Stops the refresh and removes the entity. */
    public void delete(String id) {
        Hologram hologram = id == null ? null : holograms.remove(id);
        if (hologram == null) {
            warnUnknown(id);
            return;
        }
        destroy(hologram);
    }

    /** Deletes every hologram of the plugin. */
    public void deleteAll() {
        for (String id : new ArrayList<>(holograms.keySet())) {
            Hologram hologram = holograms.remove(id);
            if (hologram != null) {
                destroy(hologram);
            }
        }
    }

    /** Deletes everything and stops answering {@link #adopt}; called by the context on shutdown. */
    public void shutdown() {
        deleteAll();
        LIVE.remove(ownerName, this);
    }

    /**
     * Decides what to do with a text display found in the world; call it on the thread that
     * owns the entity. Only entities with RsLib's {@link #MARK} are considered: anything else
     * (no mark, another plugin's) is left alone and false
     * comes back. A marked entity no live hologram claims, or a second copy of one that already
     * has its entity, is removed. Otherwise the entity becomes that hologram's (not persistent,
     * text and billboard applied again) and true comes back.
     */
    public static boolean adopt(TextDisplay display) {
        if (display == null) {
            return false;
        }
        String mark = display.getPersistentDataContainer().get(MARK, PersistentDataType.STRING);
        if (mark == null) {
            return false;
        }
        int colon = mark.indexOf(':');
        HologramUtil util = colon <= 0 ? null : LIVE.get(mark.substring(0, colon));
        Hologram hologram = util == null ? null : util.holograms.get(mark.substring(colon + 1));
        if (hologram == null) {
            display.remove();
            return false;
        }
        return util.rebind(hologram, display);
    }

    // ------------------------------------------------------------------ internals

    String ownerName() {
        return ownerName;
    }

    /** The hologram behind {@code id}, or null; for tests. */
    Hologram hologram(String id) {
        return holograms.get(id);
    }

    /** Called by the shared listener, on the thread that loaded the chunk. */
    void chunkLoaded(Hologram hologram) {
        if (hologram.deleted || Hologram.alive(hologram.entity)) {
            return;
        }
        World world = host.world(hologram.world());
        if (world != null) {
            schedule(hologram, hologram.location(world));
        }
    }

    /**
     * Renders a hologram that skipped refreshes while nobody was near, on the calling thread
     * when it owns the entity; called when a player starts receiving it or it is shown again.
     */
    void wake(Hologram hologram) {
        if (hologram.stale && !hologram.deleted) {
            update(hologram, false, false);
        }
    }

    /** Spawns now when this thread owns the chunk, later when not; nothing while it is unloaded. */
    private void place(Hologram hologram, World world) {
        Hologram.Place where = hologram.place;
        if (!world.isChunkLoaded(where.chunkX(), where.chunkZ())) {
            return;
        }
        Location at = where.location(world);
        if (host.ownsHere(at)) {
            materialize(hologram);
        } else {
            schedule(hologram, at);
        }
    }

    private void schedule(Hologram hologram, Location at) {
        try {
            host.at(at, () -> materialize(hologram));
        } catch (RuntimeException disabled) {
            host.logger().log(Level.FINE, "Could not schedule hologram '" + hologram.id + "'", disabled);
        }
    }

    /** Spawns the entity if the hologram has none and its chunk is loaded; on the chunk's thread. */
    void materialize(Hologram hologram) {
        synchronized (hologram) {
            if (hologram.deleted || Hologram.alive(hologram.entity)) {
                return;
            }
            Hologram.Place at = hologram.place;
            World world = host.world(at.world());
            if (world == null || !world.isChunkLoaded(at.chunkX(), at.chunkZ())) {
                return;
            }
            TextDisplay display;
            try {
                display = host.spawn(at.location(world));
            } catch (RuntimeException failed) {
                warnOnce("spawn:" + hologram.id, "Could not spawn hologram '" + hologram.id + "': " + failed);
                return;
            }
            if (!Hologram.alive(display)) {
                warnOnce("spawn:" + hologram.id, "Hologram '" + hologram.id + "' could not be spawned (another plugin cancelled it).");
                return;
            }
            configure(hologram, display);
        }
    }

    /** Makes {@code display} the hologram's entity. Caller holds the hologram's lock. */
    private void configure(Hologram hologram, TextDisplay display) {
        display.setPersistent(false);
        display.getPersistentDataContainer().set(MARK, PersistentDataType.STRING, hologram.mark());
        display.setBillboard(hologram.billboard);
        if (hologram.viewRange > 0) {
            display.setViewRange(hologram.viewRange);
        }
        if (hologram.teleportTicks >= 0 && !host.teleportDuration(display, hologram.teleportTicks)) {
            hologram.teleportTicks = -1;
        }
        Component text = render(hologram.lines);
        display.text(text);
        TextDisplay previous = hologram.entity;
        if (previous != null && previous != display) {
            HologramChunkListener.INSTANCE.fresh(previous.getUniqueId());
        }
        hologram.shown = text;
        hologram.stale = false;
        hologram.entity = display;
        for (UUID id : hologram.hiddenFrom) {
            Player player = host.player(id);
            if (player != null) {
                host.hide(player, display);
            }
        }
    }

    private boolean rebind(Hologram hologram, TextDisplay display) {
        synchronized (hologram) {
            TextDisplay bound = hologram.entity;
            boolean same = bound == display || (bound != null && bound.getUniqueId().equals(display.getUniqueId()));
            if (hologram.deleted || (!same && Hologram.alive(bound))) {
                display.remove();
                return false;
            }
            configure(hologram, display);
            return true;
        }
    }

    /**
     * Shows the current lines: renders and applies them when the entity exists (only if they
     * changed, unless {@code force}); otherwise spawns it if the chunk is loaded, which renders.
     * With {@code onlyIfSeen}, an existing entity no player is near is only marked stale.
     */
    private void update(Hologram hologram, boolean force, boolean onlyIfSeen) {
        if (hologram.deleted) {
            return;
        }
        TextDisplay display = hologram.entity;
        if (!Hologram.alive(display)) {
            World world = host.world(hologram.world());
            if (world != null) {
                place(hologram, world);
            }
            return;
        }
        if (onlyIfSeen && !anyoneNear(hologram)) {
            if (!hologram.stale) {
                hologram.stale = true;
                HologramChunkListener.INSTANCE.stale(display.getUniqueId(), hologram);
            }
            return;
        }
        if (hologram.stale) {
            hologram.stale = false;
            HologramChunkListener.INSTANCE.fresh(display.getUniqueId());
        }
        Component text = render(hologram.lines);
        if (!force && text.equals(hologram.shown)) {
            return;
        }
        onEntityThread(display, () -> {
            synchronized (hologram) {
                if (hologram.entity == display && Hologram.alive(display)) {
                    display.text(text);
                    hologram.shown = text;
                }
            }
        });
    }

    private void destroy(Hologram hologram) {
        TextDisplay display;
        synchronized (hologram) {
            hologram.deleted = true;
            stopRefresh(hologram);
            display = hologram.entity;
            hologram.entity = null;
        }
        HologramChunkListener.INSTANCE.remove(hologram, hologram.place);
        if (display != null) {
            HologramChunkListener.INSTANCE.fresh(display.getUniqueId());
        }
        if (Hologram.alive(display)) {
            // Not persistent: if it cannot be removed now, it goes with its chunk.
            onEntityThread(display, display::remove);
        }
    }

    /** Caller holds the hologram's lock. */
    private static void stopRefresh(Hologram hologram) {
        TaskHandle refresh = hologram.refresh;
        if (refresh != null) {
            refresh.cancel();
            hologram.refresh = null;
        }
    }

    private void onEntityThread(TextDisplay display, Runnable task) {
        if (host.ownsHere(display)) {
            task.run();
            return;
        }
        try {
            host.onEntity(display, task);
        } catch (RuntimeException disabled) {
            host.logger().log(Level.FINE, "Could not schedule hologram entity work", disabled);
        }
    }

    private void forgetViewer(UUID player) {
        for (Hologram hologram : holograms.values()) {
            hologram.hiddenFrom.remove(player);
        }
    }

    /** True when a player who may see the hologram is near it; see {@link #refreshEvery}. */
    private boolean anyoneNear(Hologram hologram) {
        Hologram.Place at = hologram.place;
        return host.anyoneNear(at.world(), at.chunkX(), at.chunkZ(), radiusChunks(hologram.viewRange),
                hologram.hiddenFrom);
    }

    /**
     * The chunk distance within which a player keeps a hologram refreshing: its view range in
     * chunks, rounded up, plus one chunk so a player walking in finds fresh text.
     */
    static int radiusChunks(float viewRange) {
        double blocks = (viewRange > 0 ? viewRange : 1f) * (double) BLOCKS_PER_VIEW_RANGE;
        return (int) Math.min(1 << 20, Math.ceil(blocks / 16.0) + 1);
    }

    /** True when chunk offsets {@code dx}, {@code dz} are within {@code radius} chunks (a square). */
    static boolean within(int dx, int dz, int radius) {
        return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
    }

    private Component render(List<String> lines) {
        return render(lines, host::placeholders);
    }

    /**
     * The hologram's text: each line through {@code placeholders} and then the text pipeline
     * (lines that did not change are rendered from the shared cache), joined with line breaks.
     */
    static Component render(List<String> lines, UnaryOperator<String> placeholders) {
        if (lines.isEmpty()) {
            return Component.empty();
        }
        if (lines.size() == 1) {
            return line(lines.get(0), placeholders);
        }
        List<Component> parts = new ArrayList<>(lines.size());
        for (String raw : lines) {
            parts.add(line(raw, placeholders));
        }
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    private static Component line(String raw, UnaryOperator<String> placeholders) {
        if (raw.isEmpty()) {
            return Component.empty();
        }
        String resolved = placeholders.apply(raw);
        return resolved == raw ? RsText.color(raw) : RsText.colorUncached(resolved);
    }

    /** An unmodifiable copy where null lines are empty. */
    static List<String> copy(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(line == null ? "" : line);
        }
        return Collections.unmodifiableList(out);
    }

    private static World worldOf(Location location) {
        if (location == null) {
            return null;
        }
        try {
            return location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return null;
        }
    }

    private Hologram known(String id) {
        Hologram hologram = id == null ? null : holograms.get(id);
        if (hologram == null) {
            warnUnknown(id);
        }
        return hologram;
    }

    private void warnUnknown(String id) {
        warnOnce("id:" + id, "There is no hologram '" + id + "' (spawn it first).");
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            host.logger().warning(message);
        }
    }
}
