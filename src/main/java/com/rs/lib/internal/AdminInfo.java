package com.rs.lib.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The text of {@code /rslib plugins}, {@code /rslib integrations} and {@code /rslib iteminfo},
 * as RsLib's documentation shows them. Kept apart from the command so it can be
 * checked without a server.
 */
public final class AdminInfo {

    /** What {@code iteminfo} shows for a tag that is not a string. */
    static final String NON_STRING = "<non-string tag>";

    private static final Comparator<String> BY_NAME = String.CASE_INSENSITIVE_ORDER;

    /** A plugin built on RsLib. */
    public record Consumer(String name, String version) {
    }

    /** One soft dependency of a plugin: what it hooks into and whether the hook is live. */
    public record Hook(String owner, String target, boolean active) {
    }

    private AdminInfo() {
    }

    /** {@code Consumers hooked to RsLib (n):}, one line per plugin sorted by name. */
    public static List<String> plugins(List<Consumer> consumers) {
        if (consumers.isEmpty()) {
            return List.of("No consumers are hooked to RsLib.");
        }
        List<Consumer> sorted = new ArrayList<>(consumers);
        sorted.sort(Comparator.comparing(Consumer::name, BY_NAME));
        List<String> lines = new ArrayList<>();
        lines.add("Consumers hooked to RsLib (" + sorted.size() + "):");
        sorted.forEach(c -> lines.add("- " + c.name() + " v" + c.version()));
        return lines;
    }

    /** {@code Registered integrations (n):}, one line per hook sorted by plugin, then target. */
    public static List<String> integrations(List<Hook> hooks) {
        List<Hook> sorted = new ArrayList<>(hooks);
        sorted.sort(Comparator.comparing(Hook::owner, BY_NAME).thenComparing(Hook::target, BY_NAME));
        List<String> lines = new ArrayList<>();
        lines.add("Registered integrations (" + sorted.size() + "):");
        sorted.forEach(h -> lines.add("- " + h.owner() + " -> " + h.target() + ": "
                + (h.active() ? "active" : "inactive")));
        return lines;
    }

    /**
     * {@code PDC tags of <material> (n):}, one line per tag sorted by key; a tag that is not a
     * string shows as {@value #NON_STRING}.
     */
    public static List<String> itemInfo(String material, PersistentDataContainer data) {
        List<NamespacedKey> keys = data == null ? List.of() : new ArrayList<>(data.getKeys());
        List<NamespacedKey> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparing(k -> k.toString().toLowerCase(Locale.ROOT)));
        List<String> lines = new ArrayList<>();
        lines.add("PDC tags of " + material + " (" + sorted.size() + "):");
        for (NamespacedKey key : sorted) {
            String value = stringValue(data, key);
            lines.add("- " + key + " = " + (value == null ? NON_STRING : value));
        }
        return lines;
    }

    private static String stringValue(PersistentDataContainer data, NamespacedKey key) {
        try {
            return data.has(key, PersistentDataType.STRING) ? data.get(key, PersistentDataType.STRING) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
