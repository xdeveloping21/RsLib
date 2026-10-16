package com.rs.lib.yml.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import com.rs.lib.yml.internal.YamlOutline.Node;

/**
 * The "always merge" of the config updater on plain lines: every key of a reference text that a
 * disk text lacks is copied in with its whole block, and nothing else changes. Pure and free of
 * Bukkit, so the Velocity side of RsLib merges proxy configs the same way. Not API: plugins
 * should not call it; the rules are described on {@code YamlUpdater}.
 */
public final class OutlineMerge {

    private OutlineMerge() {
    }

    /**
     * Returns {@code diskLines} with every key of {@code res} it lacks inserted. Pass tab-free
     * lines and their outlines; neither list is modified.
     *
     * @param fine receives a note for each key an extensible marker on disk kept out; may be null
     */
    public static List<String> merge(List<String> res, Node resRoot, List<String> diskLines, Node diskRoot,
                                     Consumer<String> fine) {
        List<String> disk = new ArrayList<>(diskLines);
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
                int count = withheld(resNode, diskNode, "", skipped);
                if (count > 0) {
                    fine.accept("'" + (diskNode.isRoot() ? "<top level>" : diskNode.path())
                            + "' is marked extensible on disk; " + count + " key" + (count == 1 ? "" : "s")
                            + " withheld from the jar: " + skipped);
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

    /**
     * How many keys the merge would have inserted below {@code diskNode} had the owner not marked
     * it (a missing block counts with everything nested in it); {@code out} gets the path of each
     * missing block, relative to {@code diskNode}. Sections the jar copy marks itself are left
     * out: the owner's marker does not hold those back.
     */
    private static int withheld(Node resNode, Node diskNode, String prefix, List<String> out) {
        int count = 0;
        for (Node resChild : resNode.children()) {
            Node diskChild = diskNode.child(resChild.key());
            if (diskChild == null) {
                out.add(prefix + resChild.key());
                count += 1 + YamlOutline.descendants(resChild).size();
            } else if (resChild.isSection() && diskChild.isSection() && !resChild.marked()) {
                count += withheld(resChild, diskChild, prefix + resChild.key() + ".", out);
            }
        }
        return count;
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
}
