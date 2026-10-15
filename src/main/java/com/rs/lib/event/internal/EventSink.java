package com.rs.lib.event.internal;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** How the shared listeners fire RsLib's events; tests put a recorder in its place. */
interface EventSink {

    /** Through Bukkit's plugin manager. */
    EventSink SERVER = new EventSink() {

        @Override
        public boolean listened(HandlerList handlers) {
            return handlers.getRegisteredListeners().length > 0;
        }

        @Override
        public void call(Event event) {
            Bukkit.getPluginManager().callEvent(event);
        }
    };

    /**
     * True when any plugin listens through {@code handlers}. Read before building an event, so
     * nothing is allocated for an event nobody listens to.
     */
    boolean listened(HandlerList handlers);

    void call(Event event);
}
