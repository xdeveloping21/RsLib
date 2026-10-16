package com.rs.lib.hologram;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import com.rs.lib.Rs;
import com.rs.lib.compat.RsVersion;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;

/** What {@link HologramUtil} needs from the server and the plugin; lets its logic run in tests. */
interface HologramHost {

    Logger logger();

    /** The owning plugin's name, which goes into the mark. */
    String ownerName();

    /** Hides {@code entity} from {@code player} through the owning plugin's entity visibility. */
    void hide(Player player, Entity entity);

    /** Undoes {@link #hide} for the owning plugin. */
    void show(Player player, Entity entity);

    /** The loaded world with this name, or null. */
    World world(String name);

    /** True when the calling thread may touch the chunk at {@code location} (the main thread on Paper). */
    boolean ownsHere(Location location);

    /** True when the calling thread may touch {@code entity} (the main thread on Paper). */
    boolean ownsHere(Entity entity);

    /** Runs {@code task} on the thread that owns the chunk at {@code location}; throws when it cannot be scheduled. */
    void at(Location location, Runnable task);

    /** Runs {@code task} on the thread that owns {@code entity}; throws when it cannot be scheduled. */
    void onEntity(Entity entity, Runnable task);

    /**
     * Repeats {@code task} on the main thread (global region on Folia), through the plugin's
     * shared ticker; throws when it cannot be scheduled.
     */
    TaskHandle every(long periodTicks, Runnable task);

    /** PlaceholderAPI with no viewer; the same instance when nothing was resolved. */
    String placeholders(String line);

    /** The online player with this id, or null. */
    Player player(UUID id);

    /** Spawns a bare text display at {@code location}; called on the thread that owns that chunk. */
    TextDisplay spawn(Location location);

    /**
     * True when a player of world {@code world}, other than those in {@code except}, stands
     * within {@code radiusChunks} chunks (a square) of chunk {@code chunkX}, {@code chunkZ}. True
     * as well when it cannot tell, so a hologram is never left unrefreshed by mistake.
     */
    boolean anyoneNear(String world, int chunkX, int chunkZ, int radiusChunks, Set<UUID> except);

    /** Moves {@code display} to {@code to}; called on the thread that owns it. */
    void teleport(TextDisplay display, Location to);

    /** Sets how many ticks the client takes to glide {@code display} to a new place; false when the server cannot. */
    boolean teleportDuration(TextDisplay display, int ticks);

    static HologramHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements HologramHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public String ownerName() {
            return rs.plugin().getName();
        }

        @Override
        public void hide(Player player, Entity entity) {
            player.hideEntity(rs.plugin(), entity);
        }

        @Override
        public void show(Player player, Entity entity) {
            player.showEntity(rs.plugin(), entity);
        }

        @Override
        public World world(String name) {
            return Bukkit.getWorld(name);
        }

        @Override
        public boolean ownsHere(Location location) {
            return rs.scheduler().ownsHere(location);
        }

        @Override
        public boolean ownsHere(Entity entity) {
            return rs.scheduler().ownsHere(entity);
        }

        @Override
        public void at(Location location, Runnable task) {
            rs.scheduler().at(location, task);
        }

        @Override
        public void onEntity(Entity entity, Runnable task) {
            rs.scheduler().entity(entity, task);
        }

        @Override
        public TaskHandle every(long periodTicks, Runnable task) {
            return rs.scheduler().sharedTimer(periodTicks, task);
        }

        @Override
        public String placeholders(String line) {
            String resolved = rs.papi().apply(null, line);
            return resolved == line ? line : RsText.normalizePapiOutput(resolved);
        }

        @Override
        public Player player(UUID id) {
            return Bukkit.getPlayer(id);
        }

        /**
         * The two-argument spawn links on every supported version (the overload taking a
         * consumer changed type in 1.20.2); the entity is set up right after, in the same tick,
         * before any player is sent it.
         */
        @Override
        public TextDisplay spawn(Location location) {
            return location.getWorld().spawn(location, TextDisplay.class);
        }

        /** Reads each player's position into one scratch location; the first one near answers. */
        @Override
        public boolean anyoneNear(String world, int chunkX, int chunkZ, int radiusChunks, Set<UUID> except) {
            try {
                World in = Bukkit.getWorld(world);
                if (in == null) {
                    return true;
                }
                Location scratch = new Location(in, 0, 0, 0);
                for (Player player : in.getPlayers()) {
                    player.getLocation(scratch);
                    if (HologramUtil.within((scratch.getBlockX() >> 4) - chunkX, (scratch.getBlockZ() >> 4) - chunkZ,
                            radiusChunks) && !except.contains(player.getUniqueId())) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException cannotTell) {
                return true;
            }
        }

        /** Folia only teleports asynchronously; Paper moves the entity now, in this tick. */
        @Override
        public void teleport(TextDisplay display, Location to) {
            if (RsVersion.isFolia()) {
                display.teleportAsync(to);
            } else {
                display.teleport(to);
            }
        }

        @Override
        public boolean teleportDuration(TextDisplay display, int ticks) {
            return DisplayMoves.setTeleportDuration(display, ticks);
        }
    }
}
