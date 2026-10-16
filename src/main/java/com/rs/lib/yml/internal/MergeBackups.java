package com.rs.lib.yml.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Backups made before the config updater rewrites a file, and the name rule folder listers use
 * to skip them.
 *
 * <p>A backup of {@code config.yml} is {@code old-config-20261013-142530.yml} in the same folder;
 * only the three newest of that exact name are kept, so {@code config.yml} never rotates away
 * the backups of {@code config-extra.yml}. Anything that lists a folder of YAML files (menus,
 * kits, ...) must ignore names for which {@link #isMergeBackup(String)} is true.</p>
 *
 * <p>A file that is not valid YAML when it is first mounted is moved to
 * {@code <name>.backup-N} with the first free N; those are never rotated.</p>
 */
public final class MergeBackups {

    /** Date format of the stamp in a backup name. */
    public static final String STAMP = "yyyyMMdd-HHmmss";

    /** How many backups of one file are kept. */
    public static final int KEEP = 3;

    private static final String CORRUPT_SUFFIX = ".backup-";
    private static final int MAX_DIGITS = 9;
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
        return backupPattern(fileName).matcher(candidate).matches();
    }

    private static Pattern backupPattern(String fileName) {
        return Pattern.compile("old-" + Pattern.quote(baseName(fileName)) + "-\\d{8}-\\d{6}\\.yml");
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
        Pattern mineOnly = backupPattern(file.getFileName().toString());
        Path dir = file.toAbsolutePath().getParent();
        String[] all = dir.toFile().list();
        if (all == null) {
            return;
        }
        List<String> mine = new ArrayList<>();
        for (String candidate : all) {
            if (mineOnly.matcher(candidate).matches()) {
                mine.add(candidate);
            }
        }
        // The stamp sorts chronologically as text.
        mine.sort(Collections.reverseOrder());
        for (int i = KEEP; i < mine.size(); i++) {
            Files.deleteIfExists(dir.resolve(mine.get(i)));
        }
    }

    /**
     * Moves an unreadable file aside to {@code <name>.backup-N}, N being the first number (from
     * 1) no file of that name uses yet. Earlier backups are never deleted: each one may hold the
     * only copy of the owner's settings.
     *
     * @return where it was moved
     */
    public static Path moveCorrupt(Path file) throws IOException {
        String name = file.getFileName().toString();
        Set<Integer> taken = corruptNumbers(file);
        int next = 1;
        while (taken.contains(next)) {
            next++;
        }
        return Files.move(file, file.resolveSibling(name + CORRUPT_SUFFIX + next));
    }

    /** The N of every {@code <name>.backup-N} next to {@code file}. */
    private static Set<Integer> corruptNumbers(Path file) {
        String prefix = file.getFileName().toString() + CORRUPT_SUFFIX;
        String[] all = file.toAbsolutePath().getParent().toFile().list();
        Set<Integer> out = new HashSet<>();
        if (all == null) {
            return out;
        }
        for (String candidate : all) {
            if (candidate.startsWith(prefix) && candidate.length() > prefix.length()
                    && candidate.length() - prefix.length() <= MAX_DIGITS) {
                String digits = candidate.substring(prefix.length());
                if (digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
                    out.add(Integer.parseInt(digits));
                }
            }
        }
        return out;
    }
}
