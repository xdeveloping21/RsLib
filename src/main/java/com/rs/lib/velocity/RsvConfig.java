package com.rs.lib.velocity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.rs.lib.yml.internal.OutlineMerge;
import com.rs.lib.yml.internal.YamlOutline;
import com.rs.lib.yml.internal.YamlText;

/**
 * A YAML config of a proxy plugin, read once and immutable; {@link Rsv#reloadConfig()} reads a
 * new one.
 *
 * <p>{@link #load} keeps the disk file up to date with the copy in the plugin jar the way the
 * Paper side does ("always merge"): a missing file is created from the jar copy as written, and
 * every key the jar copy has and the file lacks is copied in with its comments, at the place the
 * jar copy puts it and with the indentation the file uses. Nothing else in the file changes:
 * the owner's values, extra keys and comments stay, and a file that lacks nothing is never
 * written. Sections marked {@code # rs:extensible} are left alone and an empty {@code {}} is
 * respected. A file that is not valid YAML is left untouched and the jar values are used until it
 * is fixed. Nothing here throws: every problem is logged.</p>
 *
 * <p>Getters take a dotted path ({@code database.port}). A missing key, or a value of another
 * type, gives the default passed in. Safe to read from any thread.</p>
 */
public final class RsvConfig {

    private final Map<String, Object> root;

    private RsvConfig(Map<String, Object> root) {
        this.root = root;
    }

    /**
     * Loads {@code file}, creating it from {@code defaults} when it does not exist and adding
     * the keys of {@code defaults} it lacks. {@code defaults} may be null (the file is then only
     * read, and never created) and is closed here. Never throws.
     */
    public static RsvConfig load(Path file, InputStream defaults, Logger logger) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(logger, "logger");
        String jarText = readDefaults(defaults, file, logger);
        Map<String, Object> jarValues = Map.of();
        if (jarText != null) {
            try {
                jarValues = parse(jarText);
            } catch (RuntimeException e) {
                logger.error("The default {} inside the plugin jar is not valid YAML; it is ignored: {}",
                        file.getFileName(), firstLine(e));
                jarText = null;
            }
        }
        String raw;
        try {
            if (!Files.exists(file)) {
                if (jarText != null) {
                    YamlText.write(file, YamlText.join(YamlText.lines(jarText)));
                }
                return new RsvConfig(jarValues);
            }
            raw = YamlText.read(file);
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read or create {}; using the default values", file, e);
            return new RsvConfig(jarValues);
        }
        Map<String, Object> values;
        try {
            values = parse(raw);
        } catch (RuntimeException e) {
            logger.error("{} is not valid YAML; it was left as it is and the default values are used until it is"
                    + " fixed: {}", file, firstLine(e));
            return new RsvConfig(jarValues);
        }
        return jarText == null ? new RsvConfig(values) : merge(file, raw, values, jarText, logger);
    }

    /** Adds the keys of the jar copy the file lacks; the file is written only when one is added. */
    private static RsvConfig merge(Path file, String raw, Map<String, Object> values, String jarText,
                                   Logger logger) {
        List<String> diskLines = YamlText.lines(raw);
        List<String> jarLines = YamlText.lines(jarText);
        List<String> merged;
        Map<String, Object> mergedValues;
        try {
            merged = OutlineMerge.merge(jarLines, YamlOutline.parse(jarLines), diskLines,
                    YamlOutline.parse(diskLines), null);
            if (merged.equals(diskLines)) {
                return new RsvConfig(values);
            }
            mergedValues = parse(YamlText.join(merged));
        } catch (RuntimeException e) {
            logger.warn("Could not add the missing keys to {}; it is used as it is: {}", file, firstLine(e));
            return new RsvConfig(values);
        }
        if (!keeps(values, mergedValues)) {
            logger.warn("Could not add the missing keys to {} without changing what it holds; it is used as it is",
                    file);
            return new RsvConfig(values);
        }
        try {
            YamlText.write(file, YamlText.join(merged));
        } catch (IOException | RuntimeException e) {
            logger.error("Could not write the missing keys to {}; they are used from the jar until then", file, e);
        }
        return new RsvConfig(mergedValues);
    }

    private static String readDefaults(InputStream defaults, Path file, Logger logger) {
        if (defaults == null) {
            return null;
        }
        try {
            return YamlText.read(defaults);
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read the default {} from the plugin jar", file.getFileName(), e);
            return null;
        }
    }

    /** A config holding {@code values} as they are (keys turned into text). */
    static RsvConfig of(Map<?, ?> values) {
        return new RsvConfig(values == null ? Map.of() : normalizeMap(values));
    }

    // ------------------------------------------------------------------ getters

    /** True when {@code path} names a key, whatever its value (even an empty one). */
    public boolean contains(String path) {
        Map<String, Object> parent = parentOf(path);
        return parent != null && parent.containsKey(lastSegment(path));
    }

    /** Text, number or boolean as text; anything else gives {@code def}. */
    public String getString(String path, String def) {
        Object value = get(path);
        return value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character ? value.toString() : def;
    }

    /** A number, cut to an int; anything else gives {@code def}. */
    public int getInt(String path, int def) {
        return get(path) instanceof Number number ? number.intValue() : def;
    }

    /** A number, cut to a long; anything else gives {@code def}. */
    public long getLong(String path, long def) {
        return get(path) instanceof Number number ? number.longValue() : def;
    }

    /** A number; anything else gives {@code def}. */
    public double getDouble(String path, double def) {
        return get(path) instanceof Number number ? number.doubleValue() : def;
    }

    /** {@code true} or {@code false}; anything else gives {@code def}. */
    public boolean getBoolean(String path, boolean def) {
        return get(path) instanceof Boolean flag ? flag : def;
    }

    /**
     * A list as text: each text, number or boolean element in order, other elements skipped. A
     * missing key or a value that is not a list gives an empty list. The list is a new mutable
     * copy.
     */
    public List<String> getStringList(String path) {
        if (!(get(path) instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof String || element instanceof Number || element instanceof Boolean
                    || element instanceof Character) {
                out.add(element.toString());
            }
        }
        return out;
    }

    /**
     * The raw contents of the section at {@code path}, keys in file order: nested sections are
     * maps, lists are lists and keys are text. Read-only; a missing key or a value that is not a
     * section gives an empty map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String path) {
        return get(path) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** The top-level keys in file order. Read-only. */
    public Set<String> keys() {
        return root.keySet();
    }

    /** The value at {@code path}, or null. */
    Object get(String path) {
        Map<String, Object> parent = parentOf(path);
        return parent == null ? null : parent.get(lastSegment(path));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parentOf(String path) {
        if (path == null) {
            return null;
        }
        Map<String, Object> section = root;
        int from = 0;
        int dot;
        while ((dot = path.indexOf('.', from)) >= 0) {
            if (!(section.get(path.substring(from, dot)) instanceof Map<?, ?> child)) {
                return null;
            }
            section = (Map<String, Object>) child;
            from = dot + 1;
        }
        return section;
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    // ------------------------------------------------------------------ parsing

    /** Parses a YAML text whose top level is a map (or empty); throws when it is not. */
    static Map<String, Object> parse(String text) {
        LoaderOptions options = new LoaderOptions();
        Object loaded = new Yaml(new SafeConstructor(options)).load(text);
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("the top level is not a set of keys");
        }
        return normalizeMap(map);
    }

    /** A read-only copy with text keys, in the same order, all the way down. */
    private static Map<String, Object> normalizeMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>(Math.max(4, map.size() * 2));
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                out.add(normalize(element));
            }
            return Collections.unmodifiableList(out);
        }
        return value;
    }

    /** True when every value of {@code before} is still in {@code after}, unchanged. */
    static boolean keeps(Map<String, Object> before, Map<String, Object> after) {
        for (Map.Entry<String, Object> entry : before.entrySet()) {
            String key = entry.getKey();
            if (!after.containsKey(key)) {
                return false;
            }
            Object was = entry.getValue();
            Object now = after.get(key);
            if (was instanceof Map<?, ?> && now instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                boolean kept = keeps((Map<String, Object>) was, (Map<String, Object>) now);
                if (!kept) {
                    return false;
                }
            } else if (!Objects.deepEquals(was, now)) {
                return false;
            }
        }
        return true;
    }

    private static String firstLine(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
