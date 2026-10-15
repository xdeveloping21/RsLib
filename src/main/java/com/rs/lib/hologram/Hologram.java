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
 * loaded again is found by name. Entity work is done while holding this object's lock, on the
 * thread that owns the chunk.
 */
final class Hologram {

    final HologramUtil owner;
    final String id;
    final String world;
    final double x;
    final double y;
    final double z;
    final float yaw;
    final float pitch;
    final int chunkX;
    final int chunkZ;
    /** Players this hologram is hidden from; applied again to every new entity. */
    final Set<UUID> hiddenFrom = ConcurrentHashMap.newKeySet();

    volatile List<String> lines;
    volatile Display.Billboard billboard = Display.Billboard.CENTER;
    /** The entity while it exists; null before the first spawn and after delete. */
    volatile TextDisplay entity;
    /** The text last given to {@link #entity}. */
    volatile Component shown;
    volatile TaskHandle refresh;
    volatile boolean deleted;

    Hologram(HologramUtil owner, String id, Location at, List<String> lines) {
        this.owner = owner;
        this.id = id;
        this.world = at.getWorld().getName();
        this.x = at.getX();
        this.y = at.getY();
        this.z = at.getZ();
        this.yaw = at.getYaw();
        this.pitch = at.getPitch();
        this.chunkX = at.getBlockX() >> 4;
        this.chunkZ = at.getBlockZ() >> 4;
        this.lines = lines;
    }

    Location location(World in) {
        return new Location(in, x, y, z, yaw, pitch);
    }

    /** {@code <Plugin>:<id>}, the value of the mark on the entity. */
    String mark() {
        return owner.ownerName() + ":" + id;
    }

    /** Chunk coordinates packed into one long (x low, z high). */
    static long chunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    long chunkKey() {
        return chunkKey(chunkX, chunkZ);
    }

    static boolean alive(TextDisplay display) {
        return display != null && display.isValid();
    }
}
