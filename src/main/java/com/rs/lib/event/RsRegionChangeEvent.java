package com.rs.lib.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

import com.rs.lib.region.RsRegion;

/**
 * A player left or entered regions registered with {@code rs().regions()}: one event per step,
 * with everything that step left and entered (a step can leave one region and enter another).
 *
 * <p>Fired by RsLib on the player's thread <b>after</b> the change is recorded, so it cannot be
 * cancelled: to keep a player out, teleport them back from the listener (that is a new step and
 * fires its own event). Joining the server inside a region does not fire it, and neither does
 * quitting; ask {@code rs().regions().at(location)} on join when that matters.</p>
 *
 * <p>The regions of every plugin are reported. A plugin reads its own with
 * {@link #left(Plugin)} and {@link #entered(Plugin)}, or checks {@link #involves(Plugin)} first.</p>
 */
public final class RsRegionChangeEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** What moved the player in or out. */
    public enum Cause {
        /** Walking, flying or riding into another block. */
        MOVE,
        /** A teleport (or a respawn) inside the same world. */
        TELEPORT,
        /** A teleport, portal or respawn into another world. */
        WORLD_CHANGE,
        /** A region was registered (or registered again with another box) around the player. */
        REGION_REGISTERED,
        /** A region the player was in was unregistered. */
        REGION_UNREGISTERED,
        /** The periodic check found the player somewhere no event reported. */
        CHECK
    }

    private final List<RsRegion> left;
    private final List<RsRegion> entered;
    private final Cause cause;
    private final Location location;

    public RsRegionChangeEvent(Player player, List<RsRegion> left, List<RsRegion> entered, Cause cause,
            Location location) {
        super(player);
        this.left = List.copyOf(left);
        this.entered = List.copyOf(entered);
        this.cause = Objects.requireNonNull(cause, "cause");
        this.location = location;
    }

    /** Every region left in this step, of any plugin, in registration order. */
    public List<RsRegion> left() {
        return left;
    }

    /** Every region entered in this step, of any plugin, in registration order. */
    public List<RsRegion> entered() {
        return entered;
    }

    /** The regions of {@code plugin} left in this step. */
    public List<RsRegion> left(Plugin plugin) {
        return ownedBy(left, plugin);
    }

    /** The regions of {@code plugin} entered in this step. */
    public List<RsRegion> entered(Plugin plugin) {
        return ownedBy(entered, plugin);
    }

    /** True when a region of {@code plugin} was left or entered. */
    public boolean involves(Plugin plugin) {
        String name = plugin.getName();
        for (RsRegion region : left) {
            if (region.owner().equals(name)) {
                return true;
            }
        }
        for (RsRegion region : entered) {
            if (region.owner().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public Cause cause() {
        return cause;
    }

    /** Where the player was judged to be (a copy): the destination of a move or teleport. */
    public Location location() {
        return location == null ? null : location.clone();
    }

    private static List<RsRegion> ownedBy(List<RsRegion> regions, Plugin plugin) {
        String name = plugin.getName();
        List<RsRegion> mine = new ArrayList<>(regions.size());
        for (RsRegion region : regions) {
            if (region.owner().equals(name)) {
                mine.add(region);
            }
        }
        return List.copyOf(mine);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
