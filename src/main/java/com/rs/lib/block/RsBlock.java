package com.rs.lib.block;

import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * A clickable block registered with {@code rs().blocks()}: the handle {@link RsBlocks.Builder#register()}
 * returns. A plugin that keeps its blocks while it is enabled can ignore it.
 */
public final class RsBlock {

    private final RsBlocks owner;
    private final BlockPos pos;
    private final Consumer<ClickedBlock> left;
    private final Consumer<ClickedBlock> right;

    RsBlock(RsBlocks owner, BlockPos pos, Consumer<ClickedBlock> left, Consumer<ClickedBlock> right) {
        this.owner = owner;
        this.pos = pos;
        this.left = left;
        this.right = right;
    }

    /** The name of the plugin that registered it. */
    public String owner() {
        return owner.ownerName();
    }

    public String worldName() {
        return pos.world();
    }

    public int x() {
        return pos.x();
    }

    public int y() {
        return pos.y();
    }

    public int z() {
        return pos.z();
    }

    /** The block's corner; the world is null when it is not loaded. */
    public Location location() {
        return new Location(Bukkit.getServer() == null ? null : Bukkit.getWorld(pos.world()), pos.x(), pos.y(), pos.z());
    }

    /** False once unregistered, or replaced by another registration at the same block. */
    public boolean isRegistered() {
        return owner.index().get(pos) == this;
    }

    /** Takes this registration down (never one that replaced it); false when it was not standing. */
    public boolean unregister() {
        return owner.drop(this);
    }

    /** Another registration replaced this one: its plugin no longer counts it. */
    void forgetOwner() {
        owner.forget(this);
    }

    BlockPos pos() {
        return pos;
    }

    /** Runs the handler of the button; a handler that throws is reported and costs only its own click. */
    void click(ClickedBlock click) {
        Consumer<ClickedBlock> handler = click.left() ? left : right;
        if (handler == null) {
            return;
        }
        try {
            handler.accept(click);
        } catch (RuntimeException e) {
            owner.logger().log(Level.WARNING, "The " + (click.left() ? "left" : "right") + " click handler of the"
                    + " block at " + pos + " failed", e);
        }
    }

    @Override
    public String toString() {
        return "RsBlock[" + owner() + ' ' + pos + ']';
    }
}
