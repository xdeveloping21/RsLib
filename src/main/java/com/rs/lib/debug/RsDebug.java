package com.rs.lib.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.yml.RsYml;

/**
 * Debug output for one plugin that can be switched on and off without a restart.
 *
 * <p>State: a master switch (off by default), a level (DEBUG by default) and a set of
 * categories (lower case, trimmed). Levels go {@code OFF < INFO < DEBUG < TRACE}: {@link #info}
 * prints from INFO, {@link #log} from DEBUG and {@link #trace} only at TRACE; with the master
 * switch off nothing prints. An empty category set lets every category through; otherwise only
 * the listed ones. {@code log(message)} without a category ignores the filter;
 * {@code log(category, ...)} and {@code trace(category, ...)} respect it. Suppliers are only
 * called when the line will print.</p>
 *
 * <p>Lines go to the server's root logger as {@code [Plugin][DEBUG] [category] message}. The
 * state is read from the config ({@code debug.enabled}, {@code debug.level},
 * {@code debug.categories}) at creation and again on every reload of the config, so hand edits
 * apply on reload; every {@link #toggle} or {@link #setLevel} writes the three keys back and
 * saves. Without a config everything stays in memory.</p>
 */
public final class RsDebug {

    /** How much prints, from nothing to everything. */
    public enum Level { OFF, INFO, DEBUG, TRACE }

    static final String KEY_ENABLED = "debug.enabled";
    static final String KEY_LEVEL = "debug.level";
    static final String KEY_CATEGORIES = "debug.categories";

    private final String pluginName;
    private final RsYml config;
    private final Consumer<String> out;
    private final Object lock = new Object();

    private volatile boolean master;
    private volatile Level level;
    private volatile Set<String> categories;

    public RsDebug(JavaPlugin plugin, RsYml config) {
        this(plugin.getName(), config, line -> Bukkit.getLogger().info(line));
    }

    RsDebug(String pluginName, RsYml config, Consumer<String> out) {
        this.pluginName = Objects.requireNonNull(pluginName, "pluginName");
        this.config = config;
        this.out = Objects.requireNonNull(out, "out");
        if (config == null) {
            master = false;
            level = Level.DEBUG;
            categories = Set.of();
        } else {
            readConfig();
            config.onReload(this::readConfig);
        }
    }

    /** Takes the three keys from the config, replacing what is in memory. */
    private void readConfig() {
        boolean enabled = config.getBoolean(KEY_ENABLED, false);
        Level read = config.getEnum(KEY_LEVEL, Level.class, Level.DEBUG);
        Set<String> fromConfig = new TreeSet<>();
        for (String category : config.getStringList(KEY_CATEGORIES, List.of())) {
            String normalized = normalize(category);
            if (!normalized.isEmpty()) {
                fromConfig.add(normalized);
            }
        }
        synchronized (lock) {
            master = enabled;
            level = read == null ? Level.DEBUG : read;
            categories = Collections.unmodifiableSet(fromConfig);
        }
    }

    // ------------------------------------------------------------------ output

    public void info(String message) {
        if (active(Level.INFO)) {
            emit("INFO", null, message);
        }
    }

    public void info(Supplier<String> message) {
        if (active(Level.INFO)) {
            emit("INFO", null, evaluate(message));
        }
    }

    /** Prints at DEBUG; ignores the category filter. */
    public void log(String message) {
        if (active(Level.DEBUG)) {
            emit("DEBUG", null, message);
        }
    }

    public void log(Supplier<String> message) {
        if (active(Level.DEBUG)) {
            emit("DEBUG", null, evaluate(message));
        }
    }

    public void log(String category, Supplier<String> message) {
        if (active(Level.DEBUG) && passes(category)) {
            emit("DEBUG", normalize(category), evaluate(message));
        }
    }

    /** Prints only at TRACE; ignores the category filter. */
    public void trace(Supplier<String> message) {
        if (active(Level.TRACE)) {
            emit("TRACE", null, evaluate(message));
        }
    }

    public void trace(String category, Supplier<String> message) {
        if (active(Level.TRACE) && passes(category)) {
            emit("TRACE", normalize(category), evaluate(message));
        }
    }

    // ------------------------------------------------------------------ state

    /** True when {@link #log(String)} prints: the master switch is on and the level is DEBUG or TRACE. */
    public boolean enabled() {
        return active(Level.DEBUG);
    }

    /** True when {@code log(category, ...)} prints. */
    public boolean enabled(String category) {
        return enabled() && passes(category);
    }

    /** True when {@link #trace(Supplier)} prints. */
    public boolean tracing() {
        return active(Level.TRACE);
    }

    /**
     * Flips the master switch, saves it and answers its new state. That is the switch alone:
     * with the level at INFO or OFF it can be on while {@link #enabled()} stays false.
     */
    public boolean toggle() {
        synchronized (lock) {
            master = !master;
            persist();
            return master;
        }
    }

    /**
     * Adds the category to the filter, or removes it when present, and saves.
     *
     * @return true when the category is now in the filter
     */
    public boolean toggle(String category) {
        String normalized = normalize(category);
        boolean now;
        synchronized (lock) {
            Set<String> next = new TreeSet<>(categories);
            now = next.add(normalized);
            if (!now) {
                next.remove(normalized);
            }
            categories = Collections.unmodifiableSet(next);
            persist();
        }
        return now;
    }

    public void setLevel(Level level) {
        synchronized (lock) {
            this.level = Objects.requireNonNull(level, "level");
            persist();
        }
    }

    public Level level() {
        return level;
    }

    /** The categories in the filter, sorted; empty means every category. */
    public Set<String> categories() {
        return categories;
    }

    // ------------------------------------------------------------------ internals

    private boolean active(Level needed) {
        return master && level.ordinal() >= needed.ordinal();
    }

    private boolean passes(String category) {
        Set<String> filter = categories;
        return filter.isEmpty() || filter.contains(normalize(category));
    }

    private void emit(String tag, String category, String message) {
        StringBuilder line = new StringBuilder(64);
        line.append('[').append(pluginName).append("][").append(tag).append("] ");
        if (category != null && !category.isEmpty()) {
            line.append('[').append(category).append("] ");
        }
        out.accept(line.append(message).toString());
    }

    private static String evaluate(Supplier<String> message) {
        try {
            return String.valueOf(message.get());
        } catch (RuntimeException e) {
            return "(debug message failed: " + e + ")";
        }
    }

    private static String normalize(String category) {
        return category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
    }

    private void persist() {
        if (config == null) {
            return;
        }
        config.set(KEY_ENABLED, master);
        config.set(KEY_LEVEL, level.name());
        config.set(KEY_CATEGORIES, new ArrayList<>(categories));
        config.save();
    }
}
