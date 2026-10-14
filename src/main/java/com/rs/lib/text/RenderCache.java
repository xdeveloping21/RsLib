package com.rs.lib.text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import net.kyori.adventure.text.Component;

/**
 * Bounded least-recently-used cache of rendered lines, shared by every plugin.
 *
 * <p>Menus with an update interval render the same lines every tick, so the full render of a
 * line is remembered: up to {@value #MAX_ENTRIES} lines, only lines of up to
 * {@value #MAX_LINE} characters. When full, the line used longest ago is dropped (never the
 * whole cache). Sharing is safe because rendering is a pure function of the line. The render
 * runs outside the lock; two threads may render the same new line once each.</p>
 */
final class RenderCache {

    static final int MAX_ENTRIES = 2048;
    static final int MAX_LINE = 256;

    private static final Map<String, Component> LINES = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private RenderCache() {
    }

    /** The cached render of {@code line}, rendering and remembering it when missing. */
    static Component get(String line, Function<String, Component> render) {
        if (line.length() > MAX_LINE) {
            return render.apply(line);
        }
        Component cached;
        synchronized (LINES) {
            cached = LINES.get(line);
        }
        if (cached != null) {
            return cached;
        }
        Component rendered = render.apply(line);
        synchronized (LINES) {
            LINES.put(line, rendered);
        }
        return rendered;
    }

    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    static int size() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    static boolean contains(String line) {
        synchronized (LINES) {
            return LINES.containsKey(line);
        }
    }
}
