package com.rs.lib.gui;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.rs.lib.yml.internal.MergeBackups;

/** Finding menu files: the {@code guis/*.yml} a plugin's jar ships and the ones on disk. */
final class GuiFiles {

    static final String EXTENSION = ".yml";

    private GuiFiles() {
    }

    /**
     * The names (without {@code .yml}) of the files directly under {@code folder/} in the jar (or
     * classes folder) {@code mainClass} was loaded from, sorted; nested folders are ignored.
     */
    static List<String> shipped(Class<?> mainClass, String folder, Logger logger) {
        Path source;
        try {
            CodeSource code = mainClass.getProtectionDomain().getCodeSource();
            if (code == null || code.getLocation() == null) {
                return List.of();
            }
            source = Path.of(code.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException unreadable) {
            logger.warning("Could not find the plugin jar to seed " + folder + "/: " + unreadable);
            return List.of();
        }
        return shipped(source, folder, logger);
    }

    /** {@link #shipped(Class, String, Logger)} for the jar (or classes folder) at {@code source}. */
    static List<String> shipped(Path source, String folder, Logger logger) {
        List<String> names = new ArrayList<>();
        String prefix = folder + "/";
        if (Files.isDirectory(source)) {
            return onDisk(source.resolve(folder).toFile());
        }
        try (ZipFile jar = new ZipFile(source.toFile())) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0) {
                    String file = name.substring(prefix.length());
                    if (isMenuFile(file)) {
                        names.add(stripExtension(file));
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            logger.warning("Could not read the plugin jar to seed " + folder + "/: " + unreadable);
            return List.of();
        }
        names.sort(null);
        return names;
    }

    /**
     * The menu names (without {@code .yml}) in {@code folder}, in alphabetical order: files only,
     * the backups the config updater leaves ({@code old-<name>-<date>.yml}) left out.
     */
    static List<String> onDisk(File folder) {
        if (folder == null || !folder.isDirectory()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder.toPath())) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(GuiFiles::isMenuFile)
                    .map(GuiFiles::stripExtension)
                    .forEach(names::add);
        } catch (IOException unreadable) {
            return List.of();
        }
        names.sort(null);
        return names;
    }

    /** A {@code .yml} name that is not an updater backup. */
    static boolean isMenuFile(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION) && fileName.length() > EXTENSION.length()
                && !MergeBackups.isMergeBackup(fileName);
    }

    static String stripExtension(String fileName) {
        return fileName.substring(0, fileName.length() - EXTENSION.length());
    }
}
