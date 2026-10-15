package com.rs.lib.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.rs.lib.RsLibPlugin;
import com.rs.lib.scheduler.RsScheduler;

/**
 * Skins for {@code skull-owner}, shared by every plugin. A render never waits for the network.
 *
 * <ol>
 *     <li>A cached skin is applied at once: found ones are kept 30 minutes, owners that could
 *     not be found 2 minutes (at most {@value #MAX_ENTRIES} owners).</li>
 *     <li>Otherwise the owner is resolved without the network (a UUID, an online player, a
 *     name the server has cached) and what is known is shown.</li>
 *     <li>When that has no texture, Paper fetches the profile on its own executor; one fetch per
 *     owner at a time, however many renders ask. When it arrives it is cached on the main
 *     thread and every {@code onArrive} callback waiting for that owner runs, so a menu can
 *     render the cell again. Without a callback, the next render shows the skin.</li>
 * </ol>
 * Call {@link #apply} on the main thread.
 */
final class SkinCache {

    static final long FOUND_MILLIS = 30L * 60L * 1000L;
    static final long MISSING_MILLIS = 2L * 60L * 1000L;
    static final int MAX_ENTRIES = 512;

    private record Entry(PlayerProfile profile, long expires) {
    }

    private static final Object LOCK = new Object();
    /** Owner (lower case) to skin, least recently used first; guarded by LOCK. */
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    /** Owners being fetched and the callbacks waiting for them; guarded by LOCK. */
    private static final Map<String, List<Consumer<String>>> IN_FLIGHT = new HashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;
    /** RsLib's scheduler, with the RsLib instance it belongs to. */
    private record Hop(RsLibPlugin owner, RsScheduler scheduler) {
    }

    private static volatile Hop mainHop;

    private SkinCache() {
    }

    /**
     * Makes {@code meta} show {@code owner}'s skin (a name or a UUID) as far as it is known now,
     * and starts a fetch when it is not. {@code onArrive} (may be null) receives the owner when a
     * fetched skin is ready.
     */
    static void apply(SkullMeta meta, String owner, Consumer<String> onArrive, Consumer<String> warn) {
        String key = owner.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        synchronized (LOCK) {
            Entry entry = CACHE.get(key);
            if (entry != null && entry.expires() > now) {
                if (entry.profile() != null) {
                    meta.setPlayerProfile(entry.profile());
                }
                return;
            }
        }
        PlayerProfile local = resolveLocally(owner.trim());
        if (local != null && local.hasTextures()) {
            remember(key, local, now + FOUND_MILLIS);
            meta.setPlayerProfile(local);
            return;
        }
        if (local != null && local.getId() != null) {
            meta.setPlayerProfile(local);
        }
        fetch(key, owner.trim(), local, onArrive, warn);
    }

    /** Forgets every skin, so owners are looked up again. */
    static void clear() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }

    /** For tests: the clock deciding when entries expire. */
    static void clock(LongSupplier source) {
        clock = source;
    }

    /** Whether a skin (or a miss) for {@code owner} is cached and fresh. */
    static boolean cached(String owner) {
        synchronized (LOCK) {
            Entry entry = CACHE.get(owner.trim().toLowerCase(Locale.ROOT));
            return entry != null && entry.expires() > clock.getAsLong();
        }
    }

    static void remember(String key, PlayerProfile profile, long expires) {
        synchronized (LOCK) {
            CACHE.put(key, new Entry(profile, expires));
        }
    }

    /** A UUID, an online player or a name in the server's cache; null when none of those. Never uses the network. */
    private static PlayerProfile resolveLocally(String owner) {
        UUID id = uuidOrNull(owner);
        if (id != null) {
            PlayerProfile profile = Bukkit.createProfile(id);
            profile.completeFromCache(false);
            return profile;
        }
        Player online = Bukkit.getPlayerExact(owner);
        if (online != null) {
            return online.getPlayerProfile();
        }
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(owner);
        if (known != null) {
            PlayerProfile profile = Bukkit.createProfile(known.getUniqueId(), known.getName());
            profile.completeFromCache(false);
            return profile;
        }
        return null;
    }

    static UUID uuidOrNull(String text) {
        if (text.length() != 36 || text.charAt(8) != '-') {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private static void fetch(String key, String owner, PlayerProfile local, Consumer<String> onArrive,
                              Consumer<String> warn) {
        synchronized (LOCK) {
            List<Consumer<String>> waiting = IN_FLIGHT.get(key);
            if (waiting != null) {
                if (onArrive != null) {
                    waiting.add(onArrive);
                }
                return;
            }
            waiting = new ArrayList<>(2);
            if (onArrive != null) {
                waiting.add(onArrive);
            }
            IN_FLIGHT.put(key, waiting);
        }
        CompletableFuture<PlayerProfile> future;
        try {
            PlayerProfile request = local != null ? local : Bukkit.createProfile(owner);
            future = request.update();
        } catch (RuntimeException | LinkageError refused) {
            future = CompletableFuture.failedFuture(refused);
        }
        future.whenComplete((profile, error) -> onMain(() -> arrived(key, owner, profile, error, warn)));
    }

    private static void arrived(String key, String owner, PlayerProfile profile, Throwable error, Consumer<String> warn) {
        List<Consumer<String>> waiting;
        long now = clock.getAsLong();
        boolean found = error == null && profile != null && profile.hasTextures();
        synchronized (LOCK) {
            waiting = IN_FLIGHT.remove(key);
            CACHE.put(key, found ? new Entry(profile, now + FOUND_MILLIS) : new Entry(null, now + MISSING_MILLIS));
        }
        if (!found) {
            warn.accept("Could not find the skin of skull-owner '" + owner + "'; showing a plain head.");
            return;
        }
        if (waiting != null) {
            for (Consumer<String> callback : waiting) {
                try {
                    callback.accept(owner);
                } catch (RuntimeException e) {
                    warn.accept("A skin refresh callback failed for '" + owner + "': " + e);
                }
            }
        }
    }

    /** Runs {@code task} on the main thread through RsLib's scheduler, or here when RsLib is not running. */
    private static void onMain(Runnable task) {
        RsLibPlugin rslib = RsLibPlugin.running();
        if (rslib == null || !rslib.isEnabled()) {
            task.run();
            return;
        }
        Hop hop = mainHop;
        if (hop == null || hop.owner() != rslib) {
            hop = new Hop(rslib, new RsScheduler(rslib));
            mainHop = hop;
        }
        try {
            hop.scheduler().sync(task);
        } catch (RuntimeException disabled) {
            task.run();
        }
    }

    /** Forgets every skin and pending fetch (RsLib is going away). */
    static void shutdown() {
        mainHop = null;
        synchronized (LOCK) {
            IN_FLIGHT.clear();
            CACHE.clear();
        }
    }
}
