package com.rs.lib.hologram;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import com.rs.lib.scheduler.TaskHandle;

import net.kyori.adventure.text.Component;

/**
 * One hologram: where it stands, what it says and the entity that shows it while its chunk is
 * loaded. The place is kept as a world name and coordinates, so a world that is unloaded and
 * loaded again is found by name; a move replaces it whole. Entity work is done while holding
 * this object's lock, on the thread that owns the chunk.
 */
final class Hologram {

    final HologramUtil owner;
    final String id;
    /** Players this hologram is hidden from; applied again to every new entity. */
    final Set<UUID> hiddenFrom = ConcurrentHashMap.newKeySet();

    /** Where it stands; replaced whole by a move. */
    volatile Place place;
    volatile List<String> lines;
    volatile Display.Billboard billboard = Display.Billboard.CENTER;
    /** The view range given with {@code setViewRange}; 0 is the entity's own default (1). */
    volatile float viewRange;
    /** The teleport duration last given to the entity, or -1 when it was never set. */
    volatile int teleportTicks = -1;
    /** The entity while it exists; null before the first spawn and after delete. */
    volatile TextDisplay entity;
    /** The text last given to {@link #entity}. */
    volatile Component shown;
    /** True when a refresh was skipped because nobody was near: {@link #shown} may be old. */
    volatile boolean stale;
    volatile TaskHandle refresh;
    volatile boolean deleted;
    /** Start of the current window of respawns after removals by something else, and how many there were. */
    private long respawnsSince;
    private int respawns;

    Hologram(HologramUtil owner, String id, Location at, List<String> lines) {
        this.owner = owner;
        this.id = id;
        this.place = Place.of(at);
        this.lines = lines;
    }

    /** A world name, coordinates and the chunk they fall in. */
    record Place(String world, double x, double y, double z, float yaw, float pitch, int chunkX, int chunkZ) {

        static Place of(Location at) {
            return new Place(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(),
                    at.getBlockX() >> 4, at.getBlockZ() >> 4);
        }

        Location location(World in) {
            return new Location(in, x, y, z, yaw, pitch);
        }

        long chunkKey() {
            return Hologram.chunkKey(chunkX, chunkZ);
        }

        boolean sameChunk(Place other) {
            return world.equals(other.world) && chunkX == other.chunkX && chunkZ == other.chunkZ;
        }
    }

    String world() {
        return place.world();
    }

    Location location(World in) {
        return place.location(in);
    }

    /** {@code <Plugin>:<id>}, the value of the mark on the entity. */
    String mark() {
        return owner.ownerName() + ":" + id;
    }

    /** Chunk coordinates packed into one long (x low, z high). */
    static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    /**
     * Counts one respawn after a removal by something else; false once there were more than
     * {@link HologramUtil#MAX_RESPAWNS} within {@link HologramUtil#RESPAWN_WINDOW_NANOS}. Caller
     * holds this object's lock.
     */
    boolean countRespawn(long now) {
        if (respawns == 0 || now - respawnsSince > HologramUtil.RESPAWN_WINDOW_NANOS) {
            respawnsSince = now;
            respawns = 0;
        }
        respawns++;
        return respawns <= HologramUtil.MAX_RESPAWNS;
    }

    static boolean alive(TextDisplay display) {
        return display != null && display.isValid();
    }
}
