package com.rs.lib.lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.lang.RsLang.Catalog;
import com.rs.lib.lang.RsLang.Entry;
import com.rs.lib.lang.RsLang.Line;
import com.rs.lib.yml.YamlPreprocessor;
import com.rs.lib.yml.YamlUpdater;
import com.rs.lib.yml.internal.YamlText;

/**
 * The file side of {@link RsLang}: seeds and merges {@code lang/messages_<code>.yml} and the
 * extra source folders, then reads them into a {@link RsLang.Catalog}. Main thread only (the
 * caller serializes loads).
 */
final class LangLoader {

    private static final String SHARED_RESOURCE = "/rslib-messages_%s.yml";
    private static final String LEGACY_BLOCK = "snlib:";
    private static final String SHARED_BLOCK = "rslib:";

    private final JavaPlugin plugin;
    private final Logger log;
    private final BiConsumer<String, Supplier<String>> warnOnce;

    /** The maps one load fills: the language in use, its prefix and both layers of entries. */
    private record Layers(String language, String prefix, Map<String, Entry> active, Map<String, Entry> fallback) {
    }

    LangLoader(JavaPlugin plugin, BiConsumer<String, Supplier<String>> warnOnce) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.warnOnce = warnOnce;
    }

    /**
     * Seeds, merges and reads every file. {@code wanted} is the {@code lang} code as written in
     * the config (trimmed, lower case); {@code sources} maps extra folders to their namespaces.
     */
    Catalog load(String wanted, Map<String, String> sources) {
        return load(wanted, sources, false);
    }

    /**
     * Same as {@link #load(String, Map)}. With {@code keepInvalid} (a reload while messages are
     * in use), a file that is not valid YAML is left as it is and null is returned, so the caller
     * keeps the messages it already has: one typo never swaps the owner's texts for the jar copy.
     */
    Catalog load(String wanted, Map<String, String> sources, boolean keepInvalid) {
        if (keepInvalid && anyInvalid(wanted, sources)) {
            return null;
        }
        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            log.warning("Could not create " + folder);
        }
        File english = langFile("en");
        prepareEnglish(english);
        YamlConfiguration englishYaml = parse(english, log);

        if (wanted.isEmpty() || !wanted.matches("[a-z0-9_-]+")) {
            if (!wanted.isEmpty()) {
                log.warning("lang: '" + wanted + "' is not a valid language code; using English.");
            }
            wanted = "en";
        }
        YamlConfiguration activeYaml = englishYaml;
        String language = "en";
        if (!wanted.equals("en")) {
            YamlConfiguration translated = prepareTranslation(wanted, english);
            if (translated != null) {
                activeYaml = translated;
                language = wanted;
            }
        }

        String prefix = activeYaml.getString("prefix", englishYaml.getString("prefix", ""));
        Layers layers = new Layers(language, prefix, new HashMap<>(), new HashMap<>());
        flatten(activeYaml, prefix, layers.active);
        if (activeYaml != englishYaml) {
            flatten(englishYaml, prefix, layers.fallback);
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
            overlay(source.getKey(), source.getValue(), layers);
        }
        warnPrefixToken(layers.active);
        return new Catalog(language, prefix == null ? "" : prefix, Map.copyOf(layers.active),
                Map.copyOf(layers.fallback));
    }

    /** Adds the entries of {@code reference} that the English file lacks; true when it changed. */
    boolean mergeIntoEnglish(List<String> reference) {
        return mergeLines(log, reference, langFile("en"));
    }

    private void prepareEnglish(File english) {
        renameLegacyBlock(english, log);
        if (plugin.getResource("lang/messages_en.yml") != null) {
            YamlUpdater.update(plugin, "lang/messages_en.yml", english, false);
        } else if (!english.exists()) {
            try {
                YamlText.write(english.toPath(), "# Messages of " + plugin.getName() + ".\n"
                        + "# RsLib adds its shared messages below.\n");
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not create " + english, e);
            }
        }
        List<String> shared = sharedLines("en", log);
        if (shared != null) {
            mergeLines(log, shared, english);
        }
    }

    /** The translation for {@code code}, seeded and merged; null when there is none to use. */
    private YamlConfiguration prepareTranslation(String code, File english) {
        File translated = langFile(code);
        String resource = "lang/messages_" + code + ".yml";
        boolean inJar = plugin.getResource(resource) != null;
        if (!translated.exists() && !inJar) {
            warnOnce.accept("no-translation:" + code, () -> "lang: " + code + " is set but lang/messages_" + code
                    + ".yml does not exist; using English.");
            return null;
        }
        renameLegacyBlock(translated, log);
        if (inJar) {
            YamlUpdater.update(plugin, resource, translated, false);
        }
        List<String> shared = sharedLines(code, log);
        if (shared != null) {
            mergeLines(log, shared, translated);
        }
        try {
            if (english.exists() && mergeLines(log, YamlText.lines(YamlText.read(english.toPath())), translated)) {
                log.info("New messages were added to lang/messages_" + code + ".yml; translate them when convenient.");
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read " + english, e);
        }
        YamlConfiguration yaml = parse(translated, log);
        if (yaml.getKeys(false).isEmpty()) {
            log.warning("lang/messages_" + code + ".yml is empty or broken; using English.");
            return null;
        }
        return yaml;
    }

    /**
     * Adds missing keys from {@code reference} to {@code file}. A file that is not valid YAML is
     * left untouched (with a warning): it holds the owner's texts and there is no jar copy of
     * it to rebuild from.
     */
    private static boolean mergeLines(Logger log, List<String> reference, File file) {
        if (file.isFile()) {
            try {
                String text = YamlPreprocessor.preprocess(YamlPreprocessor.read(file.toPath())).cleanText();
                if (!YamlUpdater.isParseable(text)) {
                    log.warning(file.getName() + " is not valid YAML, so new messages were not added to it."
                            + " Fix the file and reload.");
                    return false;
                }
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not read " + file, e);
                return false;
            }
        }
        return YamlUpdater.updateFromLines(log, reference, file);
    }

    /** True (with a warning per file) when a message file this load would read is not valid YAML. */
    private boolean anyInvalid(String wanted, Map<String, String> sources) {
        List<File> files = new ArrayList<>();
        files.add(langFile("en"));
        if (!wanted.equals("en")) {
            files.add(langFile(wanted));
        }
        for (String folder : sources.keySet()) {
            File dir = new File(plugin.getDataFolder(), folder);
            files.add(new File(dir, "messages_en.yml"));
            if (!wanted.equals("en")) {
                files.add(new File(dir, "messages_" + wanted + ".yml"));
            }
        }
        boolean invalid = false;
        for (File file : files) {
            String error = parseError(file);
            if (error != null) {
                log.warning(file.getName() + " is not valid YAML; keeping the previous messages until it is fixed. "
                        + error);
                invalid = true;
            }
        }
        return invalid;
    }

    /** The first line of the parse error of {@code file}, or null when it is valid, missing or unreadable. */
    static String parseError(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            new YamlConfiguration().loadFromString(
                    YamlPreprocessor.preprocess(YamlPreprocessor.read(file.toPath())).cleanText());
            return null;
        } catch (InvalidConfigurationException e) {
            String message = String.valueOf(e.getMessage());
            int newline = message.indexOf('\n');
            return newline < 0 ? message : message.substring(0, newline);
        } catch (IOException e) {
            return null;
        }
    }

    /** Files from SnLib times keep their texts: the {@code snlib:} block becomes {@code rslib:} once. */
    static void renameLegacyBlock(File file, Logger log) {
        if (!file.isFile()) {
            return;
        }
        try {
            Path path = file.toPath();
            List<String> lines = new ArrayList<>(YamlText.lines(YamlText.read(path)));
            int legacy = -1;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith(SHARED_BLOCK)) {
                    return;
                }
                if (legacy < 0 && line.startsWith(LEGACY_BLOCK)) {
                    legacy = i;
                }
            }
            if (legacy < 0) {
                return;
            }
            lines.set(legacy, SHARED_BLOCK + lines.get(legacy).substring(LEGACY_BLOCK.length()));
            YamlText.write(path, YamlText.join(lines));
            log.info("Renamed the 'snlib:' block of " + file.getName() + " to 'rslib:'; its texts are kept.");
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not update " + file, e);
        }
    }

    private static List<String> sharedLines(String code, Logger log) {
        try (InputStream in = RsLang.class.getResourceAsStream(String.format(SHARED_RESOURCE, code))) {
            return in == null ? null : YamlText.lines(YamlText.read(in));
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read RsLib's shared messages (" + code + ")", e);
            return null;
        }
    }

    private static YamlConfiguration parse(File file, Logger log) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.isFile()) {
            return yaml;
        }
        try {
            yaml.loadFromString(YamlPreprocessor.preprocess(YamlPreprocessor.read(file.toPath())).cleanText());
        } catch (IOException | InvalidConfigurationException e) {
            log.log(Level.WARNING, file.getName() + " could not be read; its messages are not available: "
                    + e.getMessage());
        }
        return yaml;
    }

    /** Adds the messages of an extra folder as {@code <namespace>.<key>}; keys of lang/ win. */
    private void overlay(String folder, String namespace, Layers layers) {
        File dir = new File(plugin.getDataFolder(), folder);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log.warning("Could not create " + dir);
            return;
        }
        if (passesThroughMessage(namespace, layers.active)) {
            log.warning("Messages of " + folder + " are skipped: '" + namespace + "' is already a message in lang/.");
            return;
        }
        File english = new File(dir, "messages_en.yml");
        if (plugin.getResource(folder + "/messages_en.yml") != null) {
            YamlUpdater.update(plugin, folder + "/messages_en.yml", english, false);
        }
        YamlConfiguration source = parse(english, log);
        if (!layers.language.equals("en")) {
            source = overlayTranslation(dir, folder, layers.language, english, source);
        }
        Map<String, Entry> entries = new HashMap<>();
        flatten(source, layers.prefix, entries);
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            String key = namespace + "." + entry.getKey();
            if (layers.active.containsKey(key) || layers.fallback.containsKey(key)
                    || passesThroughMessage(key, layers.active)) {
                log.warning("Message '" + key + "' from " + folder + " is ignored: lang/ already defines it.");
                continue;
            }
            layers.active.put(key, entry.getValue());
        }
    }

    /** The translated file of an extra folder, merged with its English one; {@code english} when missing. */
    private YamlConfiguration overlayTranslation(File dir, String folder, String language, File englishFile,
            YamlConfiguration english) {
        File translated = new File(dir, "messages_" + language + ".yml");
        if (!translated.isFile()) {
            warnOnce.accept("no-translation:" + folder,
                    () -> folder + "/messages_" + language + ".yml does not exist; using English.");
            return english;
        }
        try {
            if (englishFile.isFile()) {
                mergeLines(log, YamlText.lines(YamlText.read(englishFile.toPath())), translated);
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not read " + englishFile, e);
        }
        return parse(translated, log);
    }

    /** True when a parent path of {@code key} (or the key itself) is a message in {@code entries}. */
    private static boolean passesThroughMessage(String key, Map<String, Entry> entries) {
        int dot = -1;
        do {
            dot = key.indexOf('.', dot + 1);
            String parent = dot < 0 ? key : key.substring(0, dot);
            if (entries.containsKey(parent)) {
                return true;
            }
        } while (dot >= 0);
        return false;
    }

    private static void flatten(ConfigurationSection yaml, String prefix, Map<String, Entry> out) {
        for (String key : yaml.getKeys(true)) {
            Object value = yaml.get(key);
            if (value instanceof ConfigurationSection) {
                continue;
            }
            if (value instanceof List<?> list) {
                List<Line> lines = new ArrayList<>(list.size());
                for (Object item : list) {
                    lines.add(Line.of(item == null ? "" : String.valueOf(item), null));
                }
                out.put(key, new Entry(true, List.copyOf(lines)));
            } else if (value != null) {
                out.put(key, new Entry(false, List.of(Line.of(String.valueOf(value), prefix))));
            }
        }
    }

    private void warnPrefixToken(Map<String, Entry> active) {
        int count = 0;
        for (Entry entry : active.values()) {
            for (Line line : entry.lines()) {
                if (line.raw.contains("{prefix}")) {
                    count++;
                    break;
                }
            }
        }
        if (count > 0) {
            log.warning(count + " message(s) contain '{prefix}'. The prefix is added on its own, so that text"
                    + " is shown as written; remove it from those messages.");
        }
    }

    private File langFile(String code) {
        return new File(new File(plugin.getDataFolder(), "lang"), "messages_" + code + ".yml");
    }
}
