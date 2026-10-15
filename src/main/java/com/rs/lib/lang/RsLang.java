package com.rs.lib.lang;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.papi.RsPapi;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.RsYml;
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

    /** Keys from SnLib times ({@code snlib.no-permission}) are read from {@code rslib.}. */
    private static final String LEGACY_PREFIX = "snlib.";
    private static final long ACTIONBAR_REFRESH_TICKS = 40L;
    private static final int TITLE_FADE_IN = 10;
    private static final int TITLE_STAY = 70;
    private static final int TITLE_FADE_OUT = 20;
    private static final Title.Times DEFAULT_TIMES = Title.Times.times(
            ticksOf(TITLE_FADE_IN), ticksOf(TITLE_STAY), ticksOf(TITLE_FADE_OUT));
    private static final Ph[] NO_PHS = new Ph[0];

    private final Rs rs;
    private final RsYml config;
    private final RsPapi papi;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<UUID, TaskHandle> heldBars = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final LangLoader loader;
    private volatile Catalog catalog = Catalog.EMPTY;

    public RsLang(Rs rs, RsYml config) {
        this.rs = rs;
        this.config = config;
        this.papi = rs.papi();
        this.loader = new LangLoader(rs.plugin(), this::warnOnce);
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

    /** The first line as written in the file ({@code ""} for an empty list), or null when the key does not exist. */
    public String rawOrNull(String key) {
        Entry entry = key == null ? null : catalog.find(key);
        if (entry == null) {
            return null;
        }
        return entry.lines.isEmpty() ? "" : entry.lines.get(0).raw;
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
        // Swapped in one step: when another send put its bar since cancelBar above, that timer is
        // cancelled here instead of being left running with nobody able to cancel it.
        TaskHandle previous = heldBars.put(id, self[0]);
        if (previous != null) {
            previous.cancel();
        }
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
        TitleParts parts = entry.lines.get(0).title;
        UnaryOperator<String> resolver = papiFor(target, false);
        Component title = parts.title.render(resolver, phs);
        Component subtitle = parts.subtitle == null ? Component.empty() : parts.subtitle.render(resolver, phs);
        target.showTitle(Title.title(title, subtitle, parts.times));
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
        boolean changed = loader.mergeIntoEnglish(YamlText.lines(yamlText));
        if (changed) {
            load();
        }
        return changed;
    }

    private synchronized void load() {
        warned.clear();
        String wanted = config == null ? "en" : config.getString("lang", "en").trim().toLowerCase(Locale.ROOT);
        Map<String, String> extra;
        synchronized (sources) {
            extra = new LinkedHashMap<>(sources);
        }
        Catalog next = loader.load(wanted, extra, catalog != Catalog.EMPTY);
        if (next != null) {
            catalog = next;
        }
    }

    // ---------------------------------------------------------------- rendering

    private Entry lookup(String key) {
        if (key == null) {
            warnOnce("missing:null", () -> "A message was requested with a null key.");
            return null;
        }
        if (key.startsWith(LEGACY_PREFIX)) {
            key = "rslib." + key.substring(LEGACY_PREFIX.length());
        }
        Catalog current = catalog;
        Entry entry = current.active.get(key);
        if (entry != null) {
            return entry;
        }
        entry = current.fallback.get(key);
        String found = key;
        if (entry != null) {
            warnOnce("fallback:" + key, () -> "Message '" + found + "' is missing from messages_"
                    + current.language + ".yml; using the English text.");
            return entry;
        }
        warnOnce("missing:" + key, () -> "Message '" + found + "' does not exist in the language files.");
        return null;
    }

    private List<Component> render(String key, Player viewer, boolean here, boolean withPrefix, Ph... phs) {
        Entry entry = lookup(key);
        if (entry == null) {
            return List.of(Component.text(missing(key)));
        }
        UnaryOperator<String> resolver = papiFor(viewer, here);
        if (entry.list) {
            List<Component> out = new ArrayList<>(entry.lines.size());
            for (Line line : entry.lines) {
                out.add(line.plain.render(resolver, phs));
            }
            return out;
        }
        Line line = entry.lines.get(0);
        if (line.raw.isEmpty()) {
            return List.of();
        }
        return List.of((withPrefix ? line.prefixed : line.plain).render(resolver, phs));
    }

    /**
     * PlaceholderAPI for one send, or null when it cannot change the text: not installed, or
     * off the main thread outside the {@code viewer} variants of {@code get}.
     */
    private UnaryOperator<String> papiFor(Player viewer, boolean here) {
        if (!papi.available()) {
            return null;
        }
        if (here) {
            return text -> papi.applyHere(viewer, text);
        }
        return Bukkit.isPrimaryThread() ? text -> papi.apply(viewer, text) : null;
    }

    private static Duration ticksOf(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    private static String missing(String key) {
        return "<missing:" + key + ">";
    }

    /** Logs {@code message} the first time {@code id} comes up after a load; the text is built only then. */
    private void warnOnce(String id, Supplier<String> message) {
        if (warned.add(id)) {
            rs.plugin().getLogger().warning(message.get());
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
    record Catalog(String language, String prefix, Map<String, Entry> active, Map<String, Entry> fallback) {
        static final Catalog EMPTY = new Catalog("en", "", Map.of(), Map.of());

        Entry find(String key) {
            Entry entry = active.get(key);
            return entry != null ? entry : fallback.get(key);
        }
    }

    record Entry(boolean list, List<Line> lines) {
    }

    /** One line, compiled once with and without the prefix, and as a title. */
    static final class Line {

        final String raw;
        final Compiled plain;
        final Compiled prefixed;
        final TitleParts title;

        private Line(String raw, Compiled plain, Compiled prefixed) {
            this.raw = raw;
            this.plain = plain;
            this.prefixed = prefixed;
            this.title = raw.indexOf(';') < 0 ? new TitleParts(plain, null, DEFAULT_TIMES) : TitleParts.split(raw);
        }

        static Line of(String raw, String prefix) {
            Compiled plain = new Compiled(raw);
            String withPrefix = prefix == null || prefix.isEmpty() ? null : RsText.insertPrefix(raw, prefix);
            // [noprefix] gives the line back as it is: the plain form serves both.
            return new Line(raw, plain, withPrefix == null || withPrefix.equals(raw) ? plain : new Compiled(withPrefix));
        }
    }

    /**
     * A compiled text. A text without placeholders renders the same on every send, so its
     * render is kept after the first one.
     */
    static final class Compiled {

        private final RsText.Template template;
        private final boolean placeholders;
        private volatile Component rendered;

        Compiled(String raw) {
            this.template = RsText.compile(raw);
            this.placeholders = template.hasPlaceholders();
        }

        /**
         * The text for one send. {@code papi} (null when it cannot run) is only used when a
         * {@code %token%} is left after the local placeholders; otherwise the compiled template
         * renders, which gives the same component.
         */
        Component render(UnaryOperator<String> papi, Ph[] phs) {
            if (!placeholders) {
                Component cached = rendered;
                if (cached == null) {
                    cached = template.render(NO_PHS);
                    rendered = cached;
                }
                return cached;
            }
            String raw = template.raw();
            if (papi != null && (raw.indexOf('%') >= 0 || formattedPercent(phs))) {
                String filled = RsText.applyLocals(raw, phs);
                if (hasPapiToken(filled)) {
                    return RsText.colorUncached(RsText.normalizePapiOutput(papi.apply(filled)));
                }
            }
            return template.render(phs == null ? NO_PHS : phs);
        }

        /** A formatted value can carry placeholders of its own ({@code Ph.of("rank", "%vault_rank%")}). */
        private static boolean formattedPercent(Ph[] phs) {
            if (phs == null) {
                return false;
            }
            for (Ph ph : phs) {
                if (ph != null && !ph.literal() && ph.value().indexOf('%') >= 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether PlaceholderAPI could replace anything: its tokens are {@code %name%}, so a
         * single {@code %} ({@code 50%}) is copied unchanged by it.
         */
        private static boolean hasPapiToken(String text) {
            int first = text.indexOf('%');
            return first >= 0 && text.indexOf('%', first + 1) >= 0;
        }
    }

    /** A line read as {@code title;subtitle;fadeIn;stay;fadeOut}, split and compiled once. */
    record TitleParts(Compiled title, Compiled subtitle, Title.Times times) {

        static TitleParts split(String raw) {
            String[] parts = raw.split(";", -1);
            Title.Times times = Title.Times.times(
                    ticks(parts, 2, TITLE_FADE_IN), ticks(parts, 3, TITLE_STAY), ticks(parts, 4, TITLE_FADE_OUT));
            return new TitleParts(new Compiled(parts[0]), parts.length > 1 ? new Compiled(parts[1]) : null, times);
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
            return ticksOf(value);
        }
    }
}
