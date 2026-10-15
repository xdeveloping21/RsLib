package com.rs.lib.snapshot;

import java.nio.file.Path;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.rs.lib.Rs;

/** What {@link Snapshots} needs from the server and the plugin; lets its logic run in tests. */
interface SnapshotHost {

    Logger logger();

    /** {@code plugins/<Plugin>/snapshots}. */
    Path folder();

    boolean shuttingDown();

    /** True when the calling thread owns {@code player} (the main thread on Paper). */
    boolean ownsHere(Player player);

    /** True when {@code player} is online and is the same object the server holds for that UUID. */
    boolean present(Player player);

    /** Runs {@code task} off the main thread; throws when it cannot be scheduled. */
    void async(Runnable task);

    /** Runs {@code task} on the main thread (global region on Folia); throws when it cannot be scheduled. */
    void main(Runnable task);

    /**
     * Runs {@code task} on the thread that owns {@code player}, or {@code gone} when that task
     * can never run (Folia, the player left); throws when it cannot be scheduled.
     */
    void runFor(Player player, Runnable task, Runnable gone);

    SnapshotCodec codec();

    static SnapshotHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements SnapshotHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public Path folder() {
            return rs.plugin().getDataFolder().toPath().resolve("snapshots");
        }

        @Override
        public boolean shuttingDown() {
            return rs.isShuttingDown();
        }

        @Override
        public boolean ownsHere(Player player) {
            return rs.scheduler().ownsHere(player);
        }

        @Override
        public boolean present(Player player) {
            return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player;
        }

        @Override
        public void async(Runnable task) {
            rs.scheduler().async(task);
        }

        @Override
        public void main(Runnable task) {
            rs.scheduler().sync(task);
        }

        @Override
        public void runFor(Player player, Runnable task, Runnable gone) {
            rs.scheduler().entity(player, task, gone);
        }

        @Override
        public SnapshotCodec codec() {
            return SnapshotCodec.SERVER;
        }
    }
}
