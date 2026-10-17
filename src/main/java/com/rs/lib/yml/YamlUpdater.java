package com.rs.lib.yml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.yml.internal.MergeBackups;
import com.rs.lib.yml.internal.OutlineMerge;
import com.rs.lib.yml.internal.YamlOutline;
import com.rs.lib.yml.internal.YamlOutline.Node;
import com.rs.lib.yml.internal.YamlText;

/**
 * Keeps a YAML file on disk up to date with the copy inside the plugin jar ("always merge").
 *
 * <p>There is no version key: every time, the jar resource is compared structurally with the
 * disk file, line by line, and every key the resource has and the disk lacks is copied with its
 * whole block (its comments, value and children, as written in the resource). The server
 * owner's values, extra keys and comments are never rewritten.</p>
 *
 * <h2>Where a new key goes</h2>
 * <p>Right after the block of the nearest earlier sibling that exists on both sides; if there is
 * none, right before the block of the nearest later sibling that exists on both sides; if there
 * is none, at the end of the parent section (after its last child, or at the end of the parent
 * block, or at the end of the file for top-level keys). When a key exists on both sides the merge
 * recurses into it, as long as both sides hold a section there. The copied block is re-indented
 * to the indentation the disk file uses in that section (an RsLib addition, so a disk file
 * indented with four spaces stays valid). Insertions are applied bottom-up.</p>
 *
 * <h2>Sections owned by the server owner</h2>
 * <p>A comment line {@code # rs:extensible} (case-insensitive) right above a key means that
 * what is below that key is the owner's data: nothing is ever inserted or pruned there. The key
 * itself is still part of the schema: if the whole section is deleted it comes back, and an
 * empty {@code {}} is respected. {@code # rs:extensible-root} in the file header (before the
 * first key) does the same for the top level. Either
 * side can declare a marker and it only adds protection: declared by the resource it is binding
 * (deleting it on disk does not re-enable merging); declared on disk, the owner freezes that
 * subtree silently: how many keys were held back is logged at FINE only. A marker above a key
 * with a single value protects nothing and is reported by {@link #markerWarnings(List)}.</p>
 *
 * <h2>Files</h2>
 * <p>Before rewriting a file, it is copied to {@code old-<name>-<yyyyMMdd-HHmmss>.yml} in the
 * same folder and only the three newest backups of that exact name are kept; nothing is copied
 * when there is nothing to write. When a file is first mounted, a disk file that is not valid
 * YAML (even after repairing tabs) is moved to {@code <name>.backup-N} (the first free N, so
 * earlier backups are never touched) and seeded again from the jar, with a warning. On a
 * reload of a file already in use it is left untouched instead, with a warning, and the
 * content read before stays in use until it is fixed. Files are written with {@code '\n'} line
 * endings through a temporary file. I/O errors are logged as SEVERE with the prefix
 * {@code [update-configs]}; nothing here throws.</p>
 *
 * <h2>The {@code update-configs} switch</h2>
 * <p>{@code update-configs} in {@code plugins/RsLib/config.yml} (true by default) turns merging
 * on and off for every plugin at once ({@link #setMergeEnabled}). A plugin whose main config has
 * its own top-level {@code update-configs} uses that value for all of its files instead (its
 * config, the other managed files and its messages); the caller passes it as {@code own}, and
 * {@code null} means the plugin has none and follows RsLib's. When the switch that applies is
 * false, an existing file is never written: the keys it lacks are counted and reported in one
 * warning per file ({@code update-configs is false: N keys missing in <file>}), and RsLib's own
 * config.yml is still merged so the switch itself can arrive. Missing files are still created
 * from the jar, and a file that is not valid YAML is still handled as below.</p>
 *
 * <h2>Tabs</h2>
 * <p>The structure is computed on spaces only, so a disk file indented with tabs is repaired
 * with {@link YamlPreprocessor} in memory <em>before</em> merging: validity and structure are
 * judged on the same text. Tabs alone never make the file be written; when keys are inserted,
 * the lines the owner indented with tabs are written back exactly as they were. The warning
 * listing those lines comes from whoever reads the file ({@link RsYml}, the lang loader).</p>
 */
public final class YamlUpdater {

    /** Comment marker above a key whose contents belong to the server owner. */
    public static final String EXTENSIBLE_MARKER = "rs:extensible";
    /** Header comment marker making every top-level key belong to the server owner. */
    public static final String EXTENSIBLE_ROOT_MARKER = "rs:extensible-root";

    static final String PREFIX = "[update-configs] ";
    /** The switch's key, at the top of RsLib's config.yml and, optionally, of a plugin's main config. */
    static final String SWITCH_KEY = "update-configs";

    private static volatile boolean mergeEnabled = true;
    private static volatile Path mergeExempt;

    private YamlUpdater() {
    }

    // ------------------------------------------------------------------ the update-configs switch

    /**
     * Turns merging into existing files on or off for every plugin; {@code exempt} (RsLib's own
     * config.yml) is merged either way. Set by RsLib from {@code update-configs}.
     */
    public static void setMergeEnabled(boolean enabled, File exempt) {
        mergeExempt = exempt == null ? null : exempt.toPath().toAbsolutePath().normalize();
        mergeEnabled = enabled;
    }

    /** True when files are merged (the default). */
    public static boolean mergeEnabled() {
        return mergeEnabled;
    }

    /** {@code own} (the plugin's switch, null when it has none) wins over RsLib's; RsLib's config always merges. */
    private static boolean mayMerge(Path path, Boolean own) {
        Path exempt = mergeExempt;
        if (exempt != null && exempt.equals(path.toAbsolutePath().normalize())) {
            return true;
        }
        return own != null ? own : mergeEnabled;
    }

    /**
     * The plugin's own switch: the top-level {@code update-configs} of its main config on disk,
     * or null when the file is missing, is not valid YAML or lacks the key. A value other than
     * true or false is ignored with a warning (none when {@code logger} is null), so RsLib's
     * setting applies.
     */
    static Boolean readSwitch(Logger logger, File file) {
        if (!file.isFile()) {
            return null;
        }
        Object value;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(YamlPreprocessor.preprocess(YamlText.read(file.toPath())).cleanText());
            value = yaml.get(SWITCH_KEY);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            return null;
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        if (logger != null) {
            logger.warning(PREFIX + SWITCH_KEY + " in " + file.getName() + " must be true or false; using RsLib's "
                    + SWITCH_KEY + " instead");
        }
        return null;
    }

    /** How many keys of {@code referenceLines} the disk lines (tab-free) lack. */
    static int missingKeys(List<String> referenceLines, List<String> diskLines) {
        List<String> reference = YamlText.lines(
                YamlPreprocessor.preprocess(String.join("\n", referenceLines)).cleanText());
        List<String> merged = merge(reference, diskLines);
        return YamlOutline.descendants(YamlOutline.parse(merged)).size()
                - YamlOutline.descendants(YamlOutline.parse(diskLines)).size();
    }

    // ------------------------------------------------------------------ pure line functions

    /**
     * Returns {@code diskLines} with every key of {@code resourceLines} it lacks inserted (see
     * the class description). Pass tab-free lines (see {@link YamlPreprocessor}). Neither list
     * is modified.
     */
    public static List<String> merge(List<String> resourceLines, List<String> diskLines) {
        return merge(resourceLines, diskLines, null);
    }

    static List<String> merge(List<String> resourceLines, List<String> diskLines, Consumer<String> fine) {
        List<String> res = List.copyOf(resourceLines);
        return merge(res, YamlOutline.parse(res), diskLines, YamlOutline.parse(diskLines), fine);
    }

    /** Same as {@link #merge(List, List, Consumer)} with both outlines already parsed. */
    private static List<String> merge(List<String> res, Node resRoot, List<String> diskLines, Node diskRoot,
                                      Consumer<String> fine) {
        return OutlineMerge.merge(res, resRoot, diskLines, diskRoot, fine);
    }

    /**
     * Returns {@code lines} without every block whose key path does not exist in
     * {@code resourceLines}, comments included. Sections marked extensible on either side, and
     * every top-level key when either side is marked extensible-root, are left alone.
     */
    public static List<String> prune(List<String> resourceLines, List<String> lines) {
        return prune(YamlOutline.parse(List.copyOf(resourceLines)), lines);
    }

    /** Same as {@link #prune(List, List)} with the resource outline already parsed. */
    private static List<String> prune(Node resRoot, List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        List<int[]> removals = new ArrayList<>();
        pruneSection(resRoot, YamlOutline.parse(out), removals);
        removals.sort(Comparator.comparingInt((int[] r) -> r[0]).reversed());
        for (int[] range : removals) {
            out.subList(range[0], range[1]).clear();
        }
        return out;
    }

    private static void pruneSection(Node resNode, Node diskNode, List<int[]> removals) {
        if (resNode.marked() || diskNode.marked()) {
            return;
        }
        for (Node diskChild : diskNode.children()) {
            Node resChild = resNode.child(diskChild.key());
            if (resChild == null) {
                removals.add(new int[] {diskChild.start(), diskChild.end()});
            } else if (resChild.isSection() && diskChild.isSection()) {
                pruneSection(resChild, diskChild, removals);
            }
        }
    }

    /**
     * One message for every extensible marker that protects nothing because it sits above a key
     * with a single value (not a section, {@code {}} or {@code []}).
     */
    public static List<String> markerWarnings(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (Node node : uselessMarkers(YamlOutline.parse(List.copyOf(lines)))) {
            out.add(markerMessage(node));
        }
        return out;
    }

    private static List<Node> uselessMarkers(Node root) {
        List<Node> out = new ArrayList<>();
        for (Node node : YamlOutline.descendants(root)) {
            if (node.marked() && node.isSimpleValue()) {
                out.add(node);
            }
        }
        return out;
    }

    private static String markerMessage(Node node) {
        return "'" + node.path() + "' is marked " + EXTENSIBLE_MARKER
                + " but holds a value; the marker only protects the entries of a section";
    }

    /**
     * True when Bukkit can load {@code yamlText} as a configuration (an empty text is valid).
     */
    public static boolean isParseable(String yamlText) {
        return parseError(yamlText) == null;
    }

    /** Why Bukkit cannot load {@code yamlText} (never null), or null when it can. */
    static String parseError(String yamlText) {
        try {
            new YamlConfiguration().loadFromString(yamlText == null ? "" : yamlText);
            return null;
        } catch (InvalidConfigurationException | RuntimeException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /**
     * Removes the obsolete top-level {@code update-configs} key from a main config (with the
     * comments right above it) unless the jar resource still declares it, and squeezes the blank
     * lines left behind.
     */
    static List<String> removeObsoleteKey(List<String> resourceLines, List<String> diskLines) {
        return removeObsoleteKey(YamlOutline.parse(List.copyOf(resourceLines)), diskLines,
                YamlOutline.parse(List.copyOf(diskLines)));
    }

    /** Same as {@link #removeObsoleteKey(List, List)} with both outlines already parsed. */
    private static List<String> removeObsoleteKey(Node resRoot, List<String> diskLines, Node diskRoot) {
        if (resRoot.child(SWITCH_KEY) != null) {
            return diskLines;
        }
        Node node = diskRoot.child(SWITCH_KEY);
        if (node == null) {
            return diskLines;
        }
        List<String> out = new ArrayList<>(diskLines);
        int at = node.start();
        out.subList(at, node.end()).clear();
        if (at >= out.size()) {
            while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
                out.remove(out.size() - 1);
            }
        } else {
            while (at < out.size() && out.get(at).isBlank() && (at == 0 || out.get(at - 1).isBlank())) {
                out.remove(at);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ files

    /**
     * Seeds {@code diskFile} from the jar resource when it is missing, otherwise merges the
     * resource into it (and, with {@code prune}, removes keys the resource no longer has).
     */
    public static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune) {
        update(plugin, resourcePath, diskFile, prune, null);
    }

    /**
     * Same as {@link #update(JavaPlugin, String, File, boolean)} under the plugin's own
     * {@code update-configs} ({@code own}); null follows RsLib's.
     */
    public static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune, Boolean own) {
        updateResource(plugin.getLogger(), plugin::getResource, resourcePath, diskFile, prune, false, false, own);
    }

    /**
     * Merges {@code referenceLines} into {@code diskFile} (seeding it when missing); used, for
     * example, to merge a translation against the English file.
     *
     * @return true when the file was written
     */
    public static boolean updateFromLines(JavaPlugin plugin, List<String> referenceLines, File diskFile) {
        return updateFromLines(plugin.getLogger(), referenceLines, diskFile);
    }

    /** Same as {@link #updateFromLines(JavaPlugin, List, File)} with an explicit logger. */
    public static boolean updateFromLines(Logger logger, List<String> referenceLines, File diskFile) {
        return updateFromLines(logger, referenceLines, diskFile, false);
    }

    /**
     * Same as {@link #updateFromLines(Logger, List, File)}; with {@code force} the file is merged
     * even when {@code update-configs} is false. Meant for RsLib's shared lang keys, which other
     * modules depend on.
     */
    public static boolean updateFromLines(Logger logger, List<String> referenceLines, File diskFile, boolean force) {
        return updateFromLines(logger, referenceLines, diskFile, force, null);
    }

    /**
     * Same as {@link #updateFromLines(Logger, List, File, boolean)} under the plugin's own
     * {@code update-configs} ({@code own}); null follows RsLib's.
     */
    public static boolean updateFromLines(Logger logger, List<String> referenceLines, File diskFile, boolean force,
                                          Boolean own) {
        return updateLines(logger, referenceLines, diskFile, false, false, false, force, own) == Outcome.WRITTEN;
    }

    /** Reads a resource; null (with a warning) when the jar lacks it. */
    static List<String> readResource(Logger logger, Function<String, InputStream> resources, String path) {
        try {
            InputStream in = resources.apply(path);
            if (in == null) {
                logger.warning(PREFIX + "Resource " + path + " absent from the jar");
                return null;
            }
            return YamlText.lines(YamlText.read(in));
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, PREFIX + "Could not read resource " + path + " from the jar", e);
            return null;
        }
    }

    static boolean updateResource(Logger logger, Function<String, InputStream> resources, String resourcePath,
                                  File diskFile, boolean prune, boolean dropObsolete) {
        return updateResource(logger, resources, resourcePath, diskFile, prune, dropObsolete, false)
                == Outcome.WRITTEN;
    }

    /**
     * What {@link #updateLines} did: nothing, wrote the file, or left an invalid file alone
     * (only with {@code keepInvalid}).
     */
    enum Outcome { UNCHANGED, WRITTEN, KEPT_INVALID }

    /**
     * Seeds or merges {@code diskFile} from the jar resource.
     *
     * @param keepInvalid true on a reload of a file already in use: a disk file that is not valid
     *                    YAML is then left as it is (with a warning) instead of being moved aside
     *                    and seeded again, so one typo never swaps the owner's file for the jar copy
     */
    static Outcome updateResource(Logger logger, Function<String, InputStream> resources, String resourcePath,
                                  File diskFile, boolean prune, boolean dropObsolete, boolean keepInvalid) {
        return updateResource(logger, resources, resourcePath, diskFile, prune, dropObsolete, keepInvalid, null);
    }

    /** {@code own} is the plugin's {@code update-configs}, null when it has none. */
    static Outcome updateResource(Logger logger, Function<String, InputStream> resources, String resourcePath,
                                  File diskFile, boolean prune, boolean dropObsolete, boolean keepInvalid,
                                  Boolean own) {
        List<String> reference = readResource(logger, resources, resourcePath);
        if (reference == null) {
            return Outcome.UNCHANGED;
        }
        return updateLines(logger, reference, diskFile, prune, dropObsolete, keepInvalid, false, own);
    }

    /** Writes {@code lines} to {@code file} as they are, creating folders. */
    static void seed(Path file, List<String> lines) throws IOException {
        YamlText.write(file, YamlText.join(lines));
    }

    static Outcome updateLines(Logger logger, List<String> referenceLines, File diskFile,
                               boolean prune, boolean dropObsolete, boolean keepInvalid) {
        return updateLines(logger, referenceLines, diskFile, prune, dropObsolete, keepInvalid, false, null);
    }

    /** {@code force} merges past the {@code update-configs} switch; {@code own} is the plugin's, null when none. */
    private static Outcome updateLines(Logger logger, List<String> referenceLines, File diskFile,
                                       boolean prune, boolean dropObsolete, boolean keepInvalid, boolean force,
                                       Boolean own) {
        Path path = diskFile.toPath();
        String name = diskFile.getName();
        try {
            if (!Files.exists(path)) {
                seed(path, referenceLines);
                return Outcome.WRITTEN;
            }
            String raw = YamlText.read(path);
            YamlPreprocessor.Result repaired = YamlPreprocessor.preprocess(raw);
            String error = parseError(repaired.cleanText());
            if (error != null) {
                return invalidFile(logger, referenceLines, path, error, keepInvalid);
            }
            List<String> clean = YamlText.lines(repaired.cleanText());
            if (!force && !mayMerge(path, own)) {
                int missing = missingKeys(referenceLines, clean);
                if (missing > 0) {
                    logger.warning(PREFIX + "update-configs is false: " + missing + " keys missing in " + name);
                }
                return Outcome.UNCHANGED;
            }
            List<String> merged = mergedLines(logger, name, referenceLines, clean, prune, dropObsolete);
            if (merged.equals(clean)) {
                return Outcome.UNCHANGED;
            }
            List<String> out = keepTabs(merged, clean, YamlText.lines(raw), repaired.fixedLines());
            MergeBackups.backup(path);
            YamlText.write(path, YamlText.join(out));
            return Outcome.WRITTEN;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, PREFIX + "Could not update " + diskFile.getPath(), e);
            return Outcome.UNCHANGED;
        }
    }

    /**
     * A disk file that is not valid YAML. On a reload it is kept as it is, so the content read
     * before stays in use until the owner fixes it; otherwise it is moved to
     * {@code <name>.backup-N} (the first free N) and seeded again from the jar.
     */
    private static Outcome invalidFile(Logger logger, List<String> referenceLines, Path path, String error,
                                       boolean keepInvalid) throws IOException {
        String name = path.getFileName().toString();
        if (keepInvalid) {
            logger.warning(PREFIX + name + " is not valid YAML; keeping the previous content until it is fixed. "
                    + firstLine(error));
            return Outcome.KEPT_INVALID;
        }
        Path moved = MergeBackups.moveCorrupt(path);
        seed(path, referenceLines);
        logger.warning(PREFIX + name + " does not parse as YAML: backed up to " + moved.getFileName()
                + " and regenerated from the jar");
        return Outcome.WRITTEN;
    }

    /**
     * {@code merged} with every line whose tabs were repaired in memory put back as the owner
     * wrote it. The merge, the pruning and the obsolete-key removal only copy the disk lines
     * they keep (the same String objects), so a repaired line is found by identity; inserted
     * lines are always new objects and stay as they are.
     *
     * @param clean      the repaired disk lines given to the merge
     * @param raw        the disk lines as read, line for line with {@code clean}
     * @param fixedLines the 1-based numbers of the repaired lines
     */
    static List<String> keepTabs(List<String> merged, List<String> clean, List<String> raw,
                                 List<Integer> fixedLines) {
        if (fixedLines.isEmpty() || clean.size() != raw.size()) {
            return merged;
        }
        Map<String, String> original = new IdentityHashMap<>();
        for (int number : fixedLines) {
            if (number >= 1 && number <= clean.size()) {
                original.put(clean.get(number - 1), raw.get(number - 1));
            }
        }
        List<String> out = new ArrayList<>(merged.size());
        for (String line : merged) {
            out.add(original.getOrDefault(line, line));
        }
        return out;
    }

    /**
     * The disk lines (tab-free) with the obsolete key dropped (when asked), the reference merged
     * in and, with {@code prune}, the keys the reference lacks removed. Each side is parsed once
     * and the outlines are shared by every step that reads the same lines.
     */
    private static List<String> mergedLines(Logger logger, String name, List<String> referenceLines,
                                            List<String> diskLines, boolean prune, boolean dropObsolete) {
        List<String> reference = YamlText.lines(
                YamlPreprocessor.preprocess(String.join("\n", referenceLines)).cleanText());
        Node resRoot = YamlOutline.parse(reference);
        List<String> lines = diskLines;
        Node diskRoot = YamlOutline.parse(lines);
        if (dropObsolete) {
            List<String> dropped = removeObsoleteKey(resRoot, lines, diskRoot);
            if (dropped != lines) {
                lines = dropped;
                diskRoot = YamlOutline.parse(lines);
            }
        }
        reportMarkers(logger, resRoot, diskRoot);
        List<String> merged = merge(reference, resRoot, lines, diskRoot,
                note -> logger.fine(PREFIX + name + ": " + note));
        return prune ? prune(resRoot, merged) : merged;
    }

    private static String firstLine(String message) {
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline);
    }

    private static void reportMarkers(Logger logger, Node resRoot, Node diskRoot) {
        Set<String> known = new HashSet<>();
        for (Node node : uselessMarkers(resRoot)) {
            known.add(node.path());
            logger.warning(markerMessage(node));
        }
        for (Node node : uselessMarkers(diskRoot)) {
            if (!known.contains(node.path())) {
                logger.warning(markerMessage(node));
            }
        }
    }
}
