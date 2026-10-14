package com.rs.lib.lang;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.papi.RsPapi;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YamlPreprocessor;
import com.rs.lib.yml.YamlUpdater;
import com.rs.lib.yml.internal.ResourceFolders;
import com.rs.lib.yml.internal.YamlText;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * The plugin's messages: {@code lang/messages_<code>.yml}, chosen with {@code lang:} in the
 * config ({@code en} by default), reached with {@code rs().lang()}.
 *
 * <p>On every load the English file is seeded or merged from the jar and receives RsLib's
 * shared {@code rslib.*} messages. The chosen translation is seeded from the jar when it is
 * missing, merged first with the jar's own translation and then with the English file, so new
 * keys arrive translated when the jar has them and in English otherwise. A key missing from the
 * translation falls back to English. A file that still has the old {@code snlib:} block keeps
 * its texts: the block is renamed to {@code rslib:} once.</p>
 *
 * <p>Every line is compiled once per load. A line without placeholders is rendered once and
 * reused; the rest are filled in on each send. Sending is safe from any thread; PlaceholderAPI
 * only resolves on the main thread except in the {@code viewer} variants of {@code get}.</p>
 */
public final class RsLang {

    private static final String SHARED_RESOURCE = "/rslib-messages_%s.yml";
    private static final String LEGACY_BLOCK = "snlib:";
    private static final String SHARED_BLOCK = "rslib:";
    private static final long ACTIONBAR_REFRESH_TICKS = 40L;
    private static final int TITLE_FADE_IN = 10;
    private static final int TITLE_STAY = 70;
    private static final int TITLE_FADE_OUT = 20;

    private final Rs rs;
    private final JavaPlugin plugin;
    private final RsYml config;
    private final RsPapi papi;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<UUID, TaskHandle> heldBars = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile Catalog catalog = Catalog.EMPTY;

    public RsLang(Rs rs, RsYml config) {
        this.rs = rs;
        this.plugin = rs.plugin();
        this.config = config;
        this.papi = rs.papi();
        rs.onQuit(player -> cancelBar(player.getUniqueId()));
        load();
    }

    /** The language in use: the {@code lang} code from the config when its file exists, else {@code en}. */
    public String language() {
        return catalog.language;
    }

    // ---------------------------------------------------------------- sending

    public void send(Player target, String key, Ph... phs) {
        send((CommandSender) target, key, phs);
    }

    /**
     * Sends a message. A single line gets the prefix (unless it starts with {@code [noprefix]})
     * and is skipped when empty; each line of a list is sent on its own, without the prefix.
     */
    public void send(CommandSender target, String key, Ph... phs) {
        if (target == null) {
            return;
        }
        for (Component line : render(key, target instanceof Player player ? player : null, false, true, phs)) {
            target.sendMessage(line);
        }
    }

    /** Sends to every player and the console; player placeholders are not resolved. */
    public void broadcast(String key, Ph... phs) {
        for (Component line : render(key, null, false, true, phs)) {
            Bukkit.getServer().sendMessage(line);
        }
    }

    /** Sends to everyone, resolving PlaceholderAPI once against {@code subject}. */
    public void broadcast(String key, Player subject, Ph... phs) {
        for (Component line : render(key, subject, false, true, phs)) {
            Bukkit.getServer().sendMessage(line);
        }
    }

    /** The first line, rendered, without the prefix; player placeholders are not resolved. */
    public Component get(String key, Ph... phs) {
        List<Component> lines = render(key, null, false, false, phs);
        return lines.isEmpty() ? Component.empty() : lines.get(0);
    }

    /** The first line for {@code viewer}; resolves PlaceholderAPI on the calling thread (async chat). */
    public Component get(String key, Player viewer, Ph... phs) {
        List<Component> lines = render(key, viewer, true, false, phs);
        return lines.isEmpty() ? Component.empty() : lines.get(0);
    }

    /** The first line as written in the file, or null when the key does not exist. */
    public String rawOrNull(String key) {
        Entry entry = catalog.find(key);
        return entry == null || entry.lines.isEmpty() ? null : entry.lines.get(0).raw;
    }

    /** The first line as a legacy string with section signs (no MiniMessage). */
    public String getLegacy(String key, Ph... phs) {
        Entry entry = lookup(key);
        if (entry == null) {
            return missing(key);
        }
        return entry.lines.isEmpty() ? "" : RsText.colorLegacy(RsText.applyLocals(entry.lines.get(0).raw, phs));
    }

    /** Every line, rendered, without the prefix. */
    public List<Component> getList(String key, Ph... phs) {
        return render(key, null, false, false, phs);
    }

    /** Every line for {@code viewer}, resolving PlaceholderAPI on the calling thread. */
    public List<Component> getList(String key, Player viewer, Ph... phs) {
        return render(key, viewer, true, false, phs);
    }

    /** The first line in the action bar. */
    public void actionbar(Player target, String key, Ph... phs) {
        actionbar(target, key, null, phs);
    }

    /**
     * The first line in the action bar, kept visible for {@code hold}: rendered once, sent
     * again every two seconds and cleared at the end. A new held bar from this plugin replaces
     * the previous one; leaving the server cancels it.
     */
    public void actionbar(Player target, String key, Duration hold, Ph... phs) {
        if (target == null) {
            return;
        }
        List<Component> lines = render(key, target, false, false, phs);
        if (lines.isEmpty()) {
            return;
        }
        Component bar = lines.get(0);
        UUID id = target.getUniqueId();
        cancelBar(id);
        target.sendActionBar(bar);
        if (hold == null || hold.isZero() || hold.isNegative()) {
            return;
        }
        long until = System.nanoTime() + hold.toNanos();
        TaskHandle[] self = new TaskHandle[1];
        self[0] = rs.scheduler().timer(ACTIONBAR_REFRESH_TICKS, ACTIONBAR_REFRESH_TICKS, () -> {
            if (!target.isOnline()) {
                heldBars.remove(id, self[0]);
                self[0].cancel();
                return;
            }
            if (System.nanoTime() - until >= 0) {
                heldBars.remove(id, self[0]);
                self[0].cancel();
                target.sendActionBar(Component.empty());
                return;
            }
            target.sendActionBar(bar);
        });
        heldBars.put(id, self[0]);
    }

    /**
     * A title from the first line, written {@code title;subtitle;fadeIn;stay;fadeOut} (ticks).
     * Missing or invalid times use 10, 70 and 20.
     */
    public void title(Player target, String key, Ph... phs) {
        if (target == null) {
            return;
        }
        Entry entry = lookup(key);
        if (entry == null || entry.lines.isEmpty()) {
            if (entry == null) {
                target.sendMessage(Component.text(missing(key)));
            }
            return;
        }
        String[] parts = entry.lines.get(0).raw.split(";", -1);
        Component title = renderLoose(parts[0], target, phs);
        Component subtitle = parts.length > 1 ? renderLoose(parts[1], target, phs) : Component.empty();
        Title.Times times = Title.Times.times(
                ticks(parts, 2, TITLE_FADE_IN), ticks(parts, 3, TITLE_STAY), ticks(parts, 4, TITLE_FADE_OUT));
        target.showTitle(Title.title(title, subtitle, times));
    }

    // ---------------------------------------------------------------- loading

    /** Reads every message file again (and seeds or merges them first). */
    public void reload() {
        load();
    }

    /**
     * Serves the messages of an extra folder (for example {@code addons/rewards}) as
     * {@code <namespace>.<key>}. Keys already in {@code lang/} always win. Main thread only.
     */
    public void addSource(String folder, String namespace) {
        String clean = ResourceFolders.normalize(folder, "lang", "message");
        if (namespace == null || namespace.isBlank() || namespace.indexOf(' ') >= 0
                || namespace.startsWith(".") || namespace.endsWith(".")) {
            throw new IllegalArgumentException("Invalid message namespace '" + namespace + "'");
        }
        synchronized (sources) {
            sources.put(clean, namespace.trim());
        }
        load();
    }

    public void removeSource(String folder) {
        String clean = ResourceFolders.normalize(folder, "lang", "message");
        boolean removed;
        synchronized (sources) {
            removed = sources.remove(clean) != null;
        }
        if (removed) {
            load();
        }
    }

    /**
     * Adds the entries of {@code yamlText} that the English file lacks (and the translation in
     * use, through the usual merge). Existing texts are never changed. Used by the command
     * module for its {@code commands:} block.
     *
     * @return true when a file changed and the messages were read again
     */
    public boolean mergeDefaults(String yamlText) {
        File english = langFile("en");
        boolean changed = YamlUpdater.updateFromLines(plugin.getLogger(), YamlText.lines(yamlText), english);
        if (changed) {
            load();
        }
        return changed;
    }

    private synchronized void load() {
        warned.clear();
        Logger log = plugin.getLogger();
        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.isDirectory() && !folder.mkdirs()) {
            log.warning("Could not create " + folder);
        }
        File english = langFile("en");
        prepareEnglish(english, log);
        YamlConfiguration englishYaml = parse(english, log);

        String wanted = config == null ? "en" : config.getString("lang", "en").trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty() || !wanted.matches("[a-z0-9_-]+")) {
            if (!wanted.isEmpty()) {
                log.warning("lang: '" + wanted + "' is not a valid language code; using English.");
            }
            wanted = "en";
        }
        YamlConfiguration activeYaml = englishYaml;
        String language = "en";
        if (!wanted.equals("en")) {
            YamlConfiguration translated = prepareTranslation(wanted, english, log);
            if (translated != null) {
                activeYaml = translated;
                language = wanted;
            }
        }

        Map<String, Entry> active = new HashMap<>();
        Map<String, Entry> fallback = new HashMap<>();
        String prefix = activeYaml.getString("prefix", englishYaml.getString("prefix", ""));
        flatten(activeYaml, prefix, active);
        if (activeYaml != englishYaml) {
            flatten(englishYaml, prefix, fallback);
        }
        Map<String, String> extra;
        synchronized (sources) {
            extra = new LinkedHashMap<>(sources);
        }
        for (Map.Entry<String, String> source : extra.entrySet()) {
            overlay(source.getKey(), source.getValue(), language, prefix, active, fallback, log);
        }
        catalog = new Catalog(language, prefix == null ? "" : prefix, Map.copyOf(active), Map.copyOf(fallback));
        warnPrefixToken(active, log);
    }

    private void prepareEnglish(File english, Logger log) {
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
            YamlUpdater.updateFromLines(log, shared, english);
        }
    }

    /** The translation for {@code code}, seeded and merged; null when there is none to use. */
    private YamlConfiguration prepareTranslation(String code, File english, Logger log) {
        File translated = langFile(code);
        String resource = "lang/messages_" + code + ".yml";
        boolean inJar = plugin.getResource(resource) != null;
        if (!translated.exists() && !inJar) {
            warnOnce("no-translation:" + code, "lang: " + code + " is set but lang/messages_" + code
                    + ".yml does not exist; using English.");
            return null;
        }
        renameLegacyBlock(translated, log);
        if (inJar) {
            YamlUpdater.update(plugin, resource, translated, false);
        }
        List<String> shared = sharedLines(code, log);
        if (shared != null) {
            YamlUpdater.updateFromLines(log, shared, translated);
        }
        try {
            if (english.exists() && YamlUpdater.updateFromLines(log, YamlText.lines(YamlText.read(english.toPath())),
                    translated)) {
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

    private void overlay(String folder, String namespace, String language, String prefix, Map<String, Entry> active,
            Map<String, Entry> fallback, Logger log) {
        File dir = new File(plugin.getDataFolder(), folder);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log.warning("Could not create " + dir);
            return;
        }
        if (passesThroughMessage(namespace, active)) {
            log.warning("Messages of " + folder + " are skipped: '" + namespace + "' is already a message in lang/.");
            return;
        }
        File english = new File(dir, "messages_en.yml");
        if (plugin.getResource(folder + "/messages_en.yml") != null) {
            YamlUpdater.update(plugin, folder + "/messages_en.yml", english, false);
        }
        YamlConfiguration source = parse(english, log);
        if (!language.equals("en")) {
            File translated = new File(dir, "messages_" + language + ".yml");
            if (translated.isFile()) {
                try {
                    if (english.isFile()) {
                        YamlUpdater.updateFromLines(log, YamlText.lines(YamlText.read(english.toPath())), translated);
                    }
                } catch (IOException e) {
                    log.log(Level.WARNING, "Could not read " + english, e);
                }
                source = parse(translated, log);
            } else {
                warnOnce("no-translation:" + folder, folder + "/messages_" + language + ".yml does not exist; using English.");
            }
        }
        Map<String, Entry> entries = new HashMap<>();
        flatten(source, prefix, entries);
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            String key = namespace + "." + entry.getKey();
            if (active.containsKey(key) || fallback.containsKey(key) || passesThroughMessage(key, active)) {
                log.warning("Message '" + key + "' from " + folder + " is ignored: lang/ already defines it.");
                continue;
            }
            active.put(key, entry.getValue());
        }
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

    private void warnPrefixToken(Map<String, Entry> active, Logger log) {
        int count = 0;
        for (Entry entry : active.values()) {
            for (Line line : entry.lines) {
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

    // ---------------------------------------------------------------- rendering

    private Entry lookup(String key) {
        Catalog current = catalog;
        Entry entry = current.active.get(key);
        if (entry != null) {
            return entry;
        }
        entry = current.fallback.get(key);
        if (entry != null) {
            warnOnce("fallback:" + key, "Message '" + key + "' is missing from messages_" + current.language
                    + ".yml; using the English text.");
            return entry;
        }
        warnOnce("missing:" + key, "Message '" + key + "' does not exist in the language files.");
        return null;
    }

    private List<Component> render(String key, Player viewer, boolean here, boolean withPrefix, Ph... phs) {
        Entry entry = lookup(key);
        if (entry == null) {
            return List.of(Component.text(missing(key)));
        }
        if (entry.list) {
            List<Component> out = new ArrayList<>(entry.lines.size());
            for (Line line : entry.lines) {
                out.add(line.render(false, viewer, here, papi, phs));
            }
            return out;
        }
        Line line = entry.lines.get(0);
        if (line.raw.isEmpty()) {
            return List.of();
        }
        return List.of(line.render(withPrefix, viewer, here, papi, phs));
    }

    private Component renderLoose(String raw, Player viewer, Ph... phs) {
        return Line.of(raw, null).render(false, viewer, false, papi, phs);
    }

    private static Duration ticks(String[] parts, int index, int fallback) {
        int value = fallback;
        if (index < parts.length) {
            try {
                value = Math.max(0, Integer.parseInt(parts[index].trim()));
            } catch (NumberFormatException notANumber) {
                value = fallback;
            }
        }
        return Duration.ofMillis(value * 50L);
    }

    private static String missing(String key) {
        return "<missing:" + key + ">";
    }

    private void warnOnce(String id, String message) {
        if (warned.add(id)) {
            plugin.getLogger().warning(message);
        }
    }

    private void cancelBar(UUID player) {
        TaskHandle previous = heldBars.remove(player);
        if (previous != null) {
            previous.cancel();
        }
    }

    // ---------------------------------------------------------------- caches

    /** Everything one load produced; replaced as a whole, so readers never see half a load. */
    private record Catalog(String language, String prefix, Map<String, Entry> active, Map<String, Entry> fallback) {
        static final Catalog EMPTY = new Catalog("en", "", Map.of(), Map.of());

        Entry find(String key) {
            Entry entry = active.get(key);
            return entry != null ? entry : fallback.get(key);
        }
    }

    private record Entry(boolean list, List<Line> lines) {
    }

    /** One line, compiled once with and without the prefix. */
    private static final class Line {

        final String raw;
        final RsText.Template plain;
        final RsText.Template prefixed;
        private volatile Component plainStatic;
        private volatile Component prefixedStatic;

        private Line(String raw, RsText.Template plain, RsText.Template prefixed) {
            this.raw = raw;
            this.plain = plain;
            this.prefixed = prefixed;
        }

        static Line of(String raw, String prefix) {
            RsText.Template plain = RsText.compile(raw);
            String withPrefix = prefix == null || prefix.isEmpty() ? null : insertPrefix(raw, prefix);
            RsText.Template prefixed = withPrefix == null ? plain : RsText.compile(withPrefix);
            return new Line(raw, plain, prefixed);
        }

        Component render(boolean withPrefix, Player viewer, boolean here, RsPapi papi, Ph... phs) {
            RsText.Template template = withPrefix ? prefixed : plain;
            String text = template.raw();
            boolean noLocals = phs == null || phs.length == 0;
            if (noLocals && isStatic(text)) {
                Component cached = withPrefix ? prefixedStatic : plainStatic;
                if (cached == null) {
                    cached = template.render();
                    if (withPrefix) {
                        prefixedStatic = cached;
                    } else {
                        plainStatic = cached;
                    }
                }
                return cached;
            }
            if (text.indexOf('%') >= 0 && papi.available()) {
                String filled = RsText.applyLocals(text, phs);
                String resolved = here ? papi.applyHere(viewer, filled) : papi.apply(viewer, filled);
                return RsText.color(RsText.normalizePapiOutput(resolved));
            }
            return template.render(noLocals ? new Ph[0] : phs);
        }

        private static boolean isStatic(String text) {
            return text.indexOf('%') < 0 && text.indexOf('{') < 0;
        }
    }

    /**
     * Puts the prefix after the leading {@code [center] [rgb] [small]} tags, so they apply to it
     * too; a line with {@code [noprefix]} among them is left as it is.
     */
    static String insertPrefix(String line, String prefix) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '[') {
            int length = tagAt(line, i);
            if (length == 0) {
                break;
            }
            if (line.regionMatches(true, i, "[noprefix]", 0, length)) {
                return line;
            }
            i += length;
        }
        return line.substring(0, i) + prefix + line.substring(i);
    }

    private static final List<String> LEADING_TAGS = List.of("[center]", "[rgb]", "[small]", "[noprefix]");

    private static int tagAt(String line, int at) {
        for (String tag : LEADING_TAGS) {
            if (line.regionMatches(true, at, tag, 0, tag.length())) {
                return tag.length();
            }
        }
        return 0;
    }
}
