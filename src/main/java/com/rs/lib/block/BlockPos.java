package com.rs.lib.block;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** A block position by world name: the key of the clickable block index. */
record BlockPos(String world, int x, int y, int z) {

    BlockPos {
        Objects.requireNonNull(world, "world");
    }

    static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /**
     * The block containing {@code location}, computed from its coordinates (no chunk is
     * loaded).
     *
     * @throws IllegalArgumentException when it has no world, or its world is unloaded
     */
    static BlockPos of(Location location) {
        Objects.requireNonNull(location, "location");
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("A clickable block needs a location with a world");
        }
        return new BlockPos(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public String toString() {
        return world + ';' + x + ';' + y + ';' + z;
    }
}
