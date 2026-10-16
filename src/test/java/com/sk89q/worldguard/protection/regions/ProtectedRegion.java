package com.sk89q.worldguard.protection.regions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.protection.flags.StateFlag;

/** Test stand-in for a WorldGuard region: a box in one world, a priority, flags and members. */
public final class ProtectedRegion {

    private final String id;
    private final String world;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    public final int priority;
    public final Map<StateFlag, StateFlag.State> flags = new HashMap<>();
    public final Set<UUID> members = new HashSet<>();

    public ProtectedRegion(String id, String world, int minX, int minZ, int maxX, int maxZ, int priority) {
        this.id = id;
        this.world = world;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.priority = priority;
    }

    public String getId() {
        return id;
    }

    public boolean contains(BukkitAdapter.Spot at) {
        return at.world().equals(world) && at.x() >= minX && at.x() <= maxX && at.z() >= minZ && at.z() <= maxZ;
    }
}
