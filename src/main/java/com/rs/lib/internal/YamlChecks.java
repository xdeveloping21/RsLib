package com.rs.lib.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Load-time warnings for values Bukkit's getters silently replace.
 *
 * <p>{@code getStringList} answers an empty list for {@code click-actions: "[close]"} and
 * drops an element written {@code - [close]} without quotes (YAML reads it as a nested
 * list); {@code getInt} answers the default for {@code rows: six}; a section written as a
 * list reads as absent. The button then does nothing and nothing says why. This only
 * reports: the value each parser uses is unchanged.</p>
 */
public final class YamlChecks {

    private YamlChecks() {
    }

    /**
     * Reports, for the keys directly under {@code sec}: a key ending in {@code -actions}, or
     * named in {@code lists}, that is not a list or holds an element that is not text; a key
     * in {@code ints} that is not a whole number; a key in {@code sections} that is not a
     * section. Keys ending in {@code -requirements} are left to the requirement reader
     * ({@code ActionEngine.requirements}), which reports the same mistakes and makes the
     * list always fail, so they are not reported twice.
     */
    public static void check(ConfigurationSection sec, Consumer<String> warn, Set<String> lists,
                             Set<String> ints, Set<String> sections) {
        for (String key : sec.getKeys(false)) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.endsWith("-requirements")) {
                continue;
            }
            if (lower.endsWith("-actions") || lists.contains(lower)) {
                checkList(sec, key, warn);
            } else if (ints.contains(lower)) {
                if (!(sec.get(key) instanceof Number)) {
                    warn.accept("'" + key + "' must be a whole number, got '" + sec.get(key)
                            + "'; using the default");
                }
            } else if (sections.contains(lower) && !sec.isConfigurationSection(key)) {
                warn.accept("'" + key + "' must be a section (keys below it), got '" + sec.get(key)
                        + "'; ignored");
            }
        }
    }

    private static void checkList(ConfigurationSection sec, String key, Consumer<String> warn) {
        Object value = sec.get(key);
        if (!(value instanceof List<?> list)) {
            warn.accept("'" + key + "' must be a list (one '- ' line per entry), got '" + value
                    + "'; ignored");
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            if (element instanceof List<?> || element instanceof Map<?, ?>
                    || element instanceof ConfigurationSection) {
                warn.accept("'" + key + "' entry " + (i + 1) + " is not text (" + element
                        + "); write it in quotes, for example - '[close]'; skipped");
            }
        }
    }
}
