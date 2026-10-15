package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The directory and its file; the event listener and the scheduling need a running server. */
class PlayerNamesTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final Logger LOG = Logger.getLogger("PlayerNamesTest");

    @TempDir
    Path dir;

    @Test
    void lookupsIgnoreCase() {
        NameDirectory names = new NameDirectory(100);
        names.remember(A, "Steve", 10);
        assertEquals(Optional.of("Steve"), names.name(A));
        assertEquals(Optional.of(A), names.uuid("steve"));
        assertEquals(Optional.of(A), names.uuid("STEVE"));
        assertEquals(Optional.empty(), names.uuid("Alex"));
        assertEquals(Optional.empty(), names.name(B));
        assertEquals(Optional.empty(), names.uuid(null));
    }

    @Test
    void newestSightingWins() {
        NameDirectory names = new NameDirectory(100);
        names.remember(A, "Steve", 10);
        assertFalse(names.remember(A, "OldName", 5), "an older sighting changes nothing");
        assertEquals(Optional.of("Steve"), names.name(A));
        names.remember(B, "steve", 20);
        assertEquals(Optional.of(B), names.uuid("Steve"), "the name now belongs to B");
        names.remember(A, "Steve", 15);
        assertEquals(Optional.of(B), names.uuid("Steve"), "A was seen with it before B");
        names.remember(B, "Builder", 30);
        assertEquals(Optional.of(A), names.uuid("Steve"), "B renamed: the name goes back to A");
        assertEquals(Optional.of(B), names.uuid("builder"));
        names.remember(A, "Alex", 40);
        assertEquals(Optional.empty(), names.uuid("Steve"));
        assertEquals(Optional.of(A), names.uuid("alex"));
    }

    @Test
    void invalidNamesAreRefused() {
        NameDirectory names = new NameDirectory(100);
        assertFalse(names.remember(A, "", 1));
        assertFalse(names.remember(A, null, 1));
        assertFalse(names.remember(A, "x".repeat(65), 1));
        assertFalse(names.remember(A, "tab\there", 1));
        assertFalse(names.remember(null, "Steve", 1));
        assertTrue(names.remember(A, "x".repeat(64), 1));
    }

    @Test
    void oldestAreDroppedPastTheLimit() {
        NameDirectory names = new NameDirectory(3);
        names.remember(A, "a", 1);
        names.remember(B, "b", 3);
        names.remember(C, "c", 2);
        names.remember(UUID.randomUUID(), "d", 4);
        names.prune();
        assertEquals(3, names.size());
        assertEquals(Optional.empty(), names.name(A));
        assertEquals(Optional.empty(), names.uuid("a"));
        assertEquals(Optional.of(C), names.uuid("c"));
    }

    /** Trampa: same TSV format as SnLib, unreadable lines skipped and counted. */
    @Test
    void readsAndWritesTheTsvFormat() throws Exception {
        Path file = dir.resolve("player-names.tsv");
        Files.writeString(file, "﻿" + A + "\tSteve\t1700000000000\n"
                + "not a line\n"
                + B + "\tAlex\n"
                + C + "\t\t5\n"
                + "zzz\tBad\t5\n"
                + B + "\tAlex\t-1\n"
                + "\n"
                + B + "\tAlex Two\t1700000000500\r\n", StandardCharsets.UTF_8);
        NameDirectory names = new NameDirectory(100);
        NameDirectory.LoadResult result = names.load(file);
        assertEquals(2, result.read());
        assertEquals(5, result.skipped());
        assertEquals(Optional.of("Alex Two"), names.name(B));
        assertFalse(names.isDirty(), "loading alone does not need a save");

        Path copy = dir.resolve("copy.tsv");
        names.save(copy);
        assertFalse(Files.exists(dir.resolve("copy.tsv.tmp")));
        List<String> lines = Files.readAllLines(copy, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.contains(A + "\tSteve\t1700000000000"));
        assertTrue(lines.contains(B + "\tAlex Two\t1700000000500"));
        NameDirectory again = new NameDirectory(100);
        assertEquals(new NameDirectory.LoadResult(2, 0), again.load(copy));
        assertEquals(Optional.of(A), again.uuid("steve"));
    }

    @Test
    void loadingKeepsNewerSightingsAlreadyKnown() throws Exception {
        Path file = dir.resolve("player-names.tsv");
        Files.writeString(file, A + "\tOldName\t100\n");
        NameDirectory names = new NameDirectory(100);
        names.remember(A, "NewName", 200);
        names.load(file);
        assertEquals(Optional.of("NewName"), names.name(A));
        assertTrue(names.isDirty());
    }

    /** Trampa: RsLib owns the directory; SnLib's file seeds it once, on the first start only. */
    @Test
    void snlibFileIsImportedOnlyOnce() throws Exception {
        Path ours = dir.resolve("RsLib").resolve("player-names.tsv");
        Path snlib = dir.resolve("SnLib").resolve("player-names.tsv");
        Files.createDirectories(snlib.getParent());
        Files.writeString(snlib, A + "\tSteve\t100\n");
        Path noUsercache = dir.resolve("usercache.json");

        NameDirectory first = new NameDirectory(100);
        PlayerNames.loadOrImport(first, ours, snlib, noUsercache, LOG);
        assertEquals(Optional.of(A), first.uuid("Steve"));
        assertTrue(Files.exists(ours), "our file is written at once");
        assertFalse(first.isDirty());

        Files.writeString(snlib, B + "\tAlex\t200\n");
        NameDirectory second = new NameDirectory(100);
        PlayerNames.loadOrImport(second, ours, snlib, noUsercache, LOG);
        assertEquals(Optional.of(A), second.uuid("steve"));
        assertEquals(Optional.empty(), second.uuid("alex"), "SnLib's file is not read again");
    }

    @Test
    void firstStartWithNothingToImportStillWritesTheFile() {
        Path ours = dir.resolve("RsLib").resolve("player-names.tsv");
        NameDirectory names = new NameDirectory(100);
        PlayerNames.loadOrImport(names, ours, dir.resolve("none.tsv"), dir.resolve("none.json"), LOG);
        assertTrue(Files.exists(ours));
        assertEquals(0, names.size());
    }

    @Test
    void usercacheAddsOnlyUnknownPlayers() throws Exception {
        Path usercache = dir.resolve("usercache.json");
        Files.writeString(usercache, "[{\"name\":\"Steve\",\"uuid\":\"" + A + "\",\"expiresOn\":\"2026-11-01 10:00:00 +0000\"},"
                + "{\"name\":\"Alex\",\"uuid\":\"" + B + "\",\"expiresOn\":\"2026-11-01 10:00:00 +0000\"},"
                + "{\"name\":\"Broken\",\"uuid\":\"nope\"},"
                + "{\"name\":\"NoDate\",\"uuid\":\"" + C + "\"}]");
        NameDirectory names = new NameDirectory(100);
        names.remember(A, "SteveNow", 5);
        assertEquals(2, names.importUsercache(usercache, Long.MAX_VALUE));
        assertEquals(Optional.of("SteveNow"), names.name(A), "known players keep their entry");
        assertEquals(Optional.of(B), names.uuid("alex"));
        assertEquals(Optional.of(C), names.uuid("nodate"));
    }

    @Test
    void usercacheDatesBecomeLastSightings() {
        long expires = java.time.OffsetDateTime.parse("2026-11-01T10:00:00Z").toInstant().toEpochMilli();
        long thirtyDays = 30L * 24 * 60 * 60 * 1000;
        assertEquals(expires - thirtyDays, NameDirectory.lastSeen("2026-11-01 10:00:00 +0000", Long.MAX_VALUE));
        assertEquals(1_000L, NameDirectory.lastSeen("2026-11-01 10:00:00 +0000", 1_000L), "never in the future");
        assertEquals(0L, NameDirectory.lastSeen("yesterday", Long.MAX_VALUE));
        assertEquals(0L, NameDirectory.lastSeen(null, Long.MAX_VALUE));
    }
}
