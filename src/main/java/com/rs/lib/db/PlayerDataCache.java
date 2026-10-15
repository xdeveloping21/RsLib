package com.rs.lib.db;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.logging.Level;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Per-player data loaded when the player joins and saved when they leave, from
 * {@code db.playerCache(loader, saver)}.
 *
 * <ul>
 *   <li><b>Join:</b> RsLib calls {@link #load} for every cache. The loader runs on the plugin's
 *   async pool (not on a database thread, so it may {@code join()} queries of the same
 *   database) and its value is installed on the main thread. A load that was
 *   {@linkplain #invalidate invalidated}, or whose player left meanwhile, is discarded. A null
 *   value means "no data" and is not installed.</li>
 *   <li><b>Quit:</b> the value is removed and, if it was {@linkplain #markDirty marked dirty},
 *   handed to the saver, which should queue async writes (an upsert, typically).</li>
 *   <li><b>Shutdown:</b> every dirty value is saved and the writes are waited for before the
 *   database closes.</li>
 * </ul>
 *
 * <p>Loading an offline player keeps the value until they join and leave or until
 * {@link #invalidate}; for one-off offline reads, query the database directly.</p>
 *
 * @param <T> the per-player value; the plugin mutates it in place and calls {@link #markDirty}
 */
public final class PlayerDataCache<T> {

    /** Writes one player's value; called on the thread that saves (the main thread on quit). */
    @FunctionalInterface
    public interface Saver<T> {
        void save(RsDb db, UUID uuid, T value);
    }

    private static final Set<PlayerDataCache<?>> LIVE = new CopyOnWriteArraySet<>();
    private static final Listener JOIN_LISTENER = new JoinListener();

    private final RsDb db;
    private final DbHost host;
    private final BiFunction<RsDb, UUID, T> loader;
    private final Saver<T> saver;
    private final Map<UUID, Slot<T>> values = new ConcurrentHashMap<>();
    /** The load in flight per player; a value is installed only if its ticket is still here. */
    private final Map<UUID, Long> tickets = new ConcurrentHashMap<>();
    private final AtomicLong nextTicket = new AtomicLong();

    private static final class Slot<T> {
        final T value;
        volatile boolean dirty;

        Slot(T value) {
            this.value = value;
        }
    }

    PlayerDataCache(RsDb db, DbHost host, BiFunction<RsDb, UUID, T> loader, Saver<T> saver) {
        this.db = db;
        this.host = host;
        this.loader = Objects.requireNonNull(loader, "loader");
        this.saver = Objects.requireNonNull(saver, "saver");
    }

    /** The single join listener shared by every cache of every plugin; RsLib registers it. */
    public static Listener joinListener() {
        return JOIN_LISTENER;
    }

    /** The loaded value, or null when it is not loaded (yet) or the loader found nothing. */
    public T get(UUID uuid) {
        Slot<T> slot = values.get(uuid);
        return slot == null ? null : slot.value;
    }

    /**
     * Loads the value in the background unless it is loaded, a load is in flight, or the
     * plugin is shutting down.
     */
    public void load(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        if (host.shuttingDown() || db.isClosed() || values.containsKey(uuid)) {
            return;
        }
        long ticket = nextTicket.incrementAndGet();
        if (tickets.putIfAbsent(uuid, ticket) != null) {
            return;
        }
        try {
            host.async(() -> fetch(uuid, ticket));
        } catch (RuntimeException notScheduled) {
            tickets.remove(uuid, ticket);
        }
    }

    /** Marks a loaded value as changed so it is saved on quit and shutdown; no-op if not loaded. */
    public void markDirty(UUID uuid) {
        Slot<T> slot = values.get(uuid);
        if (slot != null) {
            slot.dirty = true;
        }
    }

    /** Drops the value without saving it and discards a load in flight. */
    public void invalidate(UUID uuid) {
        tickets.remove(uuid);
        values.remove(uuid);
    }

    /**
     * Saves every dirty value (they stay loaded) and returns a future that completes once the
     * database reached the writes queued so far (exact on SQLite, best effort on MySQL).
     */
    public RsFuture<Void> saveAll() {
        return new RsFuture<>(host, saveDirty(), false);
    }

    /** Saves the dirty values; the returned future is the database's write barrier. */
    CompletableFuture<Void> saveDirty() {
        for (Map.Entry<UUID, Slot<T>> entry : values.entrySet()) {
            Slot<T> slot = entry.getValue();
            if (slot.dirty) {
                slot.dirty = false;
                save(entry.getKey(), slot.value);
            }
        }
        return db.fence();
    }

    /** The player left: forget them, saving a dirty value. */
    void quit(UUID uuid) {
        tickets.remove(uuid);
        Slot<T> slot = values.remove(uuid);
        if (slot != null && slot.dirty) {
            save(uuid, slot.value);
        }
    }

    private void fetch(UUID uuid, long ticket) {
        if (!Objects.equals(tickets.get(uuid), ticket)) {
            return;
        }
        T value;
        try {
            value = loader.apply(db, uuid);
        } catch (RuntimeException e) {
            tickets.remove(uuid, ticket);
            host.logger().log(Level.WARNING, "Could not load the data of player " + uuid, e);
            return;
        }
        try {
            host.sync(() -> install(uuid, ticket, value));
        } catch (RuntimeException disabled) {
            tickets.remove(uuid, ticket);
        }
    }

    private void install(UUID uuid, long ticket, T value) {
        if (tickets.remove(uuid, ticket) && value != null) {
            values.put(uuid, new Slot<>(value));
        }
    }

    private void save(UUID uuid, T value) {
        try {
            saver.save(db, uuid, value);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not save the data of player " + uuid, e);
        }
    }

    static void register(PlayerDataCache<?> cache) {
        LIVE.add(cache);
    }

    static void unregister(List<PlayerDataCache<?>> caches) {
        LIVE.removeAll(caches);
    }

    /** Loaded players, for tests. */
    int size() {
        return values.size();
    }

    private static final class JoinListener implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onJoin(PlayerJoinEvent event) {
            UUID uuid = event.getPlayer().getUniqueId();
            for (PlayerDataCache<?> cache : LIVE) {
                try {
                    cache.load(uuid);
                } catch (RuntimeException e) {
                    cache.host.logger().log(Level.WARNING, "Could not start loading the data of " + uuid, e);
                }
            }
        }
    }
}
