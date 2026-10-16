package com.rs.lib.block;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.block.Block;

/**
 * Every clickable block of every plugin, by position, and the last click of each player (for
 * the repeat guard). One instance serves the server ({@link #SHARED}). Thread-safe; a server
 * with no clickable blocks pays one emptiness check per event.
 */
final class BlockIndex {

    static final BlockIndex SHARED = new BlockIndex(System::currentTimeMillis);

    /** A second click of the same button on the same block within this time is the same click. */
    static final long REPEAT_MILLIS = 200L;

    private final Map<BlockPos, RsBlock> blocks = new ConcurrentHashMap<>();
    private final Map<UUID, LastClick> lastClicks = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    private record LastClick(BlockPos pos, boolean left, long at) {
    }

    BlockIndex(LongSupplier clock) {
        this.clock = clock;
    }

    boolean isEmpty() {
        return blocks.isEmpty();
    }

    int size() {
        return blocks.size();
    }

    RsBlock get(BlockPos pos) {
        return blocks.get(pos);
    }

    /** The registration at {@code block}, or null. */
    RsBlock at(Block block) {
        if (block == null || blocks.isEmpty()) {
            return null;
        }
        return blocks.get(BlockPos.of(block));
    }

    /** Stores {@code block}, returning the registration it replaced (or null). */
    RsBlock put(RsBlock block) {
        return blocks.put(block.pos(), block);
    }

    /** Removes {@code block} only if it is still the one at its position. */
    boolean remove(RsBlock block) {
        return blocks.remove(block.pos(), block);
    }

    /** True when any of {@code moved} is a clickable block. */
    boolean anyOf(List<Block> moved) {
        if (blocks.isEmpty()) {
            return false;
        }
        for (Block block : moved) {
            if (at(block) != null) {
                return true;
            }
        }
        return false;
    }

    /** Takes the clickable blocks out of an explosion's block list; the rest still blows up. */
    void spare(List<Block> blown) {
        if (blocks.isEmpty()) {
            return;
        }
        for (Iterator<Block> it = blown.iterator(); it.hasNext(); ) {
            if (at(it.next()) != null) {
                it.remove();
            }
        }
    }

    /**
     * True when {@code player} clicked the same button on the same block less than
     * {@value #REPEAT_MILLIS} ms ago (a held button or both hands). Records this click either way.
     */
    boolean repeated(UUID player, BlockPos pos, boolean left) {
        long now = clock.getAsLong();
        LastClick last = lastClicks.put(player, new LastClick(pos, left, now));
        return last != null && last.left == left && last.pos.equals(pos) && now - last.at < REPEAT_MILLIS;
    }

    /** The player left: their last click is forgotten. */
    void forget(UUID player) {
        lastClicks.remove(player);
    }

    /** Players with a remembered click; for tests. */
    int clicksRemembered() {
        return lastClicks.size();
    }
}
