package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Load on join, install on the main thread, save on quit, and the stale-load rules. */
class PlayerDataCacheTest {

    @TempDir
    Path dir;

    private final FakeDbHost host = new FakeDbHost();
    private final Map<UUID, Long> stored = new ConcurrentHashMap<>();
    private final List<UUID> saves = new CopyOnWriteArrayList<>();
    private final AtomicInteger loads = new AtomicInteger();
    private volatile CountDownLatch gate;
    private RsDb db;
    private PlayerDataCache<long[]> cache;

    @BeforeEach
    void open() {
        db = TestDbs.sqlite(dir.toFile(), host);
        db.bootstrap(Schema.of("coins", "uuid VARCHAR(36) PRIMARY KEY", "amount BIGINT NOT NULL")).join();
        cache = db.playerCache(this::load, (d, uuid, value) -> {
            saves.add(uuid);
            d.upsert("coins").keys("uuid", uuid).set("amount", value[0]).run();
        });
    }

    @AfterEach
    void close() {
        db.shutdown();
    }

    /** The loader joins a query of the same database: fine, it runs on the async pool. */
    private long[] load(RsDb d, UUID uuid) {
        loads.incrementAndGet();
        CountDownLatch wait = gate;
        if (wait != null) {
            try {
                wait.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Long amount = d.query("SELECT amount FROM coins WHERE uuid = ?", ps -> ps.setString(1, uuid.toString()),
                rows -> rows.next() ? rows.getLong(1) : null).join();
        if (amount == null && stored.containsKey(uuid)) {
            return null;
        }
        return new long[]{amount == null ? 0L : amount};
    }

    /** The first load is running (blocked on the gate): what happens now makes its result stale. */
    private void awaitLoaderStarted() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (loads.get() == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("The loader did not start");
            }
            Thread.onSpinWait();
        }
    }

    private void loadAndInstall(UUID uuid) {
        cache.load(uuid);
        host.runMainUntil(() -> cache.get(uuid) != null);
    }

    @Test
    void valueIsInstalledOnTheMainThread() {
        UUID uuid = UUID.randomUUID();
        db.update("INSERT INTO coins VALUES (?, 7)", ps -> ps.setString(1, uuid.toString())).join();
        cache.load(uuid);
        host.awaitQueued(1);
        assertNull(cache.get(uuid), "not visible before the main thread installs it");
        host.runMain();
        assertEquals(7L, cache.get(uuid)[0]);
        assertEquals(1, cache.size());
    }

    @Test
    void nullMeansNoDataAndIsNotInstalled() {
        UUID uuid = UUID.randomUUID();
        stored.put(uuid, 0L);
        cache.load(uuid);
        host.awaitQueued(1);
        host.runMain();
        assertNull(cache.get(uuid));
        assertEquals(0, cache.size());
        cache.load(uuid);
        host.awaitQueued(1);
        host.runMain();
        assertEquals(2, loads.get(), "a missing value is looked up again on the next load");
    }

    @Test
    void loadIsSkippedWhenLoadedOrInFlight() {
        UUID uuid = UUID.randomUUID();
        gate = new CountDownLatch(1);
        cache.load(uuid);
        cache.load(uuid);
        gate.countDown();
        host.runMainUntil(() -> cache.get(uuid) != null);
        cache.load(uuid);
        assertEquals(1, loads.get());
    }

    /** Trap: an invalidate during the load makes its result stale; it is discarded. */
    @Test
    void invalidateDuringALoadDiscardsIt() {
        UUID uuid = UUID.randomUUID();
        gate = new CountDownLatch(1);
        cache.load(uuid);
        awaitLoaderStarted();
        cache.invalidate(uuid);
        gate.countDown();
        host.awaitQueued(1);
        host.runMain();
        assertNull(cache.get(uuid));
    }

    /** Trap: a player who leaves during the load does not get a value installed afterwards. */
    @Test
    void quitDuringALoadDiscardsIt() {
        UUID uuid = UUID.randomUUID();
        gate = new CountDownLatch(1);
        cache.load(uuid);
        awaitLoaderStarted();
        host.quit(uuid);
        gate.countDown();
        host.awaitQueued(1);
        host.runMain();
        assertNull(cache.get(uuid));
        assertTrue(saves.isEmpty());
    }

    @Test
    void rejoinDuringAStaleLoadKeepsOnlyTheNewOne() {
        UUID uuid = UUID.randomUUID();
        gate = new CountDownLatch(1);
        cache.load(uuid);
        awaitLoaderStarted();
        host.quit(uuid);
        cache.load(uuid);
        gate.countDown();
        host.runMainUntil(() -> cache.get(uuid) != null);
        assertEquals(2, loads.get());
    }

    @Test
    void quitSavesOnlyDirtyValues() {
        UUID clean = UUID.randomUUID();
        UUID dirty = UUID.randomUUID();
        loadAndInstall(clean);
        loadAndInstall(dirty);
        cache.get(dirty)[0] = 5L;
        cache.markDirty(dirty);
        host.quit(clean);
        host.quit(dirty);
        assertEquals(List.of(dirty), saves);
        assertNull(cache.get(dirty));
        db.fence().join();
        assertEquals(1, TestDbs.count(db, "coins"));
    }

    @Test
    void markDirtyIgnoresPlayersNotLoaded() {
        UUID uuid = UUID.randomUUID();
        cache.markDirty(uuid);
        loadAndInstall(uuid);
        host.quit(uuid);
        assertTrue(saves.isEmpty());
    }

    @Test
    void invalidateDropsWithoutSaving() {
        UUID uuid = UUID.randomUUID();
        loadAndInstall(uuid);
        cache.markDirty(uuid);
        cache.invalidate(uuid);
        host.quit(uuid);
        assertNull(cache.get(uuid));
        assertTrue(saves.isEmpty());
    }

    @Test
    void saveAllWritesDirtyValuesKeepsThemLoadedAndWaitsForTheWrites() {
        UUID uuid = UUID.randomUUID();
        loadAndInstall(uuid);
        cache.get(uuid)[0] = 9L;
        cache.markDirty(uuid);
        cache.saveAll().join();
        assertEquals(9L, db.query("SELECT amount FROM coins", null, rows -> rows.next() ? rows.getLong(1) : -1L).join());
        assertNotNull(cache.get(uuid));
        cache.saveAll().join();
        assertEquals(1, saves.size(), "a saved value is clean until marked dirty again");
    }

    @Test
    void loadIsSkippedDuringShutdown() {
        host.shuttingDown = true;
        cache.load(UUID.randomUUID());
        assertEquals(0, loads.get());
    }

    @Test
    void loaderFailureIsLoggedAndCanBeRetried() {
        AtomicInteger calls = new AtomicInteger();
        PlayerDataCache<String> flaky = db.playerCache((d, uuid) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first");
            }
            return "ok";
        }, (d, uuid, value) -> { });
        UUID uuid = UUID.randomUUID();
        flaky.load(uuid);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (host.log.warnings().isEmpty() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(List.of("Could not load the data of player " + uuid), host.log.warnings());
        flaky.load(uuid);
        host.runMainUntil(() -> "ok".equals(flaky.get(uuid)));
    }

    @Test
    void saverFailureIsLoggedNotThrown() {
        PlayerDataCache<String> broken = db.playerCache((d, uuid) -> "v", (d, uuid, value) -> {
            throw new IllegalStateException("bug");
        });
        UUID uuid = UUID.randomUUID();
        broken.load(uuid);
        host.runMainUntil(() -> broken.get(uuid) != null);
        broken.markDirty(uuid);
        host.quit(uuid);
        assertEquals(List.of("Could not save the data of player " + uuid), host.log.warnings());
    }

    @Test
    void oneSharedJoinListener() {
        assertNotNull(PlayerDataCache.joinListener());
        assertSame(PlayerDataCache.joinListener(), PlayerDataCache.joinListener());
    }
}
