package com.rs.lib.hook;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

/** Keeps every {@link SoftDependency} in step with its target plugin. Registered once by RsLib. */
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
}
