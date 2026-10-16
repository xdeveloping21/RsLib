package com.rs.lib.yml;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import com.rs.lib.Ph;
import com.rs.lib.text.RsText;
import com.rs.lib.util.Formats;
import com.rs.lib.text.internal.SharedPlaceholders;
import com.rs.lib.yml.internal.YamlText;

/**
 * One YAML file of a plugin, mounted through {@link YmlManager}.
 *
 * <h2>Reading</h2>
 * <p>The file is read as UTF-8 (invalid bytes replaced, byte order mark dropped), indentation
 * tabs are repaired with one warning listing the lines, and Bukkit's {@link YamlConfiguration}
 * parses it. A missing file reads as empty. A file that does not parse keeps the content read
 * before, with a warning.</p>
 *
 * <h2>Getters</h2>
 * <ul>
 *     <li>A missing key answers the default silently; a key written with no value
 *     ({@code key: ~}) answers the default with a warning.</li>
 *     <li>A value of the wrong type answers the default with the warning
 *     {@code Invalid value in <file> -> '<key>': received '<value>', using default '<def>'},
 *     printed once per key and value until the file is read again (menus read every tick).</li>
 *     <li>Texts are resolved in this order: the file's own placeholders
 *     ({@link #placeholder}), the {@link Ph} passed to the call, then PlaceholderAPI, only on the
 *     main thread and only if a {@code %} is left. So a local placeholder can live inside a
 *     PlaceholderAPI token ({@code %math_1_{buff-value}/100%}). Off the main thread the
 *     PlaceholderAPI tokens are left as written. Defaults are returned as given.</li>
 *     <li>{@link #getString} on a number or boolean warns but returns it as text.</li>
 *     <li>Number getters accept a number or a text that resolves to one.</li>
 *     <li>{@link #getBoolean} accepts a boolean or the text {@code true}/{@code false}.</li>
 *     <li>{@link #getEnum} reads the raw value, ignoring case, and undoes YAML 1.1: an unquoted
 *     {@code OFF}/{@code NO}/{@code FALSE} arrives as {@code false} and maps to the first of
 *     those constants the enum has, {@code ON}/{@code YES}/{@code TRUE} likewise.</li>
 *     <li>{@link #getSection} returns Bukkit's raw section: no placeholders, no warnings.</li>
 * </ul>
 *
 * <h2>Writing</h2>
 * <p>{@link #set}, {@link #setComments} and {@link #setInlineComments} change memory only.
 * {@link #save()} turns the whole file into text on the calling thread and writes it off the
 * main thread; saves made before that write happens are merged into one (at most one pending
 * write per file). Every save is numbered, so an older text never replaces a newer one. While
 * the plugin is shutting down, saves are written immediately on the calling thread. Writes go
 * to a temporary file in the same folder that then replaces the file atomically, with
 * {@code '\n'} line endings. {@code save()} is the right call at run time; {@link #flush()} is
 * for shutdown.</p>
 *
 * <h2>Threads</h2>
 * <p>Call {@link #set}, {@link #setComments}, {@link #setInlineComments}, {@link #save()} and
 * {@link #reload()} on the main thread. Getters may be called from other threads, but only for
 * a file that is not being changed at the same time (a config read by async tasks is fine; a
 * data file that the main thread keeps {@code set}ting is not): the getters take no lock, so
 * they stay cheap enough to call every tick.</p>
 */
public final class RsYml {

    private static final long FLUSH_WAIT_SECONDS = 10L;
    private static final String[] FALSE_NAMES = {"OFF", "NO", "FALSE"};
    private static final String[] TRUE_NAMES = {"ON", "YES", "TRUE"};

    private final YmlHost host;
    private final File file;
    private final String name;
    private final Map<String, Supplier<String>> locals = new ConcurrentHashMap<>();
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Object memoryLock = new Object();
    // Short swaps of the pending fields only; never held during disk I/O.
    private final Object writeState = new Object();
    // Held for the sequence check and the move over the file, never by save().
    private final Object commitLock = new Object();
    private final ReentrantLock ioLock = new ReentrantLock();

    private volatile YamlConfiguration config = new YamlConfiguration();
    private volatile Set<String> nullKeys = Set.of();
    private volatile Runnable prepare = () -> { };
    private volatile boolean invalidReported;

    // Guarded by writeState.
    private String pendingText;
    private long pendingSeq;
    private long nextSeq;
    private boolean scheduled;

    // Guarded by commitLock.
    private long writtenSeq;

    RsYml(YmlHost host, File file, String name) {
        this.host = Objects.requireNonNull(host, "host");
        this.file = Objects.requireNonNull(file, "file");
        this.name = name == null ? file.getName() : name;
    }

    /** The file on disk. */
    public File file() {
        return file;
    }

    /** The path this file was mounted with, relative to the plugin folder. */
    public String name() {
        return name;
    }

    // ------------------------------------------------------------------ reading

    /** Set by the manager: seeding or merging that runs before every {@link #reload()}. */
    void prepare(Runnable step) {
        this.prepare = step == null ? () -> { } : step;
    }

    /**
     * Set by the manager when the reload step already warned that the file is not valid YAML,
     * so the next {@link #read()} does not warn a second time.
     */
    void invalidReported() {
        this.invalidReported = true;
    }

    /**
     * Reads the file from disk. Keeps the previous content when the file cannot be read or
     * parsed.
     *
     * @return true when the content was replaced
     */
    boolean read() {
        warned.clear();
        boolean reported = invalidReported;
        invalidReported = false;
        Path path = file.toPath();
        if (!Files.exists(path)) {
            replace(new YamlConfiguration(), Set.of());
            return true;
        }
        String clean;
        try {
            YamlPreprocessor.Result result = YamlPreprocessor.preprocess(YamlPreprocessor.read(path));
            if (!result.fixedLines().isEmpty()) {
                host.logger().warning("Indentation tabs fixed in " + name + " (lines " + result.fixedLines() + ")");
            }
            clean = result.cleanText();
        } catch (IOException | RuntimeException e) {
            host.logger().warning("Could not read " + name + "; keeping the previous content: " + e);
            return false;
        }
        YamlConfiguration next = new YamlConfiguration();
        try {
            next.loadFromString(clean);
        } catch (InvalidConfigurationException | RuntimeException e) {
            String message = "Could not parse " + name + "; keeping the previous content. " + firstLine(e.getMessage());
            if (reported) {
                host.logger().fine(message);
            } else {
                host.logger().warning(message);
            }
            return false;
        }
        replace(next, nullKeys(clean));
        return true;
    }

    private void replace(YamlConfiguration next, Set<String> nulls) {
        synchronized (memoryLock) {
            config = next;
            nullKeys = nulls;
        }
    }

    /**
     * Writes what is pending, seeds or merges the file again when its mode asks for it, reads it
     * and runs the {@link #onReload} hooks.
     */
    public void reload() {
        flushPendingQuietly();
        try {
            prepare.run();
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not update " + name + " from the jar", e);
        }
        read();
        fireHooks();
    }

    void fireHooks() {
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException | LinkageError e) {
                host.logger().log(Level.WARNING, "A reload hook of " + name + " failed", e);
            }
        }
    }

    /** Runs {@code hook} after every reload of this file. */
    public void onReload(Runnable hook) {
        hooks.add(Objects.requireNonNull(hook, "hook"));
    }

    /**
     * Registers a placeholder resolved in every text read from this file, as {@code {key}} or
     * {@code %key%}. It has no viewer: during a menu's timed refresh it is computed once per tick
     * and shared by every viewer (see {@link SharedPlaceholders}).
     */
    public RsYml placeholder(String key, Supplier<String> value) {
        locals.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return this;
    }

    /** Registers several placeholders (see {@link #placeholder}). */
    public RsYml placeholders(Map<String, Supplier<String>> values) {
        values.forEach(this::placeholder);
        return this;
    }

    // ------------------------------------------------------------------ getters

    public String getString(String key, String def) {
        return getString(key, def, null);
    }

    public String getString(String key, String def, Player viewer) {
        return getString(key, def, viewer, new Ph[0]);
    }

    public String getString(String key, String def, Player viewer, Ph... phs) {
        Object value = config.get(key, null);
        if (value == null) {
            return absent(key, def);
        }
        if (value instanceof String text) {
            return resolve(key, text, viewer, phs);
        }
        if (value instanceof ConfigurationSection || value instanceof Map<?, ?>) {
            return invalid(key, value, def);
        }
        String text = String.valueOf(value);
        warnOnce(key, value, "Value in " + name + " -> '" + key + "' is not text ('" + text
                + "'); using it as text. Put it in quotes to silence this warning.");
        return text;
    }

    public int getInt(String key, int def) {
        return number(key, def, Number::intValue, Integer::parseInt);
    }

    public long getLong(String key, long def) {
        return number(key, def, Number::longValue, Long::parseLong);
    }

    public double getDouble(String key, double def) {
        return number(key, def, Number::doubleValue, Double::parseDouble);
    }

    /** A number, or a text that resolves to one through {@code parse}; the default otherwise. */
    private <T> T number(String key, T def, Function<Number, T> fromNumber, Function<String, T> parse) {
        Object value = config.get(key, null);
        if (value == null) {
            return absent(key, def);
        }
        if (value instanceof Number number) {
            return fromNumber.apply(number);
        }
        if (value instanceof String text) {
            try {
                return parse.apply(resolve(key, text, null, null).trim());
            } catch (NumberFormatException e) {
                return invalid(key, value, def);
            }
        }
        return invalid(key, value, def);
    }

    public boolean getBoolean(String key, boolean def) {
        Object value = config.get(key, null);
        if (value == null) {
            return absent(key, def);
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String resolved = resolve(key, text, null, null).trim();
            if (resolved.equalsIgnoreCase("true")) {
                return true;
            }
            if (resolved.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return invalid(key, value, def);
    }

    /**
     * Reads an enum constant from the raw value (no placeholders), ignoring case. An unquoted
     * {@code off}/{@code on} (and yes/no/true/false) arrives from YAML as a boolean and is mapped
     * to the first of OFF, NO, FALSE (or ON, YES, TRUE) the enum declares.
     */
    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E def) {
        Object value = config.get(key, null);
        if (value == null) {
            return absent(key, def);
        }
        if (value instanceof Boolean bool) {
            E mapped = byBoolean(type, bool);
            if (mapped != null) {
                return mapped;
            }
            warnOnce(key, value, "Invalid value in " + name + " -> '" + key + "': received '" + value
                    + "', using default '" + def + "'. An unquoted on/off/yes/no/true/false is read as a "
                    + "boolean, never as a name; write it in quotes.");
            return def;
        }
        if (value instanceof String text) {
            E found = byName(type, text.trim());
            if (found != null) {
                return found;
            }
        }
        return invalid(key, value, def);
    }

    static <E extends Enum<E>> E byName(Class<E> type, String text) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(text)) {
                return constant;
            }
        }
        return null;
    }

    static <E extends Enum<E>> E byBoolean(Class<E> type, boolean value) {
        for (String candidate : value ? TRUE_NAMES : FALSE_NAMES) {
            E found = byName(type, candidate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public List<String> getStringList(String key, List<String> def) {
        return getStringList(key, def, null);
    }

    public List<String> getStringList(String key, List<String> def, Player viewer) {
        return getStringList(key, def, viewer, new Ph[0]);
    }

    public List<String> getStringList(String key, List<String> def, Player viewer, Ph... phs) {
        Object value = config.get(key, null);
        if (value == null) {
            return absent(key, def);
        }
        if (!(value instanceof List<?> list)) {
            return invalid(key, value, def);
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            out.add(element == null ? "" : resolve(key, String.valueOf(element), viewer, phs));
        }
        return out;
    }

    /** Bukkit's section at {@code key}, or null; values read from it are not resolved nor checked. */
    public ConfigurationSection getSection(String key) {
        return config.getConfigurationSection(key);
    }

    /** True when the key has a value, even {@code 0}, {@code false} or an empty text. */
    public boolean isSet(String key) {
        return config.contains(key, true);
    }

    // ------------------------------------------------------------------ resolution and warnings

    private <T> T absent(String key, T def) {
        if (nullKeys.contains(key)) {
            warnOnce(key, null, "Invalid value in " + name + " -> '" + key + "': received no value, using default '"
                    + def + "'");
        }
        return def;
    }

    private <T> T invalid(String key, Object value, T def) {
        warnOnce(key, value, "Invalid value in " + name + " -> '" + key + "': received '" + value
                + "', using default '" + def + "'");
        return def;
    }

    private void warnOnce(String key, Object value, String message) {
        if (warned.add(key + '\u0000' + value)) {
            host.logger().warning(message);
        }
    }

    /**
     * Resolves a raw text read from {@link #getSection} the way the text getters do: this file's
     * placeholders, then {@code locals} (null means none), then PlaceholderAPI on the main
     * thread. For definitions parsed once and rendered many times, such as menu items.
     */
    public String resolveText(String text, Player viewer, Function<String, String> locals) {
        if (text == null) {
            return null;
        }
        String out = text;
        Object previous = Formats.enterPlaceholderTexts(host.relativeTexts());
        try {
            if (!this.locals.isEmpty() && (out.indexOf('{') >= 0 || out.indexOf('%') >= 0)) {
                out = RsText.applyLocals(out, this::local);
            }
            if (locals != null) {
                out = RsText.applyLocals(out, locals);
            }
        } finally {
            Formats.exitPlaceholderTexts(previous);
        }
        if (out.indexOf('%') >= 0 && host.isPrimaryThread()) {
            out = host.papi(viewer, out);
        }
        return out;
    }

    private String resolve(String key, String text, Player viewer, Ph[] phs) {
        String out = text;
        boolean fileLocals = !locals.isEmpty() && (out.indexOf('{') >= 0 || out.indexOf('%') >= 0);
        boolean given = phs != null && phs.length > 0;
        if (fileLocals || given) {
            // {key:relative} is written in the plugin's language.
            Object previous = Formats.enterPlaceholderTexts(host.relativeTexts());
            try {
                if (fileLocals) {
                    out = RsText.applyLocals(out, this::local);
                }
                if (given) {
                    out = RsText.applyLocals(out, phs);
                }
            } finally {
                Formats.exitPlaceholderTexts(previous);
            }
        }
        if (out.indexOf('%') >= 0) {
            if (host.isPrimaryThread()) {
                out = host.papi(viewer, out);
            } else {
                host.trace(() -> "PlaceholderAPI skipped off the main thread for " + name + " -> '" + key + "'");
            }
        }
        return out;
    }

    /** A file placeholder's value; computed once per tick while placeholders are shared (menu refreshes). */
    private String local(String key) {
        Supplier<String> supplier = locals.get(key);
        if (supplier == null) {
            return null;
        }
        if (SharedPlaceholders.active()) {
            return SharedPlaceholders.value(supplier, key, () -> compute(key, supplier));
        }
        return compute(key, supplier);
    }

    private String compute(String key, Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Placeholder {" + key + "} of " + name + " failed", e);
            return null;
        }
    }

    /** Paths whose value is written but empty ({@code key: ~} or {@code key:}), which Bukkit drops. */
    static Set<String> nullKeys(String text) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(Integer.MAX_VALUE);
            options.setMaxAliasesForCollections(Integer.MAX_VALUE);
            Node root = new Yaml(options).compose(new StringReader(text));
            if (!(root instanceof MappingNode mapping)) {
                return Set.of();
            }
            Set<String> out = new HashSet<>();
            collectNulls(mapping, "", out);
            return out.isEmpty() ? Set.of() : Set.copyOf(out);
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    private static void collectNulls(MappingNode mapping, String prefix, Set<String> out) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) {
                continue;
            }
            String path = prefix + keyNode.getValue();
            Node value = tuple.getValueNode();
            if (value instanceof ScalarNode scalar && Tag.NULL.equals(scalar.getTag())) {
                out.add(path);
            } else if (value instanceof MappingNode child) {
                collectNulls(child, path + ".", out);
            }
        }
    }

    // ------------------------------------------------------------------ writing

    /**
     * Sets a value in memory; call {@link #save()} to write it. Call it on the main thread: the
     * getters read without a lock, so a value set while another thread reads the same file may
     * be seen half-applied by that thread (see the class description).
     */
    public void set(String key, Object value) {
        synchronized (memoryLock) {
            config.set(key, value);
            if (nullKeys.contains(key)) {
                Set<String> copy = new HashSet<>(nullKeys);
                copy.remove(key);
                nullKeys = Set.copyOf(copy);
            }
        }
    }

    /** Sets the comment lines above a key, in memory. */
    public void setComments(String key, List<String> comments) {
        synchronized (memoryLock) {
            config.setComments(key, comments);
        }
    }

    /** Sets the comment at the end of a key's line, in memory. */
    public void setInlineComments(String key, List<String> comments) {
        synchronized (memoryLock) {
            config.setInlineComments(key, comments);
        }
    }

    /** The whole file as it would be saved now. */
    String saveToString() {
        synchronized (memoryLock) {
            return config.saveToString();
        }
    }

    /**
     * Saves the file: the text is taken now, the write happens off the main thread and saves made
     * before it runs are merged. During shutdown the file is written immediately.
     */
    public void save() {
        String text = saveToString();
        synchronized (writeState) {
            pendingText = text;
            pendingSeq = ++nextSeq;
        }
        if (host.shuttingDown()) {
            flushPendingQuietly();
            return;
        }
        synchronized (writeState) {
            if (scheduled) {
                return;
            }
            scheduled = true;
        }
        try {
            host.async(this::drainAsync);
        } catch (RuntimeException notScheduled) {
            synchronized (writeState) {
                scheduled = false;
            }
            flushPendingQuietly();
        }
    }

    private void drainAsync() {
        synchronized (writeState) {
            scheduled = false;
        }
        ioLock.lock();
        try {
            writePending();
        } finally {
            ioLock.unlock();
        }
    }

    /**
     * Writes what is pending now, on this thread. Waits up to 10 seconds for a write already in
     * progress; never waits for the scheduled task itself. Meant for shutdown.
     */
    public void flush() {
        boolean locked = false;
        try {
            locked = ioLock.tryLock(FLUSH_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                host.logger().warning("A write to " + name + " has been in progress for over "
                        + FLUSH_WAIT_SECONDS + "s; writing the latest content anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            writePending();
        } finally {
            if (locked) {
                ioLock.unlock();
            }
        }
    }

    private void flushPendingQuietly() {
        synchronized (writeState) {
            if (pendingText == null) {
                return;
            }
        }
        flush();
    }

    /** True while a save is waiting to be written. */
    boolean hasPendingWrite() {
        synchronized (writeState) {
            return pendingText != null;
        }
    }

    /**
     * Takes the pending text (a short hold of {@code writeState}) and writes it. The commit runs
     * under its own lock, so {@link #save()} on the main thread never waits for the disk; the
     * sequence check there keeps an older text from replacing a newer one.
     */
    private void writePending() {
        String text;
        long seq;
        synchronized (writeState) {
            text = pendingText;
            seq = pendingSeq;
            pendingText = null;
        }
        if (text == null) {
            return;
        }
        Path target = file.toPath();
        try {
            Path temp = YamlText.writeTemp(target, text);
            synchronized (commitLock) {
                if (seq <= writtenSeq) {
                    Files.deleteIfExists(temp);
                    return;
                }
                YamlText.commit(temp, target, text);
                writtenSeq = seq;
            }
        } catch (IOException | RuntimeException e) {
            host.logger().warning("Could not save " + name + ": " + e);
        }
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline);
    }
}
