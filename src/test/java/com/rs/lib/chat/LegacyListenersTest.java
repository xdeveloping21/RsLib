package com.rs.lib.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Which plugins count as listening to the legacy chat events, on the real handler lists. */
@SuppressWarnings("deprecation")
class LegacyListenersTest {

    private final Plugin rslib = plugin("RsLib");
    private final Plugin other = plugin("DiscordSRV");

    @AfterEach
    void clean() {
        HandlerList.unregisterAll(rslib);
        HandlerList.unregisterAll(other);
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(LegacyListenersTest.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> true;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void listen(HandlerList handlers, Plugin plugin) {
        handlers.register(new RegisteredListener(new Listener() {
        }, (listener, event) -> {
        }, EventPriority.NORMAL, plugin, false));
    }

    @Test
    void onlyOtherPluginsThatStayCount() {
        assertFalse(LegacyLine.othersListen(rslib, null));
        listen(AsyncPlayerChatEvent.getHandlerList(), rslib);
        assertFalse(LegacyLine.othersListen(rslib, null), "RsLib's own legacy handler does not count");
        listen(AsyncPlayerChatEvent.getHandlerList(), other);
        assertTrue(LegacyLine.othersListen(rslib, null));
        assertFalse(LegacyLine.othersListen(rslib, other), "a plugin being disabled does not count");
        HandlerList.unregisterAll(other);
        listen(PlayerChatEvent.getHandlerList(), other);
        assertTrue(LegacyLine.othersListen(rslib, null), "the sync legacy event also makes Paper fire the legacy path");
    }
}
