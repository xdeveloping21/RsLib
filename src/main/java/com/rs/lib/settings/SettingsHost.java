package com.rs.lib.settings;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.db.RsDb;
import com.rs.lib.lang.RsLang;
import com.rs.lib.text.RsText;

/** What {@link SettingsManager} needs from the server and the plugin; lets its logic run in tests. */
interface SettingsHost {

    /** The plugin name: the {@code owner} column of its rows. */
    String owner();

    Logger logger();

    /** The plugin's database; throws {@link UnsupportedOperationException} when it declared none. */
    RsDb db();

    boolean shuttingDown();

    /** The players online now. */
    Collection<? extends Player> online();

    /** The server console. */
    CommandSender console();

    /** Sends the message {@code key}: the plugin's language files, or RsLib's English text without them. */
    void send(CommandSender target, String key, Ph... phs);

    /** The raw text of {@code key}, like {@link #send}; the key itself when nobody has it. */
    String text(String key);

    static SettingsHost of(Rs rs) {
        return new Server(rs);
    }

    /** The real server. */
    record Server(Rs rs) implements SettingsHost {

        @Override
        public String owner() {
            return rs.plugin().getName();
        }

        @Override
        public Logger logger() {
            return rs.plugin().getLogger();
        }

        @Override
        public RsDb db() {
            return rs.db();
        }

        @Override
        public boolean shuttingDown() {
            return rs.isShuttingDown();
        }

        @Override
        public Collection<? extends Player> online() {
            return Bukkit.getOnlinePlayers();
        }

        @Override
        public CommandSender console() {
            return Bukkit.getConsoleSender();
        }

        @Override
        public void send(CommandSender target, String key, Ph... phs) {
            RsLang lang = rs.langOrNull();
            if (lang != null) {
                lang.send(target, key, phs);
                return;
            }
            target.sendMessage(RsText.colorUncached(RsText.applyLocals(Defaults.get(key), phs)));
        }

        @Override
        public String text(String key) {
            RsLang lang = rs.langOrNull();
            String raw = lang == null ? null : lang.rawOrNull(key);
            return raw != null ? raw : Defaults.get(key);
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
            try (InputStream in = SettingsManager.class.getResourceAsStream("/rslib-messages_en.yml")) {
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
