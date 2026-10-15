package com.rs.lib.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import com.rs.lib.region.Cuboid;

/**
 * A player finished a selection of {@code rs().selections()}: both positions are set, in the
 * same world, and within the spec's maximum volume. It is the only event of the selection
 * module (there is none per click).
 *
 * <p>Cancelling it skips the spec's {@code onSelect} and keeps the session open, so the player
 * can click again.</p>
 */
public final class RsSelectionCompleteEvent extends RsPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Plugin owner;
    private final String specId;
    private final Cuboid cuboid;

    public RsSelectionCompleteEvent(Player player, Plugin owner, String specId, Cuboid cuboid) {
        super(player);
        this.owner = owner;
        this.specId = specId;
        this.cuboid = cuboid;
    }

    /** The plugin whose selection this is. */
    public Plugin owner() {
        return owner;
    }

    public String specId() {
        return specId;
    }

    public Cuboid cuboid() {
        return cuboid;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
