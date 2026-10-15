package com.rs.lib.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The text of {@code /rslib integrations} and {@code /rslib iteminfo}. Kept apart from the
 * command so it can be checked without a server.
 */
public final class AdminInfo {

    /** Longest value shown in full; longer ones are cut with "...". */
    static final int MAX_VALUE = 96;
    /** How deep nested containers are opened. */
    static final int MAX_DEPTH = 4;

    /** The types tried, in order, to read a value whose type the container does not say. */
    private static final List<Named> TYPES = List.of(
            new Named("byte", PersistentDataType.BYTE),
            new Named("short", PersistentDataType.SHORT),
            new Named("int", PersistentDataType.INTEGER),
            new Named("long", PersistentDataType.LONG),
            new Named("float", PersistentDataType.FLOAT),
            new Named("double", PersistentDataType.DOUBLE),
            new Named("string", PersistentDataType.STRING),
            new Named("byte[]", PersistentDataType.BYTE_ARRAY),
            new Named("int[]", PersistentDataType.INTEGER_ARRAY),
            new Named("long[]", PersistentDataType.LONG_ARRAY),
            new Named("container", PersistentDataType.TAG_CONTAINER));

    private record Named(String name, PersistentDataType<?, ?> type) {
    }

    /** One soft dependency of a plugin: what it hooks into and whether the hook is live. */
    public record Hook(String owner, String target, String version, boolean on) {
    }

    private AdminInfo() {
    }

    /** RsLib's own optional plugin: on (with its version), disabled, or not installed. */
    public static Hook ownHook(String owner, String target, Plugin found) {
        if (found == null) {
            return new Hook(owner, target, null, false);
        }
        return new Hook(owner, target, found.getPluginMeta().getVersion(), found.isEnabled());
    }

    /** One line per plugin, owners in the order first seen, RsLib first when present. */
    public static List<String> integrations(List<Hook> hooks) {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        for (Hook hook : hooks) {
            String state = hook.on() ? "on" : hook.version() == null ? "not installed" : "off";
            String version = hook.version() == null ? "" : " " + hook.version();
            byOwner.computeIfAbsent(hook.owner(), k -> new ArrayList<>()).add(hook.target() + version + " " + state);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Integrations:");
        byOwner.forEach((owner, list) -> lines.add(" " + owner + ": " + String.join(", ", list)));
        if (byOwner.isEmpty()) {
            lines.add(" none");
        }
        return lines;
    }

    /** The persistent data of an item: one line per key, nested containers indented. */
    public static List<String> itemInfo(String material, int amount, PersistentDataContainer data) {
        List<String> lines = new ArrayList<>();
        lines.add("Item: " + material + " x" + amount);
        if (data == null || data.getKeys().isEmpty()) {
            lines.add(" No persistent data.");
            return lines;
        }
        lines.add(" Persistent data:");
        dump(data, "  ", 1, lines);
        return lines;
    }

    private static void dump(PersistentDataContainer data, String indent, int depth, List<String> lines) {
        List<NamespacedKey> keys = new ArrayList<>(data.getKeys());
        keys.sort(Comparator.comparing(NamespacedKey::toString));
        for (NamespacedKey key : keys) {
            Named named = typeOf(data, key);
            if (named == null) {
                lines.add(indent + key + " = ? (unknown type)");
                continue;
            }
            Object value = data.get(key, named.type());
            if (value instanceof PersistentDataContainer inner) {
                if (depth >= MAX_DEPTH) {
                    lines.add(indent + key + " = {" + inner.getKeys().size() + " keys} (container)");
                } else {
                    lines.add(indent + key + " (container):");
                    dump(inner, indent + "  ", depth + 1, lines);
                }
                continue;
            }
            lines.add(indent + key + " = " + format(value) + " (" + named.name() + ")");
        }
    }

    private static Named typeOf(PersistentDataContainer data, NamespacedKey key) {
        for (Named named : TYPES) {
            try {
                if (data.has(key, named.type())) {
                    return named;
                }
            } catch (RuntimeException ignored) {
                // A type this server cannot check: try the next one.
            }
        }
        return null;
    }

    static String format(Object value) {
        String text;
        if (value instanceof String s) {
            text = "\"" + s + "\"";
        } else if (value instanceof byte[] bytes) {
            text = bytes.length + " bytes";
        } else if (value instanceof int[] ints) {
            text = Arrays.toString(ints);
        } else if (value instanceof long[] longs) {
            text = Arrays.toString(longs);
        } else {
            text = String.valueOf(value);
        }
        return text.length() > MAX_VALUE ? text.substring(0, MAX_VALUE) + "..." : text;
    }
}
