package com.rs.lib.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.EventExecutor;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

/**
 * A chat router started on a fake server, and messages sent through it the way Paper does: while
 * any plugin listens to the legacy event, the legacy {@code AsyncPlayerChatEvent} first, whose
 * cancel and recipients carry into {@code AsyncChatEvent}; otherwise only {@code AsyncChatEvent}.
 * Another plugin listens at NORMAL with {@code ignoreCancelled = true} on whichever event fires.
 */
public final class PaperChatSim {

    private static final Listener OWNER = new Listener() {
    };

    /** An executor the router registered. */
    public record Registered(Class<? extends Event> type, EventPriority priority, boolean ignoreCancelled,
                             EventExecutor executor) {
    }

    /** What happened to one message. */
    public static final class Outcome {
        /** Another plugin's legacy listener saw it (only when the legacy event fired). */
        public boolean otherLegacySaw;
        /** Another plugin's listener of the delivering event saw it. */
        public boolean otherSaw;
        public boolean cancelled;
        /** The players the message was delivered to (empty when cancelled). */
        public final Set<Player> delivered = new LinkedHashSet<>();
    }

    public final ChatEvents events;
    public final List<Registered> permanent = new ArrayList<>();
    /** RsLib's legacy handler while it is on. */
    public EventExecutor legacy;
    public int legacyOffs;

    public PaperChatSim(boolean paper) {
        events = new ChatEvents(paper);
    }

    /** Registers the router's handlers on the fake server. */
    public void start() {
        events.start((type, priority, ignoreCancelled, executor) ->
                permanent.add(new Registered(type, priority, ignoreCancelled, executor)), new ChatEvents.LegacySwitch() {
                    @Override
                    public void on(EventExecutor executor) {
                        legacy = executor;
                    }

                    @Override
                    public void off() {
                        legacy = null;
                        legacyOffs++;
                    }
                });
    }

    /**
     * {@code speaker} says {@code text} on a Paper server where {@code online} would read it;
     * {@code legacyPath} is whether some plugin listens to the legacy event (so Paper fires it first).
     */
    @SuppressWarnings("deprecation")
    public Outcome paper(Player speaker, String text, Set<Player> online, boolean legacyPath) throws EventException {
        Outcome outcome = new Outcome();
        boolean cancelled = false;
        Set<Player> recipients = new LinkedHashSet<>(online);
        String message = text;
        if (legacyPath) {
            AsyncPlayerChatEvent first = new AsyncPlayerChatEvent(true, speaker, text, recipients);
            if (legacy != null) {
                legacy.execute(OWNER, first);
            }
            outcome.otherLegacySaw = !first.isCancelled();
            cancelled = first.isCancelled();
            recipients = first.getRecipients();
            message = first.getMessage();
        }
        Set<Audience> viewers = new LinkedHashSet<>(recipients);
        viewers.add(Audience.empty());
        AsyncChatEvent event = new AsyncChatEvent(true, speaker, viewers, null, Component.text(message),
                Component.text(message), null);
        event.setCancelled(cancelled);
        run(event, EventPriority.LOWEST);
        outcome.otherSaw = !event.isCancelled();
        run(event, EventPriority.HIGHEST);
        outcome.cancelled = event.isCancelled();
        if (!event.isCancelled()) {
            for (Audience viewer : event.viewers()) {
                if (viewer instanceof Player player) {
                    outcome.delivered.add(player);
                }
            }
        }
        return outcome;
    }

    /** {@code speaker} says {@code text} on a server without Paper's chat event. */
    @SuppressWarnings("deprecation")
    public Outcome legacyOnly(Player speaker, String text, Set<Player> online) throws EventException {
        Outcome outcome = new Outcome();
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, speaker, text, new LinkedHashSet<>(online));
        run(event, EventPriority.LOWEST);
        outcome.otherSaw = !event.isCancelled();
        run(event, EventPriority.HIGHEST);
        outcome.cancelled = event.isCancelled();
        if (!event.isCancelled()) {
            outcome.delivered.addAll(event.getRecipients());
        }
        return outcome;
    }

    private void run(Event event, EventPriority priority) throws EventException {
        for (Registered each : permanent) {
            boolean cancelled = event instanceof org.bukkit.event.Cancellable c && c.isCancelled();
            if (each.priority() == priority && each.type().isInstance(event) && !(each.ignoreCancelled() && cancelled)) {
                each.executor().execute(OWNER, event);
            }
        }
    }
}
