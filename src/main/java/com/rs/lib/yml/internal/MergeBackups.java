package com.rs.lib.yml.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Backups made before the config updater rewrites a file, and the name rule folder listers use
 * to skip them.
 *
 * <p>A backup of {@code config.yml} is {@code old-config-20261013-142530.yml} in the same folder;
 * only the three newest of that exact name are kept, so {@code config.yml} never rotates away
 * the backups of {@code config-extra.yml}. Anything that lists a folder of YAML files (menus,
 * kits, ...) must ignore names for which {@link #isMergeBackup(String)} is true.</p>
 */
public final class MergeBackups {

    /** Date format of the stamp in a backup name. */
    public static final String STAMP = "yyyyMMdd-HHmmss";

    /** How many backups of one file are kept. */
    public static final int KEEP = 3;

    private static final Pattern ANY = Pattern.compile("old-.+-\\d{8}-\\d{6}\\.yml",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern(STAMP, Locale.ROOT);

    private MergeBackups() {
    }

    /** True for {@code old-<name>-<8 digits>-<6 digits>.yml}, ignoring case; {@code old-town.yml} is not one. */
    public static boolean isMergeBackup(String fileName) {
        return fileName != null && ANY.matcher(fileName).matches();
    }

    /** {@code config.yml} gives {@code config}; a name without extension stays as it is. */
    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    /** Backup name for {@code fileName} at {@code time}. */
    public static String backupName(String fileName, LocalDateTime time) {
        return "old-" + baseName(fileName) + "-" + FORMAT.format(time) + ".yml";
    }

    /** True when {@code candidate} is a backup of exactly {@code fileName} (case-sensitive). */
    public static boolean isBackupOf(String fileName, String candidate) {
        return Pattern.compile("old-" + Pattern.quote(baseName(fileName)) + "-\\d{8}-\\d{6}\\.yml")
                .matcher(candidate).matches();
    }

    /**
     * Copies {@code file} to its timestamped backup and deletes the older backups of the same
     * name beyond {@link #KEEP}.
     *
     * @return the backup written
     */
    public static Path backup(Path file) throws IOException {
        String name = file.getFileName().toString();
        Path backup = file.resolveSibling(backupName(name, LocalDateTime.now()));
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        rotate(file);
        return backup;
    }

    /** Deletes backups of {@code file} beyond the {@link #KEEP} newest. */
    public static void rotate(Path file) throws IOException {
        String name = file.getFileName().toString();
        File dir = file.toAbsolutePath().getParent().toFile();
        String[] all = dir.list();
        if (all == null) {
            return;
        }
        List<String> mine = new ArrayList<>();
        for (String candidate : all) {
            if (isBackupOf(name, candidate)) {
                mine.add(candidate);
            }
        }
        // The stamp sorts chronologically as text.
        mine.sort(Collections.reverseOrder());
        for (int i = KEEP; i < mine.size(); i++) {
            Files.deleteIfExists(dir.toPath().resolve(mine.get(i)));
        }
    }

    /**
     * Moves an unreadable file aside to {@code <name>.backup-N}, using the first free N from 1.
     *
     * @return where it was moved
     */
    public static Path moveCorrupt(Path file) throws IOException {
        String name = file.getFileName().toString();
        for (int n = 1; ; n++) {
            Path target = file.resolveSibling(name + ".backup-" + n);
            if (!Files.exists(target)) {
                return Files.move(file, target);
            }
        }
    }
}
