package com.rs.lib.update.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * File work of the self-update: reading a jar's {@code plugin.yml}, finding the installed RsLib
 * jar, hashing, swapping the jar and cleaning the staging folder. No Bukkit server is needed.
 */
public final class RsLibJars {

    static final String NAME = "RsLib";
    static final String MAIN = "com.rs.lib.RsLibPlugin";
    static final String PART_SUFFIX = ".jar.part";
    private static final long MAX_PLUGIN_YML = 1L << 20;

    private RsLibJars() {
    }

    /** How files are moved and deleted; tests replace it to imitate a locked file. */
    public interface FileOps {

        /** Moves {@code from} to {@code to}, replacing it. */
        void move(Path from, Path to) throws IOException;

        void delete(Path file) throws IOException;
    }

    /** The real file system: an atomic rename when the system offers one. */
    public static final FileOps DISK = new FileOps() {
        @Override
        public void move(Path from, Path to) throws IOException {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public void delete(Path file) throws IOException {
            Files.delete(file);
        }
    };

    /**
     * Where the new jar ended up.
     *
     * @param jar      the new jar
     * @param deferred true when it waits in the update folder for the next start
     */
    public record Installed(Path jar, boolean deferred) {
    }

    // ------------------------------------------------------------------ plugin.yml

    /** The {@code plugin.yml} of a jar, or null when the jar has none or it is not valid YAML. */
    static YamlConfiguration pluginYml(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("plugin.yml");
            if (entry == null) {
                return null;
            }
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readNBytes((int) MAX_PLUGIN_YML + 1);
            }
            if (bytes.length > MAX_PLUGIN_YML) {
                return null;
            }
            YamlConfiguration yml = new YamlConfiguration();
            yml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
            return yml;
        } catch (InvalidConfigurationException e) {
            return null;
        }
    }

    /** True when {@code jar} is a readable jar whose plugin.yml names RsLib. */
    static boolean isRsLib(Path jar) {
        try {
            YamlConfiguration yml = pluginYml(jar);
            return yml != null && NAME.equals(yml.getString("name"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Checks that {@code jar} is RsLib {@code version}: name, main class and version must match exactly. */
    static void verify(Path jar, String version) throws UpdateRefused {
        YamlConfiguration yml;
        try {
            yml = pluginYml(jar);
        } catch (IOException | RuntimeException e) {
            throw new UpdateRefused("the downloaded file is not a readable jar");
        }
        if (yml == null) {
            throw new UpdateRefused("the downloaded jar has no valid plugin.yml");
        }
        if (!NAME.equals(yml.getString("name"))) {
            throw new UpdateRefused("the downloaded jar is not RsLib (name '" + yml.getString("name") + "')");
        }
        if (!MAIN.equals(yml.getString("main"))) {
            throw new UpdateRefused("the downloaded jar has an unexpected main class '" + yml.getString("main") + "'");
        }
        if (!version.equals(yml.getString("version"))) {
            throw new UpdateRefused("the downloaded jar is version '" + yml.getString("version")
                    + "', not " + version);
        }
    }

    // ------------------------------------------------------------------ the installed jar

    /**
     * The RsLib jar in {@code pluginsDir} that the server loaded, never a remapped copy
     * (such as one under {@code plugins/.paper-remapped/}).
     *
     * <ol>
     *   <li>{@code codeSource} itself, when it is a file directly in {@code pluginsDir};</li>
     *   <li>else a file of the same name in {@code pluginsDir} that is RsLib;</li>
     *   <li>else the only RsLib jar among {@code pluginsDir/*.jar}.</li>
     * </ol>
     *
     * @param codeSource where the classes were loaded from, or null when unknown
     */
    static Path resolveInstalled(Path pluginsDir, Path codeSource) throws UpdateRefused {
        Path plugins = pluginsDir.toAbsolutePath().normalize();
        if (codeSource != null) {
            Path source = codeSource.toAbsolutePath().normalize();
            if (plugins.equals(source.getParent()) && Files.isRegularFile(source)) {
                return source;
            }
            Path sameName = plugins.resolve(source.getFileName().toString());
            if (Files.isRegularFile(sameName) && isRsLib(sameName)) {
                return sameName;
            }
        }
        List<Path> found;
        try {
            found = rsLibJars(plugins);
        } catch (IOException e) {
            throw new UpdateRefused("could not list " + plugins + ": " + e.getMessage());
        }
        if (found.size() != 1) {
            throw new UpdateRefused(found.isEmpty() ? "the installed RsLib jar was not found in " + plugins
                    : severalJars(found));
        }
        return found.get(0);
    }

    /** Every jar directly in {@code pluginsDir} whose plugin.yml names RsLib, sorted by file name. */
    static List<Path> rsLibJars(Path pluginsDir) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path jar : jars) {
                if (Files.isRegularFile(jar) && isRsLib(jar)) {
                    found.add(jar);
                }
            }
        }
        found.sort(Comparator.comparing(jar -> jar.getFileName().toString()));
        return found;
    }

    /**
     * Why the self-updater stays away from {@code plugins/}: the server loads one of several
     * RsLib jars at random, so replacing any of them could leave the wrong one in place.
     */
    static String severalJars(List<Path> jars) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < jars.size(); i++) {
            if (i > 0) {
                names.append(i == jars.size() - 1 ? " and " : ", ");
            }
            names.append(jars.get(i).getFileName());
        }
        return "plugins/ holds more than one RsLib jar (" + names + "); the self-updater will not touch any of them"
                + " until only one is left.";
    }

    // ------------------------------------------------------------------ hashing

    /** The SHA-256 of a file, lower-case hex. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from the JDK", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ------------------------------------------------------------------ installing

    /**
     * Puts {@code staged} (RsLib {@code newVersion}) in place of {@code installed}.
     *
     * <p>The new jar is called {@code RsLib-<new>.jar} when the current name holds the old
     * version, else it keeps the current name. With a different name it is moved in first and
     * the old jar deleted after; if the old jar cannot be deleted (a lock on Windows) the move is
     * undone. When the file system refuses any of it, the jar goes to {@code updateFolder} under
     * the current name, where the server picks it up on the next start. Classes are never
     * swapped while running.</p>
     *
     * @throws IOException when not even the update folder can take the jar
     */
    static Installed install(Path staged, Path installed, String oldVersion, String newVersion,
                             Path updateFolder, FileOps ops) throws IOException {
        String currentName = installed.getFileName().toString();
        String targetName = !oldVersion.isEmpty() && currentName.contains(oldVersion)
                ? NAME + "-" + newVersion + ".jar" : currentName;
        Path target = installed.resolveSibling(targetName);
        if (target.equals(installed)) {
            try {
                ops.move(staged, installed);
                return new Installed(installed, false);
            } catch (IOException locked) {
                return defer(staged, installed, updateFolder, ops);
            }
        }
        try {
            ops.move(staged, target);
        } catch (IOException refused) {
            return defer(staged, installed, updateFolder, ops);
        }
        try {
            ops.delete(installed);
            return new Installed(target, false);
        } catch (IOException locked) {
            // Two RsLib jars must never stay side by side: take the new one back out.
            try {
                ops.move(target, staged);
            } catch (IOException undoFailed) {
                ops.delete(target);
                throw new IOException("the old jar is locked and the new one could not be staged again");
            }
            return defer(staged, installed, updateFolder, ops);
        }
    }

    private static Installed defer(Path staged, Path installed, Path updateFolder, FileOps ops) throws IOException {
        Files.createDirectories(updateFolder);
        Path target = updateFolder.resolve(installed.getFileName().toString());
        ops.move(staged, target);
        return new Installed(target, true);
    }

    /** Deletes {@code *.jar.part} leftovers in {@code staging}, then the folder when it is empty. */
    static void cleanStaging(Path staging) {
        if (!Files.isDirectory(staging)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(staging)) {
            for (Path file : files) {
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PART_SUFFIX)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException ignored) {
            // A locked leftover is retried after the next pass.
        }
        try {
            boolean empty;
            try (DirectoryStream<Path> rest = Files.newDirectoryStream(staging)) {
                empty = !rest.iterator().hasNext();
            }
            if (empty) {
                Files.deleteIfExists(staging);
            }
        } catch (IOException ignored) {
            // Not empty or locked: left for the next pass.
        }
    }
}
