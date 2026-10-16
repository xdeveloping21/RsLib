package com.rs.lib.notice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.db.RsDb;
import com.rs.lib.scheduler.TaskHandle;

/** What {@link Notices} needs from the server and the plugin; lets its logic run in tests. */
interface NoticeHost {

    Logger logger();

    long now();

    NoticeSettings settings();

    /** The plugin's database; throws when the plugin did not declare {@code db()}. */
    RsDb db();

    /** True when {@code player} is online on this server. Any thread. */
    boolean isOnline(UUID player);

    /**
     * Runs {@code task} on the thread that owns {@code player}, or {@code retired} when it never
     * will (the player left first). Returns false, running neither, when the player is not online
     * or nothing can be scheduled (the plugin is disabling).
     */
    boolean runFor(UUID player, Runnable task, Runnable retired);

    /** Runs {@code task} on the main thread after {@code ticks}; throws when it cannot be scheduled. */
    TaskHandle later(long ticks, Runnable task);

    /** Repeats {@code task} off the main thread; throws when it cannot be scheduled. */
    TaskHandle every(long delayTicks, long periodTicks, Runnable task);

    /** Calls {@code joined} on the main thread with the id of every player who joins, until {@link #stop()}. */
    void listenJoins(Consumer<UUID> joined);

    /** The players online now. */
    Collection<UUID> online();

    /** Sends the message {@code key} of the plugin's language file; on the player's thread. */
    void show(UUID player, String key, Ph... phs);

    /** Stops the join listener. */
    void stop();

    static NoticeHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    final class Server implements NoticeHost {

        private final Rs rs;
        private volatile Listener listener;

        Server(Rs rs) {
            this.rs = rs;
        }

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public NoticeSettings settings() {
            return NoticeSettings.current();
        }

        @Override
        public RsDb db() {
            return rs.db();
        }

        @Override
        public boolean isOnline(UUID player) {
            Player online = Bukkit.getPlayer(player);
            return online != null && online.isOnline();
        }

        @Override
        public boolean runFor(UUID player, Runnable task, Runnable retired) {
            Player online = Bukkit.getPlayer(player);
            if (online == null || rs.isShuttingDown()) {
                return false;
            }
            try {
                rs.scheduler().entity(online, task, retired);
                return true;
            } catch (RuntimeException disabled) {
                return false;
            }
        }

        @Override
        public TaskHandle later(long ticks, Runnable task) {
            return rs.scheduler().syncLater(ticks, task);
        }

        @Override
        public TaskHandle every(long delayTicks, long periodTicks, Runnable task) {
            return rs.scheduler().timerAsync(delayTicks, periodTicks, task);
        }

        @Override
        public void listenJoins(Consumer<UUID> joined) {
            Listener created = new JoinListener(joined);
            rs.plugin().getServer().getPluginManager().registerEvents(created, rs.plugin());
            listener = created;
        }

        @Override
        public Collection<UUID> online() {
            List<UUID> ids = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                ids.add(player.getUniqueId());
            }
            return ids;
        }

        @Override
        public void show(UUID player, String key, Ph... phs) {
            Player online = Bukkit.getPlayer(player);
            if (online != null) {
                rs.lang().send(online, key, phs);
            }
        }

        @Override
        public void stop() {
            Listener current = listener;
            listener = null;
            if (current != null) {
                HandlerList.unregisterAll(current);
            }
        }
    }

    /** One plugin's join listener, registered with that plugin while its notices run. */
    final class JoinListener implements Listener {

        private final Consumer<UUID> joined;

        JoinListener(Consumer<UUID> joined) {
            this.joined = joined;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            joined.accept(event.getPlayer().getUniqueId());
        }
    }
}
