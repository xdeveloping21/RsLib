package com.rs.lib.yml.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class YmlInternalTest {

    @Test
    void mergeBackupNames() {
        assertTrue(MergeBackups.isMergeBackup("old-config-20261013-142530.yml"));
        assertTrue(MergeBackups.isMergeBackup("OLD-shop-menu-20261013-142530.YML"));
        assertFalse(MergeBackups.isMergeBackup("old-town.yml"));
        assertFalse(MergeBackups.isMergeBackup("old-config-2026101-142530.yml"));
        assertFalse(MergeBackups.isMergeBackup("config.yml"));
        assertFalse(MergeBackups.isMergeBackup(null));
        assertEquals("yyyyMMdd-HHmmss", MergeBackups.STAMP);
        assertEquals("old-config-20261013-142530.yml",
                MergeBackups.backupName("config.yml", LocalDateTime.of(2026, 10, 13, 14, 25, 30)));
        assertTrue(MergeBackups.isBackupOf("config.yml", "old-config-20261013-142530.yml"));
        assertFalse(MergeBackups.isBackupOf("config.yml", "old-config-extra-20261013-142530.yml"));
    }

    @Test
    void resourceFoldersNormalize() {
        assertEquals("addons/rewards", ResourceFolders.normalize("  addons\\rewards/ ", "lang", "message"));
        assertEquals("b", ResourceFolders.normalize("./a/../b//", "lang", "message"));
        assertEquals("lang/extra", ResourceFolders.normalize("lang/extra", "lang", "message"));
        for (String bad : List.of("C:/x", "/abs:x", "..", "a/../..", "", "  ", "./", "LANG", "x/../Lang")) {
            assertThrows(IllegalArgumentException.class, () -> ResourceFolders.normalize(bad, "lang", "message"),
                    bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ResourceFolders.normalize("Guis", "guis", "menu"));
        assertThrows(IllegalArgumentException.class, () -> ResourceFolders.normalize(null, "guis", "menu"));
    }

    @Test
    void outlineParsesBlocks() {
        YamlOutline.Node root = YamlOutline.parse(YamlText.lines(
                "# head\n\n# a doc\na:\n  b: 1\n  # c doc\n  c:\n  - x\n  - y: 2\n    z: 3\nd: |\n  e: 4\n"));
        assertEquals(List.of("a", "d"), root.children().stream().map(YamlOutline.Node::key).toList());
        YamlOutline.Node a = root.child("a");
        assertEquals(2, a.start());
        assertEquals(10, a.end());
        assertEquals(List.of("b", "c"), a.children().stream().map(YamlOutline.Node::key).toList());
        YamlOutline.Node c = a.child("c");
        assertTrue(c.hasListItems());
        assertFalse(c.isSection());
        assertEquals(5, c.start());
        assertTrue(root.child("d").children().isEmpty());
        assertEquals("a.c", c.path());
    }

    @Test
    void keyDetection() {
        assertEquals(1, YamlOutline.keyColon("a: b"));
        assertEquals(1, YamlOutline.keyColon("a:"));
        assertEquals(3, YamlOutline.keyColon("a:b: c"));
        assertEquals(5, YamlOutline.keyColon("'a:b': c"));
        assertEquals(-1, YamlOutline.keyColon("- a: b"));
        assertEquals(-1, YamlOutline.keyColon("# a: b"));
        assertEquals(-1, YamlOutline.keyColon("text # a: b"));
        assertEquals(-1, YamlOutline.keyColon("http://x"));
        assertEquals("a.b", YamlOutline.unquote("\"a.b\""));
        assertEquals("'a", YamlOutline.unquote("'a"));
        assertEquals(9, YamlOutline.commentStart("a: 'x#y' # z"));
        assertEquals(-1, YamlOutline.commentStart("it's: a#b"));
    }
}
