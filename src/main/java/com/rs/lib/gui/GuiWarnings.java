package com.rs.lib.gui;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Menu warnings printed once: a broken button rendered every second, or clicked a hundred
 * times, says so once. Each manager forgets what it printed when it loads its menus again, so
 * a mistake still there after a reload is reported again.
 */
final class GuiWarnings {

    private static final int MAX_REMEMBERED = 4096;
    private static final Set<String> GLOBAL = ConcurrentHashMap.newKeySet();

    private final Logger logger;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    GuiWarnings(Logger logger) {
        this.logger = logger;
    }

    /** Logs {@code message} unless it was logged since the last {@link #forget()}. */
    void warn(String message) {
        if (seen.size() >= MAX_REMEMBERED) {
            seen.clear();
        }
        if (seen.add(message)) {
            logger.warning(message);
        }
    }

    /** {@code [file] path: message}, once. */
    void warn(String file, String path, String message) {
        warn("[" + file + "] " + (path == null || path.isEmpty() ? "" : path + ": ") + message);
    }

    void forget() {
        seen.clear();
    }

    /** {@link #forget()} for the files whose path starts with {@code filePrefix} only. */
    void forget(String filePrefix) {
        seen.removeIf(message -> message.startsWith("[" + filePrefix));
    }

    /** Once per server run, for problems that do not belong to one plugin's files. */
    static void once(Logger logger, String message) {
        if (GLOBAL.size() >= MAX_REMEMBERED) {
            GLOBAL.clear();
        }
        if (GLOBAL.add(message)) {
            logger.warning(message);
        }
    }
}
