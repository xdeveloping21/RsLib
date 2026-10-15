package com.rs.lib.yml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.rs.lib.Rs;

/**
 * The YAML files of one plugin, reached with {@code rs().yml()}.
 *
 * <p>Each file is known by its path relative to the plugin folder ({@code \} becomes {@code /},
 * leading {@code /} dropped, {@code .} and {@code ..} segments resolved, so {@code a/./b.yml}
 * and {@code a/b.yml} are the same file; a path that leaves the plugin folder is refused). The <b>first</b> mount of a path fixes its mode; mounting it
 * again with another mode returns the same file and warns once.</p>
 * <ul>
 *     <li>{@link #managed}: seeded from the jar when missing and merged with the jar copy on
 *     every mount and reload (new keys are added, nothing is removed).</li>
 *     <li>{@link #managedPruning}: like managed, and keys the jar copy no longer has are removed.
 *     The only mode that deletes.</li>
 *     <li>{@link #seedOnly}: seeded when missing, never merged nor touched afterwards.</li>
 *     <li>{@link #data} (files the plugin writes) and {@link #load} (any file to read): never
 *     seeded nor merged.</li>
 * </ul>
 * <p>The main config is mounted as managed when the manager is created; the obsolete top-level
 * {@code update-configs} key is removed from it unless the jar copy still declares it. All this
 * I/O is synchronous on purpose: it only runs on enable and on reload.</p>
 * <p>A managed file that is not valid YAML is moved to {@code <name>.backup-N} and seeded again
 * from the jar only when it is first mounted. On a reload it is left as it is, with a warning,
 * and the content read before stays in use until the owner fixes it.</p>
 * <p>Files mounted per player (or per anything that comes and goes) should be released with
 * {@link #unmount} when they are no longer needed, or the manager keeps them for good.</p>
 */
public final class YmlManager {

    enum Mode {
        MANAGED("managed"),
        MANAGED_PRUNING("managedPruning"),
        SEED_ONLY("seedOnly"),
        PLAIN("data/load");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private record Mounted(RsYml yml, Mode mode) {
    }

    private final YmlHost host;
    private final File dataFolder;
    private final Function<String, InputStream> resources;
    private final String configPath;
    private final Map<String, Mounted> files = new LinkedHashMap<>();
    private final Set<String> modeWarnings = new HashSet<>();
    private final RsYml config;

    public YmlManager(Rs rs, String configFile) {
        this(YmlHost.of(rs), rs.plugin().getDataFolder(), rs.plugin()::getResource, configFile);
    }

    YmlManager(YmlHost host, File dataFolder, Function<String, InputStream> resources, String configFile) {
        this.host = Objects.requireNonNull(host, "host");
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.configPath = normalize(configFile == null ? "config.yml" : configFile);
        this.config = mount(configPath, Mode.MANAGED);
    }

    /** The main config, merged with the jar copy. */
    public RsYml config() {
        return config;
    }

    public RsYml managed(String path) {
        return mount(normalize(path), Mode.MANAGED);
    }

    public RsYml managedPruning(String path) {
        return mount(normalize(path), Mode.MANAGED_PRUNING);
    }

    public RsYml seedOnly(String path) {
        return mount(normalize(path), Mode.SEED_ONLY);
    }

    public RsYml data(String path) {
        return mount(normalize(path), Mode.PLAIN);
    }

    public RsYml load(String path) {
        return mount(normalize(path), Mode.PLAIN);
    }

    /**
     * Writes what is pending for the file at {@code path} and forgets it: it is no longer
     * reloaded nor flushed by this manager, and mounting the path again reads it from disk.
     * Meant for per-player data files, unmounted when the player leaves. Does nothing when the
     * path is not mounted. The {@link RsYml} still works if kept, but its saves are then the
     * caller's to flush.
     *
     * @throws IllegalArgumentException for the main config, which stays mounted
     */
    public void unmount(String path) {
        String normalized = normalize(path);
        if (normalized.equals(configPath)) {
            throw new IllegalArgumentException("The main config " + configPath + " cannot be unmounted");
        }
        synchronized (files) {
            Mounted mounted = files.get(normalized);
            if (mounted == null) {
                return;
            }
            try {
                mounted.yml().flush();
            } finally {
                files.remove(normalized);
                modeWarnings.remove(normalized);
            }
        }
    }

    /**
     * Merges the managed files again, seeds seed-only files that went missing, reads every file
     * from disk and runs their reload hooks.
     */
    public void reloadAll() {
        for (RsYml yml : snapshot()) {
            try {
                yml.reload();
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not reload " + yml.name(), e);
            }
        }
    }

    /** Writes every pending save now (used on shutdown). */
    public void flushAll() {
        for (RsYml yml : snapshot()) {
            try {
                yml.flush();
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not write " + yml.name(), e);
            }
        }
    }

    private List<RsYml> snapshot() {
        List<RsYml> out = new ArrayList<>();
        synchronized (files) {
            for (Mounted mounted : files.values()) {
                out.add(mounted.yml());
            }
        }
        return out;
    }

    /**
     * {@code \} to {@code /}, surrounding spaces, empty and {@code .} segments removed and
     * {@code ..} resolved against the previous segment.
     *
     * @throws IllegalArgumentException when nothing is left or the path leaves the plugin folder
     */
    static String normalize(String path) {
        Objects.requireNonNull(path, "path");
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.trim().replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("The file path '" + path + "' points outside the plugin folder");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Empty file path");
        }
        return String.join("/", segments);
    }

    private RsYml mount(String path, Mode mode) {
        synchronized (files) {
            Mounted existing = files.get(path);
            if (existing != null) {
                if (existing.mode() != mode && modeWarnings.add(path)) {
                    host.logger().warning(path + " is already mounted as " + existing.mode().label
                            + "; mounting it as " + mode.label + " keeps " + existing.mode().label
                            + " (the first mount decides)");
                }
                return existing.yml();
            }
            File file = new File(dataFolder, path);
            RsYml yml = new RsYml(host, file, path);
            prepareStep(path, file, mode, null).run();
            yml.prepare(prepareStep(path, file, mode, yml));
            yml.read();
            files.put(path, new Mounted(yml, mode));
            return yml;
        }
    }

    /**
     * The seeding or merging for {@code mode}. {@code reloading} is null for the first mount,
     * otherwise the file being reloaded: then a managed file that is not valid YAML is kept as
     * it is (the updater warns, so the file's own parse warning is not repeated).
     */
    private Runnable prepareStep(String path, File file, Mode mode, RsYml reloading) {
        return switch (mode) {
            case MANAGED -> () -> merge(path, file, false, reloading);
            case MANAGED_PRUNING -> () -> merge(path, file, true, reloading);
            case SEED_ONLY -> () -> seedIfMissing(path, file);
            case PLAIN -> () -> { };
        };
    }

    private void merge(String path, File file, boolean prune, RsYml reloading) {
        Logger logger = host.logger();
        boolean main = path.equals(configPath);
        YamlUpdater.Outcome outcome = YamlUpdater.updateResource(logger, resources, path, file, prune, main,
                reloading != null);
        if (outcome == YamlUpdater.Outcome.KEPT_INVALID) {
            reloading.invalidReported();
        }
    }

    private void seedIfMissing(String path, File file) {
        if (file.exists()) {
            return;
        }
        List<String> lines = YamlUpdater.readResource(host.logger(), resources, path);
        if (lines == null) {
            return;
        }
        try {
            YamlUpdater.seed(file.toPath(), lines);
        } catch (IOException | RuntimeException e) {
            host.logger().log(Level.SEVERE, YamlUpdater.PREFIX + "Could not create " + file.getPath(), e);
        }
    }
}
