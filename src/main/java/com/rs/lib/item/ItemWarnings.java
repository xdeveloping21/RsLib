package com.rs.lib.item;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.rs.lib.Rs;
import com.rs.lib.RsLib;
import com.rs.lib.yml.RsYml;

/**
 * Item warnings: each one names the file and the path of the item that caused it and is
 * printed once until {@link #forget()} (a reload), so a menu rendered every second does not
 * flood the log and an error still there after a reload is reported again.
 */
final class ItemWarnings {

    private static final Logger RSLIB = Logger.getLogger("RsLib");
    private static final int MAX_REMEMBERED = 4096;
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private ItemWarnings() {
    }

    /** A sink that prefixes {@code [source]} and logs to {@code logger} (RsLib's when null) once per text. */
    static Consumer<String> to(Logger logger, String source) {
        Logger target = logger == null ? RSLIB : logger;
        String prefix = source == null || source.isEmpty() ? "" : "[" + source + "] ";
        return message -> warn(target, prefix + message);
    }

    /** A sink for an item read from {@code yml} at {@code path}, logged by the plugin that owns the file. */
    static Consumer<String> of(RsYml yml, String path) {
        return to(loggerFor(yml.file()), source(yml, path));
    }

    /** {@code <file> -> <path>}, or the file alone for its root. */
    static String source(RsYml yml, String path) {
        return path == null || path.isEmpty() ? yml.name() : yml.name() + " -> " + path;
    }

    static void warn(Logger logger, String message) {
        if (SEEN.size() >= MAX_REMEMBERED) {
            SEEN.clear();
        }
        if (SEEN.add(message)) {
            logger.warning(message);
        }
    }

    /** Lets every warning be printed again. */
    static void forget() {
        SEEN.clear();
    }

    /** The logger of the plugin whose folder holds {@code file}, or RsLib's. */
    private static Logger loggerFor(File file) {
        if (file == null) {
            return RSLIB;
        }
        Path path = file.toPath().toAbsolutePath().normalize();
        try {
            for (Rs rs : RsLib.contexts()) {
                Path folder = rs.plugin().getDataFolder().toPath().toAbsolutePath().normalize();
                if (path.startsWith(folder)) {
                    return rs.plugin().getLogger();
                }
            }
        } catch (RuntimeException | LinkageError noServer) {
            return RSLIB;
        }
        return RSLIB;
    }
}
