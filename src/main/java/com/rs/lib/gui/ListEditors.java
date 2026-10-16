package com.rs.lib.gui;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.action.ActionContext;
import com.rs.lib.lang.RsLang;
import com.rs.lib.text.RsText;

/** The open {@link ListEditor}s of one plugin, one per player, and the {@code [list-editor]} action. */
final class ListEditors {

    /** Sends one of RsLib's messages to a player. */
    @FunctionalInterface
    interface Messenger {
        void send(Player player, String key, Ph... phs);
    }

    private final GuiManager manager;
    private final Map<UUID, ListEditor<?>> live = new ConcurrentHashMap<>();
    private final Messenger messenger;

    ListEditors(GuiManager manager, Messenger messenger) {
        this.manager = manager;
        this.messenger = messenger;
    }

    /** The plugin's language files, or RsLib's English texts without them (or without {@code rs}). */
    static Messenger of(Rs rs) {
        return (player, key, phs) -> {
            RsLang lang = rs == null ? null : rs.langOrNull();
            if (lang != null) {
                lang.send(player, key, phs);
                return;
            }
            player.sendMessage(RsText.colorUncached(RsText.applyLocals(Defaults.get(key), phs)));
        };
    }

    void send(Player player, String key, Ph... phs) {
        try {
            messenger.send(player, key, phs);
        } catch (RuntimeException e) {
            manager.host().logger().log(Level.WARNING, "Could not send " + key + " to " + player.getName(), e);
        }
    }

    /** Makes {@code editor} the player's editor; returns the one it replaces, or null. */
    ListEditor<?> register(ListEditor<?> editor) {
        ListEditor<?> previous = live.put(editor.viewer().getUniqueId(), editor);
        return previous == editor ? null : previous;
    }

    void forget(ListEditor<?> editor) {
        Player viewer = editor.viewer();
        if (viewer != null) {
            live.remove(viewer.getUniqueId(), editor);
        }
    }

    /** The player left: their editor ends as {@link ListEditor.End#LEFT}. */
    void quit(UUID player) {
        ListEditor<?> editor = live.get(player);
        if (editor != null) {
            editor.end(ListEditor.End.LEFT);
        }
    }

    /** Every editor ends as {@link ListEditor.End#SHUT_DOWN}. */
    void shutdown() {
        for (ListEditor<?> editor : new ArrayList<>(live.values())) {
            editor.end(ListEditor.End.SHUT_DOWN);
        }
    }

    /** {@code [list-editor] <command> [index]}, clicked in a menu. */
    void handle(Player player, String arg, ActionContext context) {
        if (player == null) {
            return;
        }
        ListEditor<?> editor = live.get(player.getUniqueId());
        if (editor == null || !(context.pageTarget() instanceof GuiSession session) || !editor.shows(session)) {
            manager.host().debug(() -> "[" + ListEditor.ACTION + "] " + arg + " ignored: no list editor is open in this menu.");
            return;
        }
        String[] parts = arg == null ? new String[0] : arg.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            editor.command("", -1);
            return;
        }
        int index = -1;
        if (parts.length > 1) {
            try {
                index = Integer.parseInt(parts[1]);
            } catch (NumberFormatException notANumber) {
                index = -1;
            }
        }
        editor.command(parts[0], index);
    }

    /** RsLib's English messages, for plugins without a language module. */
    static final class Defaults {

        private static final YamlConfiguration MESSAGES = load();

        private Defaults() {
        }

        static String get(String key) {
            String value = MESSAGES.getString(key);
            return value == null ? key : value;
        }

        private static YamlConfiguration load() {
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream in = ListEditors.class.getResourceAsStream("/rslib-messages_en.yml")) {
                if (in != null) {
                    yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (Exception unreadable) {
                Logger.getLogger("RsLib").log(Level.WARNING, "[RsLib] Could not read rslib-messages_en.yml", unreadable);
            }
            return yaml;
        }
    }
}
