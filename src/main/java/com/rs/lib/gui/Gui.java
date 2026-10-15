package com.rs.lib.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import com.rs.lib.Ph;

/**
 * A loaded menu ({@code rs().guis().get(id)}) and the sessions open on it, one per player.
 * The same object stays valid across reloads: it then shows the menu as the file says now.
 */
public final class Gui {

    private static final Ph[] NO_PHS = new Ph[0];

    private final GuiManager manager;
    private final String id;
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    private volatile GuiDef def;

    Gui(GuiManager manager, GuiDef def) {
        this.manager = manager;
        this.id = def.id();
        this.def = def;
    }

    public String id() {
        return id;
    }

    /** The menu as currently loaded. */
    public GuiDef def() {
        return def;
    }

    /** Opens page 1 for {@code player}. */
    public void open(Player player) {
        open(player, 1, NO_PHS);
    }

    /** Opens {@code page} (1 without {@code pagination: true}) for {@code player}. */
    public void open(Player player, int page) {
        open(player, page, NO_PHS);
    }

    /** Opens page 1 with {@code phs} in the title. */
    public void open(Player player, Ph... phs) {
        open(player, 1, phs);
    }

    /**
     * Opens the menu for {@code player} at {@code page}, with {@code phs} as the title's local
     * placeholders. When the player already has a live session of this menu it is reused: its
     * binds and timers stay, the title placeholders are replaced when {@code phs} is not empty,
     * the content is rendered again at {@code page}, and the window and open sound only come
     * back if the player was not looking at it. Does nothing for a player who is offline or
     * while the plugin shuts down. Runs on the player's thread (moved there when called
     * elsewhere).
     */
    public void open(Player player, int page, Ph... phs) {
        if (player == null || !player.isOnline() || manager.refusesOpening()) {
            return;
        }
        GuiHost host = manager.host();
        if (!host.ownsHere(player)) {
            try {
                host.runFor(player, () -> open(player, page, phs));
            } catch (RuntimeException disabled) {
                host.debug(() -> "Menu " + id + " not opened for " + player.getName() + ": the plugin is being disabled.");
            }
            return;
        }
        GuiSession live = sessions.get(player.getUniqueId());
        if (live != null && !live.closed()) {
            live.reopen(page, phs);
            return;
        }
        GuiSession session = new GuiSession(this, def, host, player, page, phs);
        sessions.put(player.getUniqueId(), session);
        manager.track(session);
        session.start();
    }

    /** The template {@code templateId} of the menu, or null. */
    public GuiTemplate template(String templateId) {
        return def.template(templateId);
    }

    /** The live session of {@code player} on this menu, or null. */
    public GuiSession session(Player player) {
        if (player == null) {
            return null;
        }
        GuiSession session = sessions.get(player.getUniqueId());
        return session == null || session.closed() ? null : session;
    }

    /** Every live session of this menu. */
    public List<GuiSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    @Override
    public String toString() {
        return "Gui[" + id + "]";
    }

    // ------------------------------------------------------------------ package

    void def(GuiDef next) {
        this.def = next;
    }

    void forget(GuiSession session) {
        sessions.remove(session.viewerId(), session);
        manager.untrack(session);
    }

    /** A problem with the plugin's use of this menu, once per load: {@code [file] message}. */
    void warn(String message) {
        manager.warnings().warn(def.file(), "", message);
    }
}
