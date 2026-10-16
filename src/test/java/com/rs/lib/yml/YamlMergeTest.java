package com.rs.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rs.lib.yml.internal.YamlText;

/** The pure line functions of {@link YamlUpdater}: merge, prune, markers. */
class YamlMergeTest {

    private static List<String> l(String text) {
        return YamlText.lines(text);
    }

    private static String merge(String res, String disk) {
        List<String> out = YamlUpdater.merge(l(res), l(disk));
        String text = String.join("\n", out) + "\n";
        assertTrue(YamlUpdater.isParseable(text), "result must be valid YAML:\n" + text);
        return text;
    }

    private static String prune(String res, String disk) {
        return String.join("\n", YamlUpdater.prune(l(res), l(disk))) + "\n";
    }

    // ------------------------------------------------------------------ insertion positions

    @Test
    void identicalFilesDoNotChange() {
        String text = "# header\n\na: 1\nb:\n  c: 2\n";
        assertEquals(text, merge(text, text));
    }

    @Test
    void newKeyGoesAfterPreviousSiblingWithItsComments() {
        String res = "a: 1\n# about b\nb: 2\nc: 3\n";
        String disk = "a: 10\nc: 30\n";
        assertEquals("a: 10\n# about b\nb: 2\nc: 30\n", merge(res, disk));
    }

    @Test
    void newKeyWithoutPreviousSiblingGoesBeforeNextSiblingBlock() {
        String res = "first: 1\n# about second\nsecond: 2\n";
        String disk = "# my comment about second\nsecond: 20\n";
        assertEquals("first: 1\n# my comment about second\nsecond: 20\n", merge(res, disk));
    }

    @Test
    void newKeyGoesBeforeNextSiblingWhenOnlyItExists() {
        String res = "s:\n  x: 1\n  y: 2\n";
        String disk = "s:\n  # mine\n  y: 5\n";
        assertEquals("s:\n  x: 1\n  # mine\n  y: 5\n", merge(res, disk));
    }

    @Test
    void newKeyGoesToEndOfParentWhenNoSiblingMatches() {
        String res = "s:\n  x: 1\nt: 2\n";
        String disk = "s:\n  other: 9\nt: 2\n";
        assertEquals("s:\n  other: 9\n  x: 1\nt: 2\n", merge(res, disk));
        // Empty parent: at the end of the parent block.
        assertEquals("s:\n  x: 1\nt: 2\n", merge(res, "s:\nt: 2\n"));
        // Root: end of file.
        assertEquals("zzz: 1\na: 1\n", merge("a: 1\n", "zzz: 1\n"));
    }

    @Test
    void severalMissingKeysKeepResourceOrderBottomUp() {
        String res = "a: 1\nb: 2\nc: 3\nd: 4\ne: 5\n";
        String disk = "a: 1\nd: 4\n";
        assertEquals("a: 1\nb: 2\nc: 3\nd: 4\ne: 5\n", merge(res, disk));
    }

    @Test
    void recursesIntoSectionsAndKeepsUserValuesKeysAndComments() {
        String res = "# Jar header\nsettings:\n  cooldown: 30\n  # new option\n  sound: true\n"
                + "  nested:\n    a: 1\n    b: 2\nmessages:\n  hi: Hello\n";
        String disk = "# Owner header\nsettings:\n  cooldown: 99 # mine\n  custom: x\n"
                + "  nested:\n    a: 7\nmessages:\n  hi: Hola\n";
        String expected = "# Owner header\nsettings:\n  cooldown: 99 # mine\n  # new option\n  sound: true\n"
                + "  custom: x\n  nested:\n    a: 7\n    b: 2\nmessages:\n  hi: Hola\n";
        assertEquals(expected, merge(res, disk));
    }

    @Test
    void lastChildOfSectionThenNextRootKey() {
        String res = "a:\n  x: 1\n  y: 2\nb: 1\n";
        String disk = "a:\n  x: 1\n\nb: 1\n";
        assertEquals("a:\n  x: 1\n  y: 2\n\nb: 1\n", merge(res, disk));
        // A nested insertion and a sibling insertion at the same spot keep their order.
        assertEquals("a:\n  x: 1\n  y: 2\nb: 1\n", merge(res, "a:\n  x: 1\n"));
    }

    @Test
    void quotedKeysAreComparedWithoutQuotes() {
        String res = "'a.b': 1\n\"c\": 2\nd: 3\n";
        String disk = "\"a.b\": 5\nc: 6\n";
        assertEquals("\"a.b\": 5\nc: 6\nd: 3\n", merge(res, disk));
    }

    @Test
    void blockIsReindentedToTheDiskIndentation() {
        String res = "s:\n  a: 1\n  b:\n    c: 1\n";
        String disk = "s:\n    a: 5\n";
        assertEquals("s:\n    a: 5\n    b:\n      c: 1\n", merge(res, disk));
    }

    @Test
    void sectionWithChildlessDiskKeyGetsDiskIndentPlusResourceStep() {
        String res = "s:\n  t:\n    a: 1\n";
        String disk = "s:\n    t:\n";
        assertEquals("s:\n    t:\n      a: 1\n", merge(res, disk));
    }

    @Test
    void userChangedTypeIsNeverMergedInto() {
        String res = "a:\n  x: 1\nb:\n  y: 1\nc:\n  z: 1\n";
        String disk = "a: off\nb: {}\nc:\n- one\n";
        assertEquals(disk, merge(res, disk));
    }

    @Test
    void listsOfMapsAndInlineMapsAreNotLookedInto() {
        String res = "items:\n  - name: a\n    extra: 1\ninline: {a: 1, b: 2}\n";
        String disk = "items:\n  - name: mine\ninline: {a: 5}\n";
        assertEquals(disk, merge(res, disk));
    }

    @Test
    void blockScalarContentIsNotReadAsKeys() {
        String res = "help: |\n  usage: /cmd\n  more: text\nnext: 1\n";
        String disk = "help: |\n  mine: only\nnext: 2\n";
        assertEquals(disk, merge(res, disk));
    }

    @Test
    void multilineFlowAndQuotedValuesAreSkipped() {
        String res = "list: [\n  a: b,\n  c\n]\nmsg: \"one\n  two: three\"\nz: 1\n";
        String disk = "list: [x]\nmsg: \"x\"\n";
        assertEquals("list: [x]\nmsg: \"x\"\nz: 1\n", merge(res, disk));
    }

    @Test
    void fileHeaderStaysAboveKeysInsertedFirst() {
        String res = "new: 1\nold: 2\n";
        String disk = "# Header of my file\n\nold: 5\n";
        assertEquals("# Header of my file\n\nnew: 1\nold: 5\n", merge(res, disk));
    }

    @Test
    void emptyDiskReceivesEverything() {
        String res = "# c\na: 1\nb:\n  c: 2\n";
        assertEquals(res, merge(res, ""));
    }

    // ------------------------------------------------------------------ markers

    @Test
    void resourceMarkerProtectsSectionContentsButKeyIsSchema() {
        for (String marker : List.of("# rs:extensible", "# sn:extensible", "#   RS:Extensible  ")) {
            String res = "settings:\n  a: 1\n" + marker + "\nrewards:\n  example:\n    cmd: give\n";
            // The owner deleted the example and added their own entry: nothing comes back inside.
            String disk = "settings:\n  a: 1\nrewards:\n  mine:\n    cmd: say\n";
            assertEquals(disk, merge(res, disk), marker);
            // The whole section deleted: it comes back with its marker.
            assertEquals("settings:\n  a: 1\n" + marker + "\nrewards:\n  example:\n    cmd: give\n",
                    merge(res, "settings:\n  a: 1\n"), marker);
            // An empty map is respected.
            assertEquals("settings:\n  a: 1\nrewards: {}\n", merge(res, "settings:\n  a: 1\nrewards: {}\n"));
            // Deleting the marker on disk does not re-enable merging.
            assertEquals("settings:\n  a: 1\nrewards:\n", merge(res, "settings:\n  a: 1\nrewards:\n"));
        }
    }

    @Test
    void diskMarkerFreezesSubtreeAndLogsAtFine() {
        for (String marker : List.of("# rs:extensible", "# sn:extensible")) {
            String res = "rewards:\n  example:\n    cmd: give\n";
            String disk = marker + "\nrewards:\n  mine: 1\n";
            List<String> notes = new ArrayList<>();
            List<String> out = YamlUpdater.merge(l(res), l(disk), notes::add);
            assertEquals(l(disk), out);
            assertEquals(1, notes.size(), notes.toString());
            assertTrue(notes.get(0).contains("example"), notes.get(0));
        }
    }

    @Test
    void diskMarkerNoteCountsEveryWithheldKey() {
        String res = "worlds:\n  spawn:\n    safe: true\n    pvp: false\n  nether:\n    safe: false\n";
        String disk = "# RS:EXTENSIBLE\nworlds:\n  spawn:\n    safe: false\n";
        List<String> notes = new ArrayList<>();
        assertEquals(l(disk), YamlUpdater.merge(l(res), l(disk), notes::add));
        assertEquals(List.of("'worlds' is marked extensible on disk; 3 keys withheld from the jar:"
                + " [spawn.pvp, nether]"), notes);
        // A marker with words after it is not a marker: the section is merged.
        String wordy = "# rs:extensible please\nworlds:\n  spawn:\n    safe: false\n";
        notes.clear();
        assertTrue(YamlUpdater.merge(l(res), l(wordy), notes::add).contains("  nether:"));
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void rootMarkerOnEitherSideStopsTopLevelMerging() {
        for (String marker : List.of("# rs:extensible-root", "# sn:extensible-root", "# SN:EXTENSIBLE-ROOT")) {
            String res = "# Entries\n" + marker + "\n\nexample:\n  a: 1\n";
            String disk = "mine:\n  a: 2\n";
            assertEquals(disk, merge(res, disk), marker);
            String diskMarked = marker + "\nmine:\n  a: 2\n";
            assertEquals(diskMarked, merge("example:\n  a: 1\n", diskMarked), marker);
        }
    }

    @Test
    void markerMustBeTheWholeComment() {
        String res = "# this section is sn:extensible maybe\nrewards:\n  a: 1\n";
        String disk = "rewards:\n  b: 1\n";
        assertEquals("rewards:\n  b: 1\n  a: 1\n", merge(res, disk));
    }

    @Test
    void markerOnASingleValueIsReported() {
        List<String> lines = l("# rs:extensible\nname: hello\n# sn:extensible\nsec:\n  a: 1\n"
                + "# rs:extensible\nempty: {}\n# rs:extensible\nlist: []\n");
        List<String> warnings = YamlUpdater.markerWarnings(lines);
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("'name' is marked rs:extensible but holds a value; the marker only protects the entries of a"
                + " section", warnings.get(0));
    }

    @Test
    void markerConstants() {
        assertEquals("rs:extensible", YamlUpdater.EXTENSIBLE_MARKER);
        assertEquals("rs:extensible-root", YamlUpdater.EXTENSIBLE_ROOT_MARKER);
        assertEquals("sn:extensible", YamlUpdater.LEGACY_EXTENSIBLE_MARKER);
        assertEquals("sn:extensible-root", YamlUpdater.LEGACY_EXTENSIBLE_ROOT_MARKER);
    }

    // ------------------------------------------------------------------ pruning

    @Test
    void pruneRemovesKeysMissingFromResourceWithTheirComments() {
        String res = "a: 1\ns:\n  x: 1\n";
        String disk = "# Header\n\na: 1\n# old option\nold: 2\ns:\n  x: 1\n  # gone\n  gone: 3\n";
        assertEquals("# Header\n\na: 1\ns:\n  x: 1\n", prune(res, disk));
    }

    @Test
    void pruneKeepsHeaderWhenFirstKeyGoes() {
        assertEquals("# Header\n\nb: 2\n", prune("b: 1\n", "# Header\n\na: 1\nb: 2\n"));
    }

    @Test
    void pruneRespectsMarkersOnEitherSide() {
        String res = "# sn:extensible\nrewards:\n  example: 1\n";
        assertEquals("rewards:\n  mine: 1\n", prune(res, "rewards:\n  mine: 1\n"));
        String disk = "# rs:extensible\nr:\n  mine: 1\n";
        assertEquals(disk, prune("r:\n  example: 1\n", disk));
        String rootDisk = "# rs:extensible-root\n\nmine: 1\n";
        assertEquals(rootDisk, prune("example: 1\n", rootDisk));
        assertEquals("mine: 1\n", prune("# sn:extensible-root\nexample: 1\n", "mine: 1\n"));
    }

    @Test
    void pruneDoesNotDescendWhenTypesDiffer() {
        assertEquals("a:\n  x: 1\n", prune("a: 5\n", "a:\n  x: 1\n"));
    }

    // ------------------------------------------------------------------ obsolete key

    @Test
    void obsoleteUpdateConfigsKeyIsRemovedWithItsComment() {
        String disk = "lang: en\n\n# Update the configs automatically\nupdate-configs: true\n\nprefix: x\n";
        List<String> out = YamlUpdater.removeObsoleteKey(l("lang: en\nprefix: y\n"), l(disk));
        assertEquals(l("lang: en\n\nprefix: x\n"), out);
        // At the end of the file, trailing blank lines go too.
        assertEquals(l("lang: en\n"), YamlUpdater.removeObsoleteKey(l("lang: en\n"),
                l("lang: en\n\n# auto update\nupdate-configs:\n  enabled: true\n\n")));
        // Kept when the jar copy still declares it.
        assertEquals(l(disk), YamlUpdater.removeObsoleteKey(l("update-configs: true\n"), l(disk)));
    }
}
