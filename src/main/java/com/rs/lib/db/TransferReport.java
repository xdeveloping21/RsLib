package com.rs.lib.db;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What an export or import of a plugin's database did, from {@link RsDb#exportTo} or
 * {@link RsDb#importFrom}. A transfer that could not run, or was refused, fails its future
 * instead (with {@link TransferRefused} when it was refused before or while writing).
 *
 * @param file      the file written or read
 * @param tables    rows per table, in the file's order (the migration history counts only this
 *                  plugin's records)
 * @param migration the plugin's migration version recorded in the file (0 without migrations)
 * @param millis    wall time, database and file included
 */
public record TransferReport(Path file, Map<String, Long> tables, int migration, long millis) {

    /** The ending of the files an export writes. */
    public static final String EXTENSION = ".rsdump.gz";

    /** Told after each table, on the database thread (hop to the main thread before using Bukkit). */
    @FunctionalInterface
    public interface Progress {

        /** Nothing to tell. */
        Progress NONE = (table, rows) -> {
        };

        void table(String table, long rows);
    }

    public TransferReport {
        Objects.requireNonNull(file, "file");
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    /** Rows across every table. */
    public long rows() {
        long total = 0;
        for (long count : tables.values()) {
            total += count;
        }
        return total;
    }
}
