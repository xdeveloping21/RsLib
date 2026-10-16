package com.rs.lib.region;

import java.util.Collection;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.event.RsRegionChangeEvent;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.scheduler.TaskHandle;

/** What {@link RegionTracker} needs from the server; lets its logic run in tests. */
interface RegionHost {

    /** The players online now. */
    Collection<? extends Player> online();

    /** True when the calling thread may touch {@code player} (always on Paper's main thread). */
    boolean ownsHere(Player player);

    /** Runs {@code task} soon on the thread that owns {@code player}; never if they left first. */
    void runFor(Player player, Runnable task);

    /** True when any plugin listens to {@link RsRegionChangeEvent}. */
    boolean listened();

    void fire(RsRegionChangeEvent event);

    /** Repeats {@code task} every {@code periodTicks} on the global thread; throws when it cannot be scheduled. */
    TaskHandle timer(long periodTicks, Runnable task);

    Logger logger();

    /** The real server, scheduling with RsLib's own scheduler. */
    static RegionHost server(JavaPlugin rslib) {
        RsScheduler scheduler = new RsScheduler(rslib);
        return new RegionHost() {

            @Override
            public Collection<? extends Player> online() {
                return Bukkit.getOnlinePlayers();
            }

            @Override
            public boolean ownsHere(Player player) {
                return scheduler.ownsHere(player);
            }

            @Override
            public void runFor(Player player, Runnable task) {
                scheduler.entity(player, task);
            }

            @Override
            public boolean listened() {
                return RsRegionChangeEvent.getHandlerList().getRegisteredListeners().length > 0;
            }

            @Override
            public void fire(RsRegionChangeEvent event) {
                Bukkit.getPluginManager().callEvent(event);
            }

            @Override
            public TaskHandle timer(long periodTicks, Runnable task) {
                return scheduler.timer(periodTicks, periodTicks, task);
            }

            @Override
            public Logger logger() {
                return rslib.getLogger();
            }
        };
    }
}
