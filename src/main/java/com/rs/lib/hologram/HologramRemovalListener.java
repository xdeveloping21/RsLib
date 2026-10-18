package com.rs.lib.hologram;

import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;

/**
 * Puts a hologram back on the next tick when a command or another plugin removes its entity
 * (for example {@code /kill @e[type=text_display]}). A chunk unload is left to
 * {@link HologramChunkListener}, which spawns it again when the chunk loads. Shared by every
 * plugin and registered once by RsLib, only on servers that have {@link EntityRemoveEvent}.
 *
 * <p>Every entity removal of the server passes here; anything that is not a text display costs
 * one type check.</p>
 */
final class HologramRemovalListener implements Listener {

    static final String EVENT_CLASS = "org.bukkit.event.entity.EntityRemoveEvent";

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof TextDisplay display && event.getCause() != EntityRemoveEvent.Cause.UNLOAD) {
            HologramUtil.removedByOther(display);
        }
    }
}
