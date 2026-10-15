package com.rs.lib.leaderboard;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;

import com.rs.lib.Rs;
import com.rs.lib.scheduler.TaskHandle;

/** What {@link LeaderboardCache} needs from the server; lets its logic run in tests. */
interface BoardHost {

    Logger logger();

    /** Runs {@code task} on the main thread from the next tick, every {@code periodTicks}; throws when it cannot. */
    TaskHandle every(long periodTicks, Runnable task);

    void debug(Supplier<String> message);

    /**
     * Registers the expansion {@code identifier} with a global {@code top_} prefix and a player
     * {@code pos_} prefix; false when PlaceholderAPI did not take it now.
     */
    boolean expose(String identifier, Function<String, String> top, BiFunction<OfflinePlayer, String, String> position);

    static BoardHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements BoardHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public TaskHandle every(long periodTicks, Runnable task) {
            return rs.scheduler().timer(1L, periodTicks, task);
        }

        @Override
        public void debug(Supplier<String> message) {
            rs.debug().log(message);
        }

        @Override
        public boolean expose(String identifier, Function<String, String> top,
                BiFunction<OfflinePlayer, String, String> position) {
            return rs.papi().expansion(identifier)
                    .globalPrefixed("top_", top)
                    .prefixed("pos_", position)
                    .register();
        }
    }
}
