package com.rs.lib.internal;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import com.rs.lib.Ph;
import com.rs.lib.text.RsText;
import com.rs.lib.util.Formats;
import com.rs.lib.util.RsFormats;

import net.kyori.adventure.text.Component;

/**
 * RsLib's own texts in its own language, {@code lang} in plugins/RsLib/config.yml ({@code en}
 * or {@code es}): the answers of {@code /rslib settings}, {@code logs}, {@code db} and
 * {@code alts}, the settings menu, and {@code {key:relative}} in a text no plugin language
 * covers. Read from the {@code rslib-messages_<lang>.yml} RsLib ships, so they are not edited
 * on the server; a key missing in the language is English, and a key missing there is shown as
 * itself. Any thread.
 */
public final class LibMessages {

    /** The languages RsLib has its texts in. */
    public static final List<String> LANGUAGES = List.of("en", "es");
    private static final String RESOURCE = "/rslib-messages_%s.yml";

    private static final Map<String, YamlConfiguration> FILES = new ConcurrentHashMap<>();
    private static volatile String language = "en";
    private static volatile Formats.RelativeTexts relative;

    private LibMessages() {
    }

    /** The language in use. */
    public static String language() {
        return language;
    }

    /**
     * Uses {@code code} ({@code en} or {@code es}, any case) from now on and makes it the
     * language of {@code :relative} outside any plugin. False, with English in use, when RsLib
     * has no texts in that language.
     */
    public static boolean use(String code) {
        String wanted = code == null ? "en" : code.trim().toLowerCase(Locale.ROOT);
        boolean known = LANGUAGES.contains(wanted);
        language = known ? wanted : "en";
        relative = RsFormats.texts(LibMessages::rawOrNull);
        Formats.fallbackPlaceholderTexts(LibMessages::relativeTexts);
        return known;
    }

    /** Back to English, and {@code :relative} outside any plugin to English (RsLib stopped). */
    public static void reset() {
        language = "en";
        relative = null;
        Formats.fallbackPlaceholderTexts(null);
    }

    /** The words of a relative time in RsLib's language. */
    public static Formats.RelativeTexts relativeTexts() {
        Formats.RelativeTexts texts = relative;
        return texts != null ? texts : RsFormats.texts(LibMessages::rawOrNull);
    }

    /** The text of {@code key} in RsLib's language, English when it lacks it, or null. */
    public static String rawOrNull(String key) {
        String value = file(language).getString(key);
        return value != null || language.equals("en") ? value : file("en").getString(key);
    }

    /** {@link #rawOrNull}, or the key itself. */
    public static String text(String key) {
        String value = rawOrNull(key);
        return value == null ? key : value;
    }

    /** The text of {@code key} with {@code phs} put in and colours applied (values from players go in as {@link Ph#literal}). */
    public static Component render(String key, Ph... phs) {
        return RsText.colorUncached(RsText.applyLocals(text(key), phs));
    }

    /** Sends {@link #render}. */
    public static void send(CommandSender to, String key, Ph... phs) {
        to.sendMessage(render(key, phs));
    }

    private static YamlConfiguration file(String code) {
        return FILES.computeIfAbsent(code, LibMessages::load);
    }

    private static YamlConfiguration load(String code) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = LibMessages.class.getResourceAsStream(String.format(RESOURCE, code))) {
            if (in != null) {
                yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception unreadable) {
            Logger.getLogger("RsLib").log(Level.WARNING, "[RsLib] Could not read rslib-messages_" + code + ".yml",
                    unreadable);
        }
        return yaml;
    }
}
