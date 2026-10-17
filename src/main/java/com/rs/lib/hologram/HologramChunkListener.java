package com.rs.lib.hologram;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;

/**
 * Puts holograms back when their chunk loads. Shared by every plugin and registered once by
 * RsLib.
 *
 * <p>RsLib's holograms are never saved with the chunk: unloading the chunk removes the entity,
 * and loading it spawns a new one on the next tick. Nothing is ever read back from the world,
 * so this listener never looks at existing entities (another plugin's) at all.</p>
 *
 * <p>The index goes world name, then packed chunk, then the holograms there. A chunk load
 * with no hologram costs two map lookups and allocates nothing.</p>
 *
 * <p>It also renders, the moment a player starts tracking it, a hologram that skipped its
 * refreshes because nobody was near (see {@link HologramUtil#refreshEvery}).</p>
 */
final class HologramChunkListener implements Listener {

    static final HologramChunkListener INSTANCE = new HologramChunkListener();

    private final Map<String, Map<Long, Set<Hologram>>> byWorld = new ConcurrentHashMap<>();
    /** Entities whose hologram skipped a refresh while nobody was near, by entity id. */
    private final Map<UUID, Hologram> stale = new ConcurrentHashMap<>();

    private HologramChunkListener() {
    }

    void add(Hologram hologram, Hologram.Place place) {
        // Added inside compute, so a concurrent remove cannot drop the set this lands in.
        byWorld.computeIfAbsent(place.world(), w -> new ConcurrentHashMap<>())
                .compute(place.chunkKey(), (k, set) -> {
                    Set<Hologram> here = set == null ? ConcurrentHashMap.newKeySet() : set;
                    here.add(hologram);
                    return here;
                });
    }

    void remove(Hologram hologram, Hologram.Place place) {
        Map<Long, Set<Hologram>> chunks = byWorld.get(place.world());
        if (chunks == null) {
            return;
        }
        chunks.computeIfPresent(place.chunkKey(), (k, set) -> {
            set.remove(hologram);
            return set.isEmpty() ? null : set;
        });
    }

    /** {@code hologram}'s entity {@code entity} shows old text: render it when a player starts tracking it. */
    void stale(UUID entity, Hologram hologram) {
        stale.put(entity, hologram);
    }

    /** The entity no longer needs a render on tracking (rendered, replaced or removed). */
    void fresh(UUID entity) {
        if (entity != null && !stale.isEmpty()) {
            stale.remove(entity);
        }
    }

    /** Entities waiting for a render; for tests. */
    int staleCount() {
        return stale.size();
    }

    /** Number of holograms indexed in that chunk; for tests. */
    int countAt(String world, int chunkX, int chunkZ) {
        Map<Long, Set<Hologram>> chunks = byWorld.get(world);
        Set<Hologram> here = chunks == null ? null : chunks.get(Hologram.chunkKey(chunkX, chunkZ));
        return here == null ? 0 : here.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        chunkLoaded(event.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    /**
     * A player starts receiving an entity (it came in range, joined, teleported or respawned
     * near it, or it was shown again): a hologram that skipped refreshes while nobody was near
     * renders now, before the entity is sent. Every other entity costs one emptiness check, or
     * one map lookup while some hologram waits.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (stale.isEmpty()) {
            return;
        }
        tracked(event.getEntity().getUniqueId());
    }

    void tracked(UUID entity) {
        Hologram hologram = stale.get(entity);
        if (hologram != null) {
            hologram.owner.wake(hologram);
        }
    }

    void chunkLoaded(String world, int chunkX, int chunkZ) {
        if (byWorld.isEmpty()) {
            return;
        }
        Map<Long, Set<Hologram>> chunks = byWorld.get(world);
        if (chunks == null) {
            return;
        }
        Set<Hologram> here = chunks.get(Hologram.chunkKey(chunkX, chunkZ));
        if (here == null) {
            return;
        }
        for (Hologram hologram : here) {
            hologram.owner.chunkLoaded(hologram);
        }
    }
}
