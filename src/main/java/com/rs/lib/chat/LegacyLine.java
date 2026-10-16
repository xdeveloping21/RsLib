package com.rs.lib.chat;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/** A message of the legacy {@link AsyncPlayerChatEvent}, for servers without Paper's chat event. */
@SuppressWarnings({"deprecation", "removal"})
final class LegacyLine implements ChatLine {

    private final AsyncPlayerChatEvent event;

    LegacyLine(AsyncPlayerChatEvent event) {
        this.event = event;
    }

    static void listen(ChatEvents.Registrar registrar, EventPriority priority, boolean ignoreCancelled,
                       Consumer<ChatLine> handler) {
        registrar.register(AsyncPlayerChatEvent.class, priority, ignoreCancelled, executor(handler));
    }

    static EventExecutor executor(Consumer<ChatLine> handler) {
        return (listener, event) -> {
            if (event instanceof AsyncPlayerChatEvent chat) {
                handler.accept(new LegacyLine(chat));
            }
        };
    }

    /** Adds {@code executor} at LOWEST on the legacy event, seeing cancelled messages too. */
    static void register(Plugin rslib, Listener owner, EventExecutor executor) {
        rslib.getServer().getPluginManager().registerEvent(AsyncPlayerChatEvent.class, owner, EventPriority.LOWEST,
                executor, rslib, false);
    }

    static void unregister(Listener owner) {
        AsyncPlayerChatEvent.getHandlerList().unregister(owner);
    }

    /**
     * Whether a plugin other than RsLib (and {@code leaving}) listens to the legacy chat events,
     * which makes Paper fire them before its own chat event.
     */
    static boolean othersListen(Plugin rslib, Plugin leaving) {
        return othersIn(AsyncPlayerChatEvent.getHandlerList(), rslib, leaving)
                || othersIn(PlayerChatEvent.getHandlerList(), rslib, leaving);
    }

    private static boolean othersIn(HandlerList handlers, Plugin rslib, Plugin leaving) {
        for (RegisteredListener listener : handlers.getRegisteredListeners()) {
            Plugin owner = listener.getPlugin();
            if (owner != rslib && owner != leaving) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Player speaker() {
        return event.getPlayer();
    }

    @Override
    public String text() {
        return event.getMessage();
    }

    @Override
    public boolean cancelled() {
        return event.isCancelled();
    }

    @Override
    public void cancel() {
        event.setCancelled(true);
    }

    @Override
    public boolean removeViewers(Predicate<Player> drop) {
        try {
            event.getRecipients().removeIf(drop);
            return true;
        } catch (UnsupportedOperationException unmodifiable) {
            return false;
        }
    }
}
