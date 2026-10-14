package com.rs.lib.yml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
 * leading {@code /} dropped). The <b>first</b> mount of a path fixes its mode; mounting it
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

    /** {@code \} to {@code /}, surrounding spaces and leading {@code /} removed. */
    static String normalize(String path) {
        Objects.requireNonNull(path, "path");
        String out = path.trim().replace('\\', '/');
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Empty file path");
        }
        return out;
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
            Runnable prepare = prepareStep(path, file, mode);
            yml.prepare(prepare);
            prepare.run();
            yml.read();
            files.put(path, new Mounted(yml, mode));
            return yml;
        }
    }

    private Runnable prepareStep(String path, File file, Mode mode) {
        Logger logger = host.logger();
        boolean main = path.equals(configPath);
        return switch (mode) {
            case MANAGED -> () -> YamlUpdater.updateResource(logger, resources, path, file, false, main);
            case MANAGED_PRUNING -> () -> YamlUpdater.updateResource(logger, resources, path, file, true, main);
            case SEED_ONLY -> () -> seedIfMissing(path, file);
            case PLAIN -> () -> { };
        };
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
