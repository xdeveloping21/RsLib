package com.rs.lib.hologram;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Puts holograms back when their chunk loads. Shared by every plugin and registered once by
 * RsLib.
 *
 * <p>RsLib's holograms are never saved with the chunk: unloading the chunk removes the entity,
 * and loading it spawns a new one on the next tick. Nothing is ever read back from the world,
 * so this listener never looks at existing entities (another plugin's or SnLib's) at all.</p>
 *
 * <p>The index goes world name, then packed chunk, then the holograms there. A chunk load
 * with no hologram costs two map lookups and allocates nothing.</p>
 */
final class HologramChunkListener implements Listener {

    static final HologramChunkListener INSTANCE = new HologramChunkListener();

    private final Map<String, Map<Long, Set<Hologram>>> byWorld = new ConcurrentHashMap<>();

    private HologramChunkListener() {
    }

    void add(Hologram hologram) {
        // Added inside compute, so a concurrent remove cannot drop the set this lands in.
        byWorld.computeIfAbsent(hologram.world, w -> new ConcurrentHashMap<>())
                .compute(hologram.chunkKey(), (k, set) -> {
                    Set<Hologram> here = set == null ? ConcurrentHashMap.newKeySet() : set;
                    here.add(hologram);
                    return here;
                });
    }

    void remove(Hologram hologram) {
        Map<Long, Set<Hologram>> chunks = byWorld.get(hologram.world);
        if (chunks == null) {
            return;
        }
        chunks.computeIfPresent(hologram.chunkKey(), (k, set) -> {
            set.remove(hologram);
            return set.isEmpty() ? null : set;
        });
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
