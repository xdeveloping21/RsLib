package com.sk89q.worldedit.bukkit;

import org.bukkit.Location;

/** Test stand-in for WorldEdit's adapter: only what RsLib's WorldGuard bridge calls. */
public final class BukkitAdapter {

    private BukkitAdapter() {
    }

    /** WorldEdit's own location type, reduced to a world name and a block. */
    public record Spot(String world, int x, int y, int z) {
    }

    public static Spot adapt(Location location) {
        return new Spot(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
