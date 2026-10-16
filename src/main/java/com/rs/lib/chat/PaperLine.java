package com.rs.lib.chat;

import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** A message of Paper's {@link AsyncChatEvent}. */
final class PaperLine implements ChatLine {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final AsyncChatEvent event;

    PaperLine(AsyncChatEvent event) {
        this.event = event;
    }

    static void listen(ChatEvents.Registrar registrar, EventPriority priority, boolean ignoreCancelled,
                       Consumer<ChatLine> handler) {
        registrar.register(AsyncChatEvent.class, priority, ignoreCancelled, (listener, event) -> {
            if (event instanceof AsyncChatEvent chat) {
                handler.accept(new PaperLine(chat));
            }
        });
    }

    @Override
    public Player speaker() {
        return event.getPlayer();
    }

    @Override
    public String text() {
        return PLAIN.serialize(event.message());
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
            event.viewers().removeIf(viewer -> viewer instanceof Player player && drop.test(player));
            return true;
        } catch (UnsupportedOperationException unmodifiable) {
            return false;
        }
    }
}
