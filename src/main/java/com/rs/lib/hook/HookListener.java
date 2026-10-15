package com.rs.lib.hook;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/**
 * Keeps every {@link SoftDependency} in step with its target plugin, and forgets the hooks of
 * an owner that is disabled (also owners that are not {@code RsPlugin}s or never finished
 * enabling). Registered once by RsLib.
 */
public final class HookListener implements Listener {

    @EventHandler
    public void onEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        SoftDependency.forEachRegistered(dep -> {
            if (dep.targets(name)) {
                dep.targetEnabled();
            }
        });
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        String name = event.getPlugin().getName();
        SoftDependency.forEachRegistered(dep -> {
            if (dep.targets(name)) {
                dep.targetDisabled();
            }
        });
    }

    /**
     * Last of all, so no other listener of this event asks a hook of the owner again before the
     * owner reports itself disabled (that would turn the hook back on).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onOwnerDisable(PluginDisableEvent event) {
        SoftDependency.disableAllOf(event.getPlugin());
    }
}
