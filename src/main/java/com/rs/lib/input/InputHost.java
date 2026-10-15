package com.rs.lib.input;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.lang.RsLang;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;

/** What {@link Inputs} needs from the server and the plugin; lets its logic run in tests. */
interface InputHost {

    Logger logger();

    /** True when the calling thread owns {@code player} (the main thread on Paper). */
    boolean ownsHere(Player player);

    /** Runs {@code task} on the thread that owns {@code player}; throws when it cannot be scheduled. */
    void runFor(Player player, Runnable task);

    /** Runs {@code task} for {@code player} after {@code ticks}; throws when it cannot be scheduled. */
    TaskHandle later(Player player, long ticks, Runnable task);

    boolean shuttingDown();

    /** Sends the message {@code key}: the plugin's language files, or RsLib's English text without them. */
    void send(Player player, String key, Ph... phs);

    /** Sends {@code text} as written in a config (full text pipeline). */
    void sendText(Player player, String text);

    static InputHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements InputHost {

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public boolean ownsHere(Player player) {
            return rs.scheduler().ownsHere(player);
        }

        @Override
        public void runFor(Player player, Runnable task) {
            rs.scheduler().entity(player, task);
        }

        @Override
        public TaskHandle later(Player player, long ticks, Runnable task) {
            return rs.scheduler().entityLater(player, ticks, task);
        }

        @Override
        public boolean shuttingDown() {
            return rs.isShuttingDown();
        }

        @Override
        public void send(Player player, String key, Ph... phs) {
            RsLang lang = rs.langOrNull();
            if (lang != null) {
                lang.send(player, key, phs);
                return;
            }
            player.sendMessage(RsText.colorUncached(RsText.applyLocals(Defaults.get(key), phs)));
        }

        @Override
        public void sendText(Player player, String text) {
            player.sendMessage(RsText.color(text));
        }
    }

    /** RsLib's English messages, for plugins without a language module. */
    final class Defaults {

        private static final YamlConfiguration MESSAGES = load();

        private Defaults() {
        }

        static String get(String key) {
            String value = MESSAGES.getString(key);
            return value == null ? key : value;
        }

        private static YamlConfiguration load() {
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStream in = Inputs.class.getResourceAsStream("/rslib-messages_en.yml")) {
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
