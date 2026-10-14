package com.rs.lib.yml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.yml.internal.MergeBackups;
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
 * first key) does the same for the top level. The legacy spellings {@code sn:extensible} and
 * {@code sn:extensible-root}, already present in existing files, are recognized too. Either
 * side can declare a marker and it only adds protection: declared by the resource it is binding
 * (deleting it on disk does not re-enable merging); declared on disk, the owner freezes that
 * subtree and what was not inserted is logged at FINE only. A marker above a key with a single
 * value protects nothing and is reported by {@link #markerWarnings(List)}.</p>
 *
 * <h2>Files</h2>
 * <p>Before rewriting a file, it is copied to {@code old-<name>-<yyyyMMdd-HHmmss>.yml} in the
 * same folder and only the three newest backups of that exact name are kept. A disk file that
 * is not valid YAML (even after repairing tabs) is moved to {@code <name>.backup-N} and seeded
 * again from the jar, with a warning. Files are written with {@code '\n'} line endings through
 * a temporary file. I/O errors are logged as SEVERE with the prefix {@code [config-updater]};
 * nothing here throws.</p>
 *
 * <h2>Tabs</h2>
 * <p>The structure is computed on spaces only, so a disk file indented with tabs is repaired
 * with {@link YamlPreprocessor} <em>before</em> merging (an RsLib decision): validity and
 * structure are judged on the same text, and the file is written back repaired (with a backup
 * and a warning listing the lines).</p>
 */
public final class YamlUpdater {

    /** Comment marker above a key whose contents belong to the server owner. */
    public static final String EXTENSIBLE_MARKER = "rs:extensible";
    /** Header comment marker making every top-level key belong to the server owner. */
    public static final String EXTENSIBLE_ROOT_MARKER = "rs:extensible-root";
    /** Spelling used by files written for SnLib; still recognized. */
    public static final String LEGACY_EXTENSIBLE_MARKER = "sn:extensible";
    /** Spelling used by files written for SnLib; still recognized. */
    public static final String LEGACY_EXTENSIBLE_ROOT_MARKER = "sn:extensible-root";

    static final String PREFIX = "[config-updater] ";
    static final String OBSOLETE_KEY = "update-configs";

    private YamlUpdater() {
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
        List<String> disk = new ArrayList<>(diskLines);
        Node resRoot = YamlOutline.parse(res);
        Node diskRoot = YamlOutline.parse(disk);
        List<Insertion> insertions = new ArrayList<>();
        mergeSection(res, resRoot, diskRoot, insertions, fine);
        insertions.sort(Comparator.comparingInt(Insertion::position).thenComparingInt(Insertion::order)
                .reversed());
        for (Insertion insertion : insertions) {
            disk.addAll(insertion.position(), insertion.lines());
        }
        return disk;
    }

    private record Insertion(int position, int order, List<String> lines) {
    }

    private static void mergeSection(List<String> res, Node resNode, Node diskNode,
                                     List<Insertion> out, Consumer<String> fine) {
        if (resNode.marked() || diskNode.marked()) {
            if (!resNode.marked() && fine != null) {
                List<String> skipped = new ArrayList<>();
                for (Node child : resNode.children()) {
                    if (diskNode.child(child.key()) == null) {
                        skipped.add(child.key());
                    }
                }
                if (!skipped.isEmpty()) {
                    fine.accept("'" + (diskNode.isRoot() ? "<top level>" : diskNode.path())
                            + "' is marked extensible on disk; not added from the jar: " + skipped);
                }
            }
            return;
        }
        List<Node> children = resNode.children();
        for (int i = 0; i < children.size(); i++) {
            Node resChild = children.get(i);
            Node diskChild = diskNode.child(resChild.key());
            if (diskChild != null) {
                if (resChild.isSection() && diskChild.isSection()
                        && (!resChild.children().isEmpty() || resChild.marked())) {
                    mergeSection(res, resChild, diskChild, out, fine);
                }
                continue;
            }
            int position = insertionPoint(resNode, diskNode, i);
            int shift = targetIndent(resNode, diskNode, resChild) - resChild.indent();
            List<String> block = reindent(res.subList(resChild.start(), resChild.end()), shift);
            out.add(new Insertion(position, out.size(), block));
        }
    }

    private static int insertionPoint(Node resNode, Node diskNode, int index) {
        List<Node> siblings = resNode.children();
        for (int j = index - 1; j >= 0; j--) {
            Node anchor = diskNode.child(siblings.get(j).key());
            if (anchor != null) {
                return anchor.end();
            }
        }
        for (int j = index + 1; j < siblings.size(); j++) {
            Node anchor = diskNode.child(siblings.get(j).key());
            if (anchor != null) {
                return anchor.start();
            }
        }
        List<Node> diskChildren = diskNode.children();
        if (!diskChildren.isEmpty()) {
            return diskChildren.get(diskChildren.size() - 1).end();
        }
        return diskNode.end();
    }

    private static int targetIndent(Node resNode, Node diskNode, Node resChild) {
        if (!diskNode.children().isEmpty()) {
            return diskNode.children().get(0).indent();
        }
        if (diskNode.isRoot()) {
            return 0;
        }
        int step = resNode.isRoot() ? resChild.indent() : resChild.indent() - resNode.indent();
        return diskNode.indent() + Math.max(1, step);
    }

    private static List<String> reindent(List<String> block, int shift) {
        List<String> out = new ArrayList<>(block.size());
        for (String line : block) {
            if (shift == 0 || line.isBlank()) {
                out.add(line);
            } else if (shift > 0) {
                out.add(" ".repeat(shift) + line);
            } else {
                out.add(line.substring(Math.min(-shift, YamlOutline.leadingSpaces(line))));
            }
        }
        return out;
    }

    /**
     * Returns {@code lines} without every block whose key path does not exist in
     * {@code resourceLines}, comments included. Sections marked extensible on either side, and
     * every top-level key when either side is marked extensible-root, are left alone.
     */
    public static List<String> prune(List<String> resourceLines, List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        List<int[]> removals = new ArrayList<>();
        pruneSection(YamlOutline.parse(List.copyOf(resourceLines)), YamlOutline.parse(out), removals);
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
        for (Node node : uselessMarkers(lines)) {
            out.add(markerMessage(node));
        }
        return out;
    }

    private static List<Node> uselessMarkers(List<String> lines) {
        List<Node> out = new ArrayList<>();
        for (Node node : YamlOutline.descendants(YamlOutline.parse(List.copyOf(lines)))) {
            if (node.marked() && node.isSimpleValue()) {
                out.add(node);
            }
        }
        return out;
    }

    private static String markerMessage(Node node) {
        return "'" + node.path() + "' (line " + (node.line() + 1) + ") is marked " + EXTENSIBLE_MARKER
                + " but holds a single value, so the marker protects nothing; put it above a section";
    }

    /**
     * True when Bukkit can load {@code yamlText} as a configuration (an empty text is valid).
     */
    public static boolean isParseable(String yamlText) {
        try {
            new YamlConfiguration().loadFromString(yamlText == null ? "" : yamlText);
            return true;
        } catch (InvalidConfigurationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Removes the obsolete top-level {@code update-configs} key from a main config (with the
     * comments right above it) unless the jar resource still declares it, and squeezes the blank
     * lines left behind.
     */
    static List<String> removeObsoleteKey(List<String> resourceLines, List<String> diskLines) {
        if (YamlOutline.parse(List.copyOf(resourceLines)).child(OBSOLETE_KEY) != null) {
            return diskLines;
        }
        Node node = YamlOutline.parse(List.copyOf(diskLines)).child(OBSOLETE_KEY);
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
        updateResource(plugin.getLogger(), plugin::getResource, resourcePath, diskFile, prune, false);
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
        return updateLines(logger, referenceLines, diskFile, false, false);
    }

    /**
     * Kept for plugins compiled against the four-argument form.
     *
     * @param ignored no longer used
     * @deprecated use {@link #updateFromLines(JavaPlugin, List, File)}
     */
    @Deprecated
    public static boolean updateFromLines(JavaPlugin plugin, List<String> referenceLines, File diskFile, File ignored) {
        return updateFromLines(plugin, referenceLines, diskFile);
    }

    /**
     * Kept for plugins compiled against the four-argument form.
     *
     * @param ignored no longer used
     * @deprecated use {@link #updateFromLines(Logger, List, File)}
     */
    @Deprecated
    public static boolean updateFromLines(Logger logger, List<String> referenceLines, File diskFile, File ignored) {
        return updateFromLines(logger, referenceLines, diskFile);
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
        List<String> reference = readResource(logger, resources, resourcePath);
        if (reference == null) {
            return false;
        }
        return updateLines(logger, reference, diskFile, prune, dropObsolete);
    }

    /** Writes {@code lines} to {@code file} as they are, creating folders. */
    static void seed(Path file, List<String> lines) throws IOException {
        YamlText.write(file, YamlText.join(lines));
    }

    static boolean updateLines(Logger logger, List<String> referenceLines, File diskFile,
                               boolean prune, boolean dropObsolete) {
        Path path = diskFile.toPath();
        String name = diskFile.getName();
        try {
            if (!Files.exists(path)) {
                seed(path, referenceLines);
                return true;
            }
            String raw = YamlText.read(path);
            YamlPreprocessor.Result repaired = YamlPreprocessor.preprocess(raw);
            if (!isParseable(repaired.cleanText())) {
                Path moved = MergeBackups.moveCorrupt(path);
                seed(path, referenceLines);
                logger.warning(PREFIX + name + " is not valid YAML; it was moved to " + moved.getFileName()
                        + " and created again from the jar. Copy your changes back from the backup.");
                return true;
            }
            List<String> reference = YamlText.lines(
                    YamlPreprocessor.preprocess(String.join("\n", referenceLines)).cleanText());
            List<String> original = YamlText.lines(raw);
            List<String> lines = YamlText.lines(repaired.cleanText());
            if (dropObsolete) {
                lines = removeObsoleteKey(reference, lines);
            }
            reportMarkers(logger, name, reference, lines);
            List<String> merged = merge(reference, lines,
                    note -> logger.fine(PREFIX + name + ": " + note));
            if (prune) {
                merged = prune(reference, merged);
            }
            if (merged.equals(original)) {
                return false;
            }
            if (!repaired.fixedLines().isEmpty()) {
                logger.warning(PREFIX + "Indentation tabs fixed in " + name + " (lines "
                        + repaired.fixedLines() + ")");
            }
            MergeBackups.backup(path);
            YamlText.write(path, YamlText.join(merged));
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, PREFIX + "Could not update " + diskFile.getPath(), e);
            return false;
        }
    }

    private static void reportMarkers(Logger logger, String name, List<String> reference, List<String> disk) {
        Set<String> known = new HashSet<>();
        for (Node node : uselessMarkers(reference)) {
            known.add(node.path());
            logger.warning(PREFIX + name + " (jar copy): " + markerMessage(node));
        }
        for (Node node : uselessMarkers(disk)) {
            if (!known.contains(node.path())) {
                logger.warning(PREFIX + name + ": " + markerMessage(node));
            }
        }
    }
}
