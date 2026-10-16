package com.rs.lib.block;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Listener;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.action.ActionList;

/**
 * Blocks that answer clicks instead of behaving like their material, reached with
 * {@code rs().blocks()}: a crate, a shop, a quest board.
 *
 * <pre>
 * rs().blocks().at(crate.location())
 *         .onRight(click -&gt; open(click.player(), crate))
 *         .onLeft(click -&gt; preview(click.player(), crate))
 *         .register();
 *
 * rs().blocks().at(location).rightActions(List.of("[message] &amp;aHello {player}")).register();
 * </pre>
 *
 * <ul>
 *   <li><b>Nothing is saved.</b> Where a plugin's blocks are is that plugin's data: register
 *   them again on enable, from what it loads. Shutting the plugin down takes all of them down
 *   (the blocks stay in the world).</li>
 *   <li>A registered block cannot be broken by players, blown up (it is taken out of the
 *   explosion, the rest still blows up), moved by a piston (the piston is stopped) or burnt.
 *   Nor can the world change it: no mob or falling block (endermen, silverfish, sheep eating
 *   grass, sand that would fall), no water or lava flowing into it, no melting, fading,
 *   freezing, forming, spreading onto it or leaf decay. Block physics is not watched: a
 *   registered torch, button or sign still drops when the block holding it goes, because a
 *   physics listener would make the server build an event for every block update.</li>
 *   <li>Its vanilla interaction is cancelled: a barrel does not open, a button does not press,
 *   nothing is placed against it, and the item in hand is not used on it. Clicks are handled
 *   at the lowest priority, even when another plugin cancelled them already.</li>
 *   <li>Only the main hand calls a handler, and the same button on the same block within
 *   200 ms counts once (a held button, both hands).</li>
 *   <li>One block, one registration: registering where another registration (of any plugin)
 *   stands replaces it.</li>
 *   <li>A location means the block that contains it; no chunk is loaded to register.</li>
 *   <li>Handlers run on the player's thread. One that throws is reported and costs only its
 *   own click.</li>
 * </ul>
 */
public final class RsBlocks {

    private static final Listener LISTENER = new BlockListener(BlockIndex.SHARED);

    /** Turns an action list into a handler for the block at a position. */
    @FunctionalInterface
    interface ActionCompiler {
        Consumer<ClickedBlock> compile(List<String> actions, BlockPos pos);
    }

    private final String ownerName;
    private final Logger logger;
    private final ActionCompiler actions;
    private final BlockIndex index;
    private final Set<RsBlock> mine = ConcurrentHashMap.newKeySet();

    /** Created by the context. */
    public RsBlocks(Rs rs) {
        this(rs.plugin().getName(), rs.plugin().getLogger(), (lines, pos) -> {
            ActionList list = rs.actions().compile(lines, "block " + pos);
            Ph[] phs = {Ph.of("world", pos.world()), Ph.of("x", pos.x()), Ph.of("y", pos.y()), Ph.of("z", pos.z())};
            return click -> list.run(click.player(), phs);
        }, BlockIndex.SHARED);
    }

    RsBlocks(String ownerName, Logger logger, ActionCompiler actions, BlockIndex index) {
        this.ownerName = Objects.requireNonNull(ownerName, "ownerName");
        this.logger = logger;
        this.actions = actions;
        this.index = index;
    }

    /** The listener every plugin shares; RsLib registers it. */
    public static Listener sharedListener() {
        return LISTENER;
    }

    /**
     * Starts a registration at the block containing {@code location}; nothing happens until
     * {@link Builder#register()}.
     *
     * @throws IllegalArgumentException when the location has no world
     */
    public Builder at(Location location) {
        return new Builder(BlockPos.of(location));
    }

    /** Starts a registration at {@code block}. */
    public Builder at(Block block) {
        return new Builder(BlockPos.of(Objects.requireNonNull(block, "block")));
    }

    /** Starts a registration at block {@code (x, y, z)} of the world named {@code world} (loaded or not). */
    public Builder at(String world, int x, int y, int z) {
        return new Builder(new BlockPos(world, x, y, z));
    }

    /** This plugin's registration at the block containing {@code location}, or null (also when another plugin owns it). */
    public RsBlock registered(Location location) {
        RsBlock found = index.get(BlockPos.of(location));
        return found != null && mine.contains(found) ? found : null;
    }

    /** True when any plugin registered the block. */
    public boolean isRegistered(Block block) {
        return index.at(block) != null;
    }

    /** Takes down this plugin's registration at {@code location}; false when there was none. */
    public boolean unregister(Location location) {
        RsBlock found = registered(location);
        return found != null && drop(found);
    }

    /** How many blocks this plugin has registered. */
    public int count() {
        int standing = 0;
        for (RsBlock block : mine) {
            if (block.isRegistered()) {
                standing++;
            }
        }
        return standing;
    }

    /** Takes down every block of this plugin; what a reload does before registering them again. */
    public void unregisterAll() {
        for (RsBlock block : mine) {
            drop(block);
        }
    }

    /** Called by the context when the plugin shuts down. */
    public void shutdown() {
        unregisterAll();
    }

    String ownerName() {
        return ownerName;
    }

    Logger logger() {
        return logger;
    }

    BlockIndex index() {
        return index;
    }

    void forget(RsBlock block) {
        mine.remove(block);
    }

    boolean drop(RsBlock block) {
        mine.remove(block);
        return index.remove(block);
    }

    /** One registration being built. */
    public final class Builder {

        private final BlockPos pos;
        private Consumer<ClickedBlock> left;
        private Consumer<ClickedBlock> right;

        private Builder(BlockPos pos) {
            this.pos = pos;
        }

        /** What a left click does; without one a left click only does nothing. */
        public Builder onLeft(Consumer<ClickedBlock> handler) {
            this.left = handler;
            return this;
        }

        /** What a right click does; without one a right click only does nothing. */
        public Builder onRight(Consumer<ClickedBlock> handler) {
            this.right = handler;
            return this;
        }

        /** The same handler for both buttons. */
        public Builder onClick(Consumer<ClickedBlock> handler) {
            this.left = handler;
            this.right = handler;
            return this;
        }

        /**
         * An action list ({@code rs().actions()}) for a left click, with {@code {world}},
         * {@code {x}}, {@code {y}} and {@code {z}} of the block. Replaces {@link #onLeft}.
         */
        public Builder leftActions(List<String> lines) {
            this.left = actions.compile(lines, pos);
            return this;
        }

        /** An action list for a right click; see {@link #leftActions}. Replaces {@link #onRight}. */
        public Builder rightActions(List<String> lines) {
            this.right = actions.compile(lines, pos);
            return this;
        }

        /** Puts the block in place, replacing whatever registration stood there. */
        public RsBlock register() {
            RsBlock block = new RsBlock(RsBlocks.this, pos, left, right);
            mine.add(block);
            RsBlock replaced = index.put(block);
            if (replaced != null) {
                replaced.forgetOwner();
            }
            return block;
        }
    }
}
