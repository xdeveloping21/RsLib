package com.rs.lib.chat;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import com.rs.lib.Rs;

/**
 * Who reads a chat message, and messages that never reach the chat ({@code rs().chat()}).
 *
 * <pre>
 * // a staff channel: the message never reaches the public chat or its formatters
 * rs().chat().intercept((player, text) -&gt; {
 *     if (!staffChat.isOn(player.getUniqueId())) return false;
 *     staffChat.send(player, text);
 *     return true;
 * });
 *
 * // an arena's chat stays in the arena
 * rs().chat().rule((reader, speaker) -&gt; arenas.same(reader, speaker));
 * </pre>
 *
 * <ul>
 *   <li>Both listen to the one chat event of the server ({@link ChatEvents}): Paper's
 *   {@code AsyncChatEvent}, or the legacy event only on a server without it. On Paper, a plugin
 *   that still listens to the legacy {@code AsyncPlayerChatEvent} is run before it, so it may
 *   read a message before an interceptor takes it.</li>
 *   <li><b>Interceptors</b> run at LOWEST, before other plugins, in the order they were
 *   registered (RsLib's chat questions go first). The first one that takes a message cancels it
 *   and the rest are not asked; a message already cancelled is not offered.</li>
 *   <li><b>Rules</b> run at HIGHEST on messages that were not cancelled and remove readers
 *   without cancelling or re-sending the message, so its format stays. They combine with AND:
 *   one refusal from any plugin removes the reader. The speaker always reads their own message
 *   and the console keeps every message. A player with {@value #BYPASS_PERMISSION} is above
 *   every rule: they read everything and everyone reads them.</li>
 *   <li>Both run on the chat thread: read shared state only and hop to the main thread before
 *   touching the world. A handler that throws is ignored for that message and reported once.</li>
 * </ul>
 */
public final class ChatManager {

    /** Players with it read every message and are read by everyone, whatever the rules say. */
    public static final String BYPASS_PERMISSION = "rslib.chat.bypass";

    private static final List<Entry<ChatInterceptor>> INTERCEPTORS = new CopyOnWriteArrayList<>();
    private static final List<Entry<AudienceRule>> RULES = new CopyOnWriteArrayList<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Listener SHARED = new Listener() {
    };
    private static volatile Logger libLog = Logger.getLogger("RsLib");

    private final String owner;
    private final Logger logger;
    private volatile boolean closed;

    private static final class Entry<T> {
        final ChatManager owner;
        final T handler;
        final ChatHook hook;

        Entry(ChatManager owner, T handler, List<Entry<T>> list) {
            this.owner = owner;
            this.handler = handler;
            this.hook = new ChatHook(() -> list.remove(this));
        }
    }

    /** Created by the context. */
    public ChatManager(Rs rs) {
        this(rs.plugin().getName(), rs.plugin().getLogger());
    }

    ChatManager(String owner, Logger logger) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Offers every chat message to {@code interceptor} before the rest of the server sees it.
     *
     * @throws IllegalStateException when the plugin is shutting down
     */
    public ChatHook intercept(ChatInterceptor interceptor) {
        return add(INTERCEPTORS, Objects.requireNonNull(interceptor, "interceptor"));
    }

    /**
     * Adds a rule on who reads whom; a plugin may add several.
     *
     * @throws IllegalStateException when the plugin is shutting down
     */
    public ChatHook rule(AudienceRule rule) {
        return add(RULES, Objects.requireNonNull(rule, "rule"));
    }

    /** Removes every interceptor and rule of this plugin; called by the context on shutdown. */
    public void shutdown() {
        closed = true;
        for (Entry<?> entry : INTERCEPTORS) {
            if (entry.owner == this) {
                entry.hook.close();
            }
        }
        for (Entry<?> entry : RULES) {
            if (entry.owner == this) {
                entry.hook.close();
            }
        }
    }

    /** Registers RsLib's chat handlers on the server's chat event; RsLib calls it once. */
    public static void registerShared(Plugin rslib) {
        libLog = rslib.getLogger();
        listen(ChatEvents.registrar(rslib, SHARED));
    }

    static void listen(ChatEvents.Registrar registrar) {
        ChatEvents.listen(registrar, EventPriority.LOWEST, true, ChatManager::takeOut);
        ChatEvents.listen(registrar, EventPriority.HIGHEST, true, ChatManager::trim);
    }

    // ------------------------------------------------------------------ handlers

    /** Offers {@code line} to the interceptors until one takes it. */
    static void takeOut(ChatLine line) {
        if (INTERCEPTORS.isEmpty() || line.cancelled()) {
            return;
        }
        Player speaker = line.speaker();
        String text = line.text();
        for (Entry<ChatInterceptor> entry : INTERCEPTORS) {
            if (!entry.hook.isOpen()) {
                continue;
            }
            try {
                if (entry.handler.take(speaker, text)) {
                    line.cancel();
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
                entry.owner.warnOnce("interceptor", e);
            }
        }
    }

    /** Takes off the audience of {@code line} every reader a rule refuses. */
    static void trim(ChatLine line) {
        if (RULES.isEmpty() || line.cancelled()) {
            return;
        }
        Player speaker = line.speaker();
        if (bypasses(speaker)) {
            return;
        }
        UUID speakerId = speaker.getUniqueId();
        boolean changed = line.removeViewers(reader -> !speakerId.equals(reader.getUniqueId())
                && !bypasses(reader) && !canRead(reader, speaker));
        if (!changed && WARNED.add("unmodifiable")) {
            libLog.warning("A chat plugin hands over the chat audience as unmodifiable; chat rules"
                    + " cannot remove readers while it does.");
        }
    }

    /** The answer every open rule agrees on; a rule that throws has no say. */
    static boolean canRead(Player reader, Player speaker) {
        for (Entry<AudienceRule> entry : RULES) {
            if (!entry.hook.isOpen()) {
                continue;
            }
            try {
                if (!entry.handler.canRead(reader, speaker)) {
                    return false;
                }
            } catch (RuntimeException | LinkageError e) {
                entry.owner.warnOnce("rule", e);
            }
        }
        return true;
    }

    static boolean bypasses(Player player) {
        return player.hasPermission(BYPASS_PERMISSION);
    }

    // ------------------------------------------------------------------ internals

    private <T> ChatHook add(List<Entry<T>> list, T handler) {
        if (closed) {
            throw new IllegalStateException(owner + " is shutting down; no chat handler can be added");
        }
        Entry<T> entry = new Entry<>(this, handler, list);
        list.add(entry);
        return entry.hook;
    }

    private void warnOnce(String what, Throwable error) {
        if (WARNED.add(owner + "\n" + what)) {
            logger.log(Level.WARNING, "A chat " + what + " of " + owner
                    + " failed; it is skipped for that message (reported once)", error);
        }
    }

    /** Forgets every handler and warning; for tests. */
    static void reset() {
        INTERCEPTORS.clear();
        RULES.clear();
        WARNED.clear();
    }
}
