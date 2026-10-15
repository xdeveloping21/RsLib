package com.rs.lib.item.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The death-stash files ({@code death-stash/<kind>/<uuid>.txt}); taking items out of the drops
 * and giving them back need a server.
 */
class DeathStashFilesTest {

    private static final Logger LOG = Logger.getLogger("stash-test");

    @TempDir
    Path root;

    @Test
    void lineKeepsTheSlot() {
        assertEquals("7:QUJD", DeathStash.StashFiles.line(7, "QUJD"));
        assertEquals("QUJD", DeathStash.StashFiles.line(-1, "QUJD"));
        assertEquals(new DeathStash.StashFiles.Line(7, "QUJD"), DeathStash.StashFiles.parse("7:QUJD"));
        assertEquals(new DeathStash.StashFiles.Line(40, "QUJD"), DeathStash.StashFiles.parse(" 40: QUJD "));
        assertEquals(new DeathStash.StashFiles.Line(-1, "QUJD"), DeathStash.StashFiles.parse("QUJD"),
                "a line with only the item has no slot");
        assertNull(DeathStash.StashFiles.parse("  "));
        assertNull(DeathStash.StashFiles.parse(null));
    }

    @Test
    void writeReadDelete() {
        UUID player = UUID.randomUUID();
        DeathStash.StashFiles.write(root, DeathStash.LOCKED, player, List.of("0:AAAA", "40:BBBB"), LOG);
        DeathStash.StashFiles.write(root, DeathStash.KEEP_ON_DEATH, player, List.of("CCCC"), LOG);
        assertTrue(Files.exists(root.resolve("locked").resolve(player + ".txt")));
        assertTrue(Files.exists(root.resolve("keep-on-death").resolve(player + ".txt")));

        Map<String, Map<UUID, List<String>>> all = DeathStash.StashFiles.readAll(root, LOG);
        assertEquals(List.of("0:AAAA", "40:BBBB"), all.get("locked").get(player));
        assertEquals(List.of("CCCC"), all.get("keep-on-death").get(player));

        DeathStash.StashFiles.delete(root, player, LOG);
        assertFalse(Files.exists(root.resolve("locked").resolve(player + ".txt")));
        assertTrue(DeathStash.StashFiles.readAll(root, LOG).values().stream().allMatch(Map::isEmpty));
    }

    @Test
    void foreignFilesAndMissingFolderAreIgnored() throws Exception {
        Files.createDirectories(root.resolve("locked"));
        Files.writeString(root.resolve("locked").resolve("notes.txt"), "hello");
        assertTrue(DeathStash.StashFiles.readAll(root, LOG).getOrDefault("locked", Map.of()).isEmpty());
        assertTrue(DeathStash.StashFiles.readAll(root.resolve("missing"), LOG).isEmpty());
    }
}
