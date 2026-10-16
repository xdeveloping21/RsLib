package com.rs.lib.chat;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

/**
 * Where RsLib reads chat, so that a message taken out of chat (an answer to a chat question, a
 * message an interceptor took) is handled exactly once and is cancelled before any other plugin's
 * chat listener runs, whichever chat event that plugin listens to.
 *
 * <p>On Paper, a message goes through the legacy {@code AsyncPlayerChatEvent} (and the sync
 * {@code PlayerChatEvent}) first only while some plugin listens to them; their cancel and
 * recipients then carry into {@code AsyncChatEvent}, which delivers the message. Listening to the
 * legacy event is what turns that path on, so RsLib does it only when it is on anyway:</p>
 * <ul>
 *   <li>With no other plugin on the legacy events, the takers run at LOWEST on
 *   {@code AsyncChatEvent}, before every other listener of the only chat event fired.</li>
 *   <li>While another plugin listens to a legacy event, the takers run at LOWEST on the legacy
 *   event, before that plugin; a message they take arrives cancelled everywhere after. The
 *   {@code AsyncChatEvent} handler then skips the message, so it is never handled twice. The
 *   choice is made again whenever a plugin is enabled or disabled and when the server finishes
 *   loading.</li>
 *   <li>Rules that remove readers run at HIGHEST on {@code AsyncChatEvent}, whose audience is the
 *   one delivered on both paths.</li>
 * </ul>
 * <p>On a server without {@code AsyncChatEvent}, everything runs on the legacy event. A listener
 * registered to see cancelled messages ({@code ignoreCancelled = false}) still sees a taken
 * message as cancelled; no plugin can prevent that.</p>
 *
 * <p>Used by RsLib's chat questions ({@code rs().inputs()}) and chat module
 * ({@code rs().chat()}); plugins use those instead.</p>
 */
public final class ChatEvents {

    static final String PAPER_EVENT = "io.papermc.paper.event.player.AsyncChatEvent";
    private static final boolean PAPER = present(PAPER_EVENT);
    private static final ChatEvents SERVER = new ChatEvents(PAPER);

    /** Registers one executor for one event class; lets the routing be checked without a server. */
    @FunctionalInterface
    public interface Registrar {
        void register(Class<? extends Event> type, EventPriority priority, boolean ignoreCancelled,
                      EventExecutor executor);
    }

    /** Adds and removes RsLib's LOWEST handler of the legacy chat event. */
    public interface LegacySwitch {
        void on(EventExecutor executor);

        void off();
    }

    private record Taker(Consumer<ChatLine> handler, boolean seesCancelled) {
    }

    private final boolean paper;
    private final List<Taker> takers = new CopyOnWriteArrayList<>();
    private final List<Consumer<ChatLine>> rules = new CopyOnWriteArrayList<>();
    /** The speaker whose message the legacy handler of this thread handled; Paper fires both events on one thread. */
    private final ThreadLocal<UUID> handledOnLegacy = new ThreadLocal<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile Logger log = Logger.getLogger("RsLib");
    /** Guarded by this. */
    private LegacySwitch legacy;
    private volatile boolean legacyOn;

    /** A router for a server with Paper's chat event ({@code paper}) or only the legacy one. */
    public ChatEvents(boolean paper) {
        this.paper = paper;
    }

    /** The router of this server, which RsLib installs. */
    public static ChatEvents server() {
        return SERVER;
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

    /**
     * Adds a handler that may take messages out of chat; handlers run in the order added, before
     * any other plugin. A handler that does not see cancelled messages is skipped once an earlier
     * one (or another plugin) cancelled the message.
     */
    public void take(Consumer<ChatLine> handler, boolean seesCancelled) {
        takers.add(new Taker(Objects.requireNonNull(handler, "handler"), seesCancelled));
    }

    /** Adds a handler of the delivered message at HIGHEST, for messages not cancelled (removing readers). */
    public void rule(Consumer<ChatLine> handler) {
        rules.add(Objects.requireNonNull(handler, "handler"));
    }

    /** Whether the takers currently run on the legacy event. */
    public boolean legacyOn() {
        return legacyOn;
    }

    /**
     * Registers the handlers through {@code registrar}; on Paper, {@code legacy} adds the legacy
     * handler while {@link #refresh} says another plugin listens to a legacy chat event.
     */
    public void start(Registrar registrar, LegacySwitch legacy) {
        Objects.requireNonNull(registrar, "registrar");
        if (paper) {
            synchronized (this) {
                this.legacy = Objects.requireNonNull(legacy, "legacy");
            }
            PaperLine.listen(registrar, EventPriority.LOWEST, false, this::fromModern);
            PaperLine.listen(registrar, EventPriority.HIGHEST, true, this::deliver);
        } else {
            LegacyLine.listen(registrar, EventPriority.LOWEST, false, this::takeAll);
            LegacyLine.listen(registrar, EventPriority.HIGHEST, true, this::deliver);
        }
    }

    /** Takes messages on the legacy event exactly while another plugin listens to a legacy chat event. */
    public void refresh(boolean othersOnLegacy) {
        synchronized (this) {
            if (!paper || legacy == null || othersOnLegacy == legacyOn) {
                return;
            }
            if (othersOnLegacy) {
                legacy.on(LegacyLine.executor(this::fromLegacy));
            } else {
                legacy.off();
            }
            legacyOn = othersOnLegacy;
        }
    }

    /** Registers this router on {@code rslib}'s server and keeps the choice up to date; RsLib calls it once. */
    public void install(Plugin rslib) {
        log = rslib.getLogger();
        Listener legacyOwner = new Listener() {
        };
        start(registrar(rslib, new Listener() {
        }), new LegacySwitch() {
            @Override
            public void on(EventExecutor executor) {
                LegacyLine.register(rslib, legacyOwner, executor);
            }

            @Override
            public void off() {
                LegacyLine.unregister(legacyOwner);
            }
        });
        if (paper) {
            rslib.getServer().getPluginManager().registerEvents(new Watcher(rslib), rslib);
            refresh(LegacyLine.othersListen(rslib, null));
        }
    }

    // ------------------------------------------------------------------ handlers

    /** LOWEST on Paper's event: skipped when the legacy handler of this thread already took its turn. */
    void fromModern(ChatLine line) {
        UUID handled = handledOnLegacy.get();
        if (handled != null) {
            handledOnLegacy.remove();
            if (handled.equals(line.speaker().getUniqueId())) {
                return;
            }
        }
        takeAll(line);
    }

    /** LOWEST on the legacy event, while it is the first event of the message. */
    void fromLegacy(ChatLine line) {
        handledOnLegacy.set(line.speaker().getUniqueId());
        takeAll(line);
    }

    void takeAll(ChatLine line) {
        for (Taker taker : takers) {
            if (line.cancelled() && !taker.seesCancelled()) {
                continue;
            }
            try {
                taker.handler().accept(line);
            } catch (RuntimeException | LinkageError e) {
                warnOnce("A chat handler failed; it is skipped for that message (reported once)", e);
            }
        }
    }

    void deliver(ChatLine line) {
        for (Consumer<ChatLine> rule : rules) {
            try {
                rule.accept(line);
            } catch (RuntimeException | LinkageError e) {
                warnOnce("A chat audience handler failed; it is skipped for that message (reported once)", e);
            }
        }
    }

    private void warnOnce(String message, Throwable error) {
        if (warned.add(message)) {
            log.log(Level.WARNING, message, error);
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

    /** Chooses again when the set of plugins changes. */
    private final class Watcher implements Listener {

        private final Plugin rslib;

        Watcher(Plugin rslib) {
            this.rslib = rslib;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnable(PluginEnableEvent event) {
            refresh(LegacyLine.othersListen(rslib, null));
        }

        /** The leaving plugin's listeners are still registered at this point; they do not count. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onDisable(PluginDisableEvent event) {
            if (event.getPlugin() != rslib) {
                refresh(LegacyLine.othersListen(rslib, event.getPlugin()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onLoaded(ServerLoadEvent event) {
            refresh(LegacyLine.othersListen(rslib, null));
        }
    }
}
