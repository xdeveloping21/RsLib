package com.sk89q.worldguard.bukkit;

import java.util.UUID;

import org.bukkit.entity.Player;

import com.sk89q.worldguard.protection.association.RegionAssociable;

/** Test stand-in for the WorldGuard plugin: it only wraps players. */
public final class WorldGuardPlugin {

    private static final WorldGuardPlugin INSTANCE = new WorldGuardPlugin();

    /** A wrapped player. */
    public record LocalPlayer(UUID id) implements RegionAssociable {
    }

    public static WorldGuardPlugin inst() {
        return INSTANCE;
    }

    public LocalPlayer wrapPlayer(Player player) {
        return new LocalPlayer(player.getUniqueId());
    }
}
