package com.rs.lib.snapshot;

import static com.rs.lib.snapshot.SnapKit.file;
import static com.rs.lib.snapshot.SnapKit.stack;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.snapshot.SnapKit.FakePlayer;
import com.rs.lib.snapshot.SnapKit.Host;

/**
 * Saving, restoring and the file protocol, with a fake host whose threads are queues. What
 * stays untested without a server: Paper's item bytes, potion effect types, the asynchronous
 * teleport of {@code LOCATION} and the real schedulers (Paper and Folia).
 */
class SnapshotsTest {

    @TempDir
    Path folder;

    private Host host;
    private Snapshots snapshots;
    private FakePlayer p;

    @BeforeEach
    void setUp() {
        host = new Host(folder);
        snapshots = new Snapshots(host);
        p = new FakePlayer();
        p.slots[0] = stack("sword", 1);
        p.slots[7] = stack("bread", 300);
        p.cursor = stack("dirt", 3);
        p.level = 12;
    }

    private Path yml(String key) {
        return file(folder, p.id, key + ".yml");
    }

    private Path claimed(String key) {
        return file(folder, p.id, key + ".yml.restoring");
    }

    private static boolean done(CompletableFuture<Boolean> future) {
        assertTrue(future.isDone(), "the future should be complete");
        return future.join();
    }

    @Test
    void saveWritesTheFileAndKeepsThePlayer() throws IOException {
        assertTrue(done(snapshots.save(p.player, "arena")));
        assertTrue(Files.exists(yml("arena")));
        assertTrue(SnapKit.read(yml("arena")).startsWith("format: 1\n"));
        assertEquals(3, p.itemCount(), "a plain save does not clear");
        assertEquals(1, p.closes, "the open inventory is closed before the capture");
        assertTrue(runMainAndGet(snapshots.exists(p.id, "arena")));
    }

    private boolean runMainAndGet(CompletableFuture<Boolean> future) {
        host.runAll();
        return done(future);
    }

    @Test
    void existsAndDeleteCompleteOnTheMainThread() {
        done(snapshots.save(p.player, "arena"));
        CompletableFuture<Boolean> exists = snapshots.exists(p.id, "arena");
        assertFalse(exists.isDone(), "completes on the main thread");
        assertTrue(runMainAndGet(exists));
        assertTrue(runMainAndGet(snapshots.delete(p.id, "arena")));
        assertFalse(Files.exists(yml("arena")));
        assertFalse(runMainAndGet(snapshots.exists(p.id, "arena")));
        assertFalse(runMainAndGet(snapshots.delete(p.id, "arena")));
    }

    @Test
    void withoutOverwriteAnExistingKeyIsLeftAlone() throws IOException {
        done(snapshots.save(p.player, "arena"));
        String before = SnapKit.read(yml("arena"));
        p.slots[0] = null;
        assertFalse(done(snapshots.saveAndClear(p.player, "arena")));
        assertEquals(before, SnapKit.read(yml("arena")));
        assertEquals(2, p.itemCount(), "nothing was cleared");

        assertTrue(done(snapshots.save(p.player, "arena", SnapshotOptions.defaults().withOverwrite(true))));
        assertFalse(SnapKit.read(yml("arena")).contains("sword"));
    }

    /** A claimed ({@code .restoring}) file counts as existing, so a save never overwrites it by accident. */
    @Test
    void aClaimedFileCountsAsExisting() throws IOException {
        Files.createDirectories(claimed("arena").getParent());
        Files.writeString(claimed("arena"), "format: 1");
        assertFalse(done(snapshots.save(p.player, "arena")));
        assertTrue(runMainAndGet(snapshots.exists(p.id, "arena")));
    }

    @Test
    void saveAndClearThenRestoreGivesEverythingBack() {
        assertTrue(done(snapshots.saveAndClear(p.player, "staff")));
        assertEquals(0, p.itemCount());
        assertEquals(0, p.level);
        p.slots[3] = stack("staff-wand", 1);

        assertTrue(done(snapshots.restore(p.player, "staff")));
        assertEquals(stack("sword", 1), p.slots[0]);
        assertEquals(stack("bread", 300), p.slots[7]);
        assertEquals(stack("dirt", 3), p.slots[1], "the stored cursor goes into the inventory");
        assertNull(p.slots[3], "restoring replaces the inventory");
        assertEquals(12, p.level);
        assertFalse(Files.exists(yml("staff")));
        assertFalse(Files.exists(claimed("staff")));
        assertFalse(done(snapshots.restore(p.player, "staff")), "a snapshot is applied only once");
    }

    /** Trampa: the file is claimed by renaming it to .restoring before it is read. */
    @Test
    void restoreClaimsTheFileBeforeReadingIt() {
        done(snapshots.saveAndClear(p.player, "staff"));
        host.owns = false;
        CompletableFuture<Boolean> restore = snapshots.restore(p.player, "staff");
        assertFalse(Files.exists(yml("staff")));
        assertTrue(Files.exists(claimed("staff")), "claimed while waiting for the player's thread");
        assertEquals(0, p.itemCount());
        host.runAll();
        assertTrue(done(restore));
        assertEquals(3, p.itemCount());
        assertFalse(Files.exists(claimed("staff")));
    }

    /** Trampa: a .restoring file left by a crash is restored normally next time. */
    @Test
    void aClaimLeftByACrashIsRestored() throws IOException {
        done(snapshots.saveAndClear(p.player, "staff"));
        Files.move(yml("staff"), claimed("staff"));
        assertTrue(done(snapshots.restore(p.player, "staff")));
        assertEquals(3, p.itemCount());
        assertFalse(Files.exists(claimed("staff")));
    }

    /** Trampa: when the player left before the claim was applied, the claim is undone. */
    @Test
    void aPlayerWhoLeftGetsTheClaimUndone() {
        done(snapshots.saveAndClear(p.player, "staff"));
        host.owns = false;
        host.present = false;
        assertFalse(runMainAndGet(snapshots.restore(p.player, "staff")));
        assertTrue(Files.exists(yml("staff")));
        assertFalse(Files.exists(claimed("staff")));
    }

    /** Folia: the player's task retires (they left); the claim is undone and the future still completes. */
    @Test
    void aRetiredPlayerTaskUndoesTheClaim() {
        done(snapshots.saveAndClear(p.player, "staff"));
        host.owns = false;
        CompletableFuture<Boolean> restore = snapshots.restore(p.player, "staff");
        host.retirePlayerTasks();
        host.present = false;
        assertFalse(runMainAndGet(restore));
        assertTrue(Files.exists(yml("staff")));
        assertFalse(Files.exists(claimed("staff")));
    }

    @Test
    void aDeadPlayerIsNotRestored() {
        done(snapshots.saveAndClear(p.player, "staff"));
        p.dead = true;
        assertFalse(done(snapshots.restore(p.player, "staff")));
        assertEquals(0, p.itemCount());
        assertTrue(Files.exists(yml("staff")));
    }

    /** All or nothing: an unreadable item applies nothing and keeps the file. */
    @Test
    void anUnreadableSnapshotIsKeptAndNothingApplied() throws IOException {
        done(snapshots.saveAndClear(p.player, "staff"));
        Files.writeString(yml("staff"), SnapKit.read(yml("staff")).replace("bread*300", "BAD"));
        p.slots[3] = stack("staff-wand", 1);
        assertFalse(done(snapshots.restore(p.player, "staff")));
        assertEquals(stack("staff-wand", 1), p.slots[3]);
        assertEquals(1, p.itemCount());
        assertTrue(Files.exists(yml("staff")));
        assertFalse(Files.exists(claimed("staff")));
        assertTrue(host.logged("cannot be read"));
    }

    @Test
    void anItemThatCannotBeWrittenSavesNothingAndClearsNothing() {
        p.slots[2] = stack("UNWRITABLE", 1);
        assertFalse(done(snapshots.saveAndClear(p.player, "staff")));
        assertEquals(4, p.itemCount());
        assertFalse(Files.exists(yml("staff")));
    }

    /** A cleared player whose snapshot cannot be written gets everything back, plus what they got since. */
    @Test
    void aFailedWriteGivesTheClearedPlayerEverythingBack() throws IOException {
        Files.writeString(folder.resolve(p.id.toString()), "a file where the folder should be");
        assertFalse(done(snapshots.saveAndClear(p.player, "staff")));
        assertEquals(stack("sword", 1), p.slots[0]);
        assertEquals(stack("bread", 300), p.slots[7]);
        assertEquals(12, p.level);
        assertEquals(3, p.itemCount());
        assertTrue(host.logged("got back"));
        assertFalse(runMainAndGet(snapshots.exists(p.id, "staff")), "nothing waits in memory");
    }

    @Test
    void aFailedWriteWithoutClearIsDropped() throws IOException {
        Files.writeString(folder.resolve(p.id.toString()), "a file where the folder should be");
        assertFalse(done(snapshots.save(p.player, "staff")));
        assertEquals(3, p.itemCount());
        assertFalse(runMainAndGet(snapshots.exists(p.id, "staff")));
    }

    /**
     * A cleared player who left before getting the items back: the snapshot stays in memory,
     * is retried by the next operation and written at shutdown; if even that fails, its YAML is logged.
     */
    @Test
    void aSnapshotThatCannotBeGivenBackIsNeverLost() throws IOException {
        Path blocker = folder.resolve(p.id.toString());
        Files.writeString(blocker, "a file where the folder should be");
        host.asyncInline = false;
        CompletableFuture<Boolean> save = snapshots.saveAndClear(p.player, "staff");
        assertEquals(3, p.itemCount(), "the existence check comes first");
        host.async.poll().run();
        assertEquals(0, p.itemCount(), "captured and cleared in the same step");
        assertFalse(save.isDone());
        host.owns = false;
        host.present = false;
        host.runAll();
        assertFalse(done(save));
        assertTrue(host.logged("left before getting back"));
        assertTrue(runMainAndGet(snapshots.exists(p.id, "staff")), "still held in memory");

        host.shuttingDown = true;
        snapshots.shutdown();
        assertTrue(host.logged("Save the text below"));
        assertTrue(host.logs.stream().anyMatch(line -> line.contains("bread*300")));

        Files.delete(blocker);
        snapshots.flushPending();
        assertTrue(Files.exists(yml("staff")));
        assertTrue(SnapKit.read(yml("staff")).contains("bread*300"));
    }

    @Test
    void theNextOperationRetriesAWriteKeptInMemory() throws IOException {
        Path blocker = folder.resolve(p.id.toString());
        Files.writeString(blocker, "a file where the folder should be");
        host.asyncInline = false;
        CompletableFuture<Boolean> save = snapshots.saveAndClear(p.player, "staff");
        host.async.poll().run();
        host.owns = false;
        host.present = false;
        host.runAll();
        assertFalse(done(save));
        assertEquals(0, p.itemCount());

        Files.delete(blocker);
        host.owns = true;
        host.present = true;
        CompletableFuture<Boolean> restore = snapshots.restore(p.player, "staff");
        host.runAll();
        assertTrue(done(restore), "the kept snapshot was written first, then restored");
        assertEquals(3, p.itemCount());
        assertFalse(Files.exists(yml("staff")));
    }

    /** Operations on one player and key run in the order they were asked for. */
    @Test
    void operationsOnOneKeyRunInOrder() {
        host.asyncInline = false;
        CompletableFuture<Boolean> first = snapshots.save(p.player, "arena");
        CompletableFuture<Boolean> second = snapshots.saveAndClear(p.player, "arena");
        CompletableFuture<Boolean> restore = snapshots.restore(p.player, "arena");
        CompletableFuture<Boolean> after = snapshots.exists(p.id, "arena");
        host.runAll();
        assertTrue(done(first));
        assertFalse(done(second), "the first save already holds the key");
        assertTrue(done(restore), "the restore waited for the first write");
        assertFalse(done(after), "and the exists check for the restore");
        assertEquals(3, p.itemCount());
    }

    @Test
    void differentKeysDoNotWaitForEachOther() {
        host.asyncInline = false;
        CompletableFuture<Boolean> a = snapshots.save(p.player, "a");
        CompletableFuture<Boolean> b = snapshots.save(p.player, "b");
        assertEquals(2, host.async.size(), "both checks are queued at once");
        host.runAll();
        assertTrue(done(a));
        assertTrue(done(b));
    }

    /** At shutdown every waiting step runs on the shutting-down thread and pending saves are written. */
    @Test
    void shutdownRunsWhatIsWaitingAndWritesPendingSaves() {
        host.asyncInline = false;
        CompletableFuture<Boolean> save = snapshots.saveAndClear(p.player, "staff");
        assertFalse(save.isDone());
        host.shuttingDown = true;
        snapshots.shutdown();
        assertTrue(done(save));
        assertTrue(Files.exists(yml("staff")));
        assertEquals(0, p.itemCount());
        host.runAll();
        assertTrue(Files.exists(yml("staff")), "the queued task, run late, does nothing more");
    }

    /** During shutdown calls run on the calling thread, so onInnerDisable can restore and wait. */
    @Test
    void whileShuttingDownEverythingRunsInline() {
        host.asyncInline = false;
        CompletableFuture<Boolean> save = snapshots.saveAndClear(p.player, "staff");
        host.runAll();
        assertTrue(done(save));
        host.shuttingDown = true;
        assertTrue(done(snapshots.restore(p.player, "staff")));
        assertEquals(3, p.itemCount());
    }

    @Test
    void keysAreChecked() {
        for (String bad : new String[] {null, "", "Staff", "with space", "a.b", "a/b", "x".repeat(33), "ñ"}) {
            assertThrows(IllegalArgumentException.class, () -> snapshots.save(p.player, bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> snapshots.exists(p.id, bad), String.valueOf(bad));
        }
        for (String good : new String[] {"a", "staff", "arena_2", "pvp-1", "x".repeat(32), "0"}) {
            assertTrue(Snapshots.validKey(good), good);
        }
    }
}
