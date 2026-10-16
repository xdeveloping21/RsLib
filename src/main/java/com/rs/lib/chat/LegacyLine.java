package com.rs.lib.chat;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/** A message of the legacy {@link AsyncPlayerChatEvent}, for servers without Paper's chat event. */
@SuppressWarnings({"deprecation", "removal"})
final class LegacyLine implements ChatLine {

    private final AsyncPlayerChatEvent event;

    LegacyLine(AsyncPlayerChatEvent event) {
        this.event = event;
    }

    static void listen(ChatEvents.Registrar registrar, EventPriority priority, boolean ignoreCancelled,
                       Consumer<ChatLine> handler) {
        registrar.register(AsyncPlayerChatEvent.class, priority, ignoreCancelled, (listener, event) -> {
            if (event instanceof AsyncPlayerChatEvent chat) {
                handler.accept(new LegacyLine(chat));
            }
        });
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
