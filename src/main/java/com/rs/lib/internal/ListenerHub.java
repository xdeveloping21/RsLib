package com.rs.lib.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Rs;
import com.rs.lib.RsLib;
import com.rs.lib.command.RsCommands;
import com.rs.lib.db.PlayerDataCache;
import com.rs.lib.hook.HookListener;
import com.rs.lib.input.Inputs;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.update.internal.UpdateHub;

/**
 * Registers, once, the listeners every Rs plugin shares. One listener per event serves all
 * plugins, so nothing is registered twice when a plugin is disabled and enabled again.
 */
public final class ListenerHub {

    private ListenerHub() {
    }

    public static void registerAll(JavaPlugin rslib) {
        var pm = rslib.getServer().getPluginManager();
        pm.registerEvents(new HookListener(), rslib);
        pm.registerEvents(new Shared(rslib), rslib);
        pm.registerEvents(RsCommands.priorityListener(), rslib);
        pm.registerEvents(PlayerDataCache.joinListener(), rslib);
        pm.registerEvents(Inputs.chatListener(), rslib);
    }

    private static final class Shared implements Listener {

        /** Ticks between a join and the update notices, so they are not lost among join messages. */
        private static final long UPDATE_NOTICE_DELAY = 40L;

        private final JavaPlugin rslib;
        private final RsScheduler scheduler;

        Shared(JavaPlugin rslib) {
            this.rslib = rslib;
            this.scheduler = new RsScheduler(rslib);
        }

        /** Pending update notices of every plugin (each checks its own permission). */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            if (!UpdateHub.hasNotices()) {
                return;
            }
            Player player = event.getPlayer();
            scheduler.syncLater(UPDATE_NOTICE_DELAY, () -> {
                if (player.isOnline()) {
                    UpdateHub.deliver(rslib, player);
                }
            });
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onQuit(PlayerQuitEvent event) {
            for (Rs rs : RsLib.contexts()) {
                rs.handleQuit(event.getPlayer());
            }
        }

        /** PlaceholderAPI appearing or leaving: every plugin asks again whether it can use it. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            refreshPapi(event.getPlugin().getName());
        }

        private static void refreshPapi(String name) {
            if (name.equals("PlaceholderAPI")) {
                for (Rs rs : RsLib.contexts()) {
                    rs.papi().invalidate();
                }
            }
        }

        /**
         * Safety net: a plugin disabled without going through {@code RsPlugin.onDisable}
         * (a broken plugin manager) still gets its context taken down, one tick later.
         */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginDisable(PluginDisableEvent event) {
            refreshPapi(event.getPlugin().getName());
            if (event.getPlugin() == rslib) {
                return;
            }
            Rs rs = RsLib.context(event.getPlugin());
            if (rs == null) {
                return;
            }
            scheduler.sync(() -> {
                if (RsLib.context(event.getPlugin()) == rs) {
                    rslib.getLogger().warning(event.getPlugin().getName()
                            + " was disabled without shutting down its RsLib context; cleaning up.");
                    rs.shutdown();
                }
            });
        }
    }
}
