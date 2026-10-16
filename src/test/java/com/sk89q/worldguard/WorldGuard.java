package com.sk89q.worldguard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * Test stand-in for WorldGuard 7's entry point, with the classes reached from it nested here.
 * {@link #REGIONS} and {@link #FLAGS} are the server's state.
 */
public final class WorldGuard {

    public static final List<ProtectedRegion> REGIONS = new ArrayList<>();
    public static final Map<String, Object> FLAGS = new HashMap<>();
    /** Set to make every query throw, as a broken WorldGuard would. */
    public static volatile boolean broken;

    private static final WorldGuard INSTANCE = new WorldGuard();

    public static WorldGuard getInstance() {
        return INSTANCE;
    }

    public Platform getPlatform() {
        return new Platform();
    }

    public Registry getFlagRegistry() {
        return new Registry();
    }

    public static final class Platform {
        public Container getRegionContainer() {
            return new Container();
        }
    }

    public static final class Container {
        public Query createQuery() {
            return new Query();
        }
    }

    public static final class Registry {
        public Object get(String name) {
            return FLAGS.get(name);
        }
    }

    public static final class Query {

        public Iterable<ProtectedRegion> getApplicableRegions(BukkitAdapter.Spot at) {
            if (broken) {
                throw new IllegalStateException("broken");
            }
            List<ProtectedRegion> found = new ArrayList<>();
            for (ProtectedRegion region : REGIONS) {
                if (region.contains(at)) {
                    found.add(region);
                }
            }
            found.sort(Comparator.comparingInt((ProtectedRegion r) -> r.priority).reversed());
            return found;
        }

        /** The highest-priority region setting the flag decides; a member is always allowed. */
        public StateFlag.State queryState(BukkitAdapter.Spot at, RegionAssociable subject, StateFlag... flags) {
            for (ProtectedRegion region : getApplicableRegions(at)) {
                StateFlag.State value = region.flags.get(flags[0]);
                if (value == null) {
                    continue;
                }
                if (subject instanceof WorldGuardPlugin.LocalPlayer player && region.members.contains(player.id())) {
                    return StateFlag.State.ALLOW;
                }
                return value;
            }
            return null;
        }
    }
}
