package com.rs.lib.chat;

import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * The single chat event RsLib listens to on this server: Paper's {@code AsyncChatEvent} when the
 * server has it, the legacy {@code AsyncPlayerChatEvent} only when it does not. Never both, so
 * a message is handled once, and RsLib alone never turns on Paper's legacy chat path (a listener
 * of the legacy event is what makes Paper route every message through it).
 *
 * <p>Used by RsLib's chat questions ({@code rs().inputs()}) and chat module
 * ({@code rs().chat()}); plugins use those instead.</p>
 */
public final class ChatEvents {

    static final String PAPER_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";
    private static final boolean PAPER = present(PAPER_EVENT);

    /** Registers one executor for one event class; lets the choice be checked without a server. */
    @FunctionalInterface
    public interface Registrar {
        void register(Class<? extends Event> type, EventPriority priority, boolean ignoreCancelled,
                      EventExecutor executor);
    }

    private ChatEvents() {
    }

    /** True when the server fires Paper's {@code AsyncChatEvent} (every supported Paper does). */
    public static boolean paperChat() {
        return PAPER;
    }

    /** Registers through {@code plugin}'s plugin manager, with {@code owner} as the listener. */
    public static Registrar registrar(Plugin plugin, Listener owner) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(owner, "owner");
        return (type, priority, ignoreCancelled, executor) -> plugin.getServer().getPluginManager()
                .registerEvent(type, owner, priority, executor, plugin, ignoreCancelled);
    }

    /** Calls {@code handler} for every message of the server's chat event, at {@code priority}. */
    public static void listen(Registrar registrar, EventPriority priority, boolean ignoreCancelled,
                              Consumer<ChatLine> handler) {
        listen(registrar, PAPER, priority, ignoreCancelled, handler);
    }

    static void listen(Registrar registrar, boolean paper, EventPriority priority, boolean ignoreCancelled,
                       Consumer<ChatLine> handler) {
        Objects.requireNonNull(registrar, "registrar");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(handler, "handler");
        // Each adapter is its own class, so the event class of the other path is never loaded.
        if (paper) {
            PaperLine.listen(registrar, priority, ignoreCancelled, handler);
        } else {
            LegacyLine.listen(registrar, priority, ignoreCancelled, handler);
        }
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, ChatEvents.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
