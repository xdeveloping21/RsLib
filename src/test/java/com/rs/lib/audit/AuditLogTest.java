package com.rs.lib.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsDb;
import com.rs.lib.db.TestDbs;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.yml.YmlTestSupport.Captured;

class AuditLogTest {

    @TempDir
    Path dir;

    private final Captured log = new Captured();
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private final List<Long> periods = new ArrayList<>();
    private final List<RsDb> opened = new ArrayList<>();
    private final AtomicBoolean owns = new AtomicBoolean(true);
    private volatile AuditSettings settings = AuditSettings.DEFAULTS;
    private final UUID steve = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private RsDb db;

    @BeforeEach
    void open() {
        db = database(dir);
    }

    @AfterEach
    void close() {
        opened.forEach(RsDb::shutdown);
    }

    private RsDb database(Path folder) {
        RsDb created = TestDbs.sqlite(folder.toFile(), new FakeDbHost());
        opened.add(created);
        return created;
    }

    private AuditLog audit(String plugin, RsDb database) {
        return new AuditLog(null, log.logger, now::get, (delay, period, task) -> {
            periods.add(period);
            return new TaskHandle() {
                @Override
                public void cancel() {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
        }, () -> database, plugin, () -> settings, entity -> owns.get());
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private int rows() {
        return TestDbs.count(db, AuditStore.TABLE);
    }

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(AuditLogTest.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    @Test
    void entriesAreQueuedAndWrittenTogetherOnceTheIntervalPasses() {
        AuditLog audit = audit("Market", db);
        audit.entry("market-buy").actor(steve, "Steve").record();
        audit.entry("market-buy").actor(alex, "Alex").record();
        audit.entry("market-sell").actor(alex, "Alex").record();
        assertEquals(3, audit.queued());
        assertEquals(List.of(AuditLog.CHECK_PERIOD_TICKS, AuditLog.PURGE_PERIOD_TICKS), periods,
                "the first entry started the write check and the purge");
        audit.tick();
        assertEquals(3, audit.queued(), "not due yet");
        now.addAndGet(settings.flushSeconds() * 1000L);
        audit.tick();
        assertEquals(0, audit.queued(), "one batch took all of them");
        await(audit.flush());
        assertEquals(3, rows());
    }

    @Test
    void fiveHundredWaitingEntriesAreWrittenBeforeTheInterval() {
        AuditLog audit = audit("Market", db);
        for (int i = 0; i < AuditLog.BATCH_TRIGGER - 1; i++) {
            audit.entry("tick").record();
        }
        audit.tick();
        assertEquals(AuditLog.BATCH_TRIGGER - 1, audit.queued());
        audit.entry("tick").record();
        audit.tick();
        assertEquals(0, audit.queued());
        await(audit.flush());
        assertEquals(AuditLog.BATCH_TRIGGER, rows());
    }

    @Test
    void aFailedWriteKeepsTheEntriesForTheNextOne() {
        AuditLog audit = audit("Market", db);
        audit.entry("a").record();
        await(audit.flush());
        audit.entry("b").record();
        audit.entry("c").record();
        db.shutdown();
        assertThrows(AssertionError.class, () -> await(audit.flush()));
        assertEquals(2, audit.queued());
        assertEquals(1, log.warnings().size());
    }

    @Test
    void aQueryWritesTheQueueFirstAndFiltersAndPagesNewestFirst() {
        AuditLog audit = audit("Market", db);
        audit.entry("Market Buy").actor(steve, "Steve").target(alex, "Alex").money(4500).items("16x Diamond").record();
        now.addAndGet(1000);
        audit.entry("market_sell").actor(alex, "Alex").target(steve, "Steve").record();
        now.addAndGet(1000);
        audit.entry("market-buy").actor(alex, "Alex").details("own listing").record();
        now.addAndGet(1000);
        audit.entry("give").actor(UUID.randomUUID(), "Admin").target(alex, "Alex").record();
        long start = now.get() - 3000;

        AuditPage all = await(audit.find(AuditQuery.builder().build()));
        assertEquals(4, all.total());
        assertEquals(List.of("give", "market-buy", "market-sell", "market-buy"),
                all.entries().stream().map(AuditEntry::action).toList(), "newest first");

        AuditPage steves = await(audit.find(AuditQuery.builder().player(steve).build()));
        assertEquals(2, steves.total(), "what Steve did and what was done to him");
        assertEquals(1, await(audit.find(AuditQuery.builder().actor(steve).build())).total());
        assertEquals(2, await(audit.find(AuditQuery.builder().target(alex).build())).total());
        assertEquals(2, await(audit.find(AuditQuery.builder().actions("MARKET_BUY").build())).total());
        assertEquals(3, await(audit.find(AuditQuery.builder().actions("market-buy", "give").build())).total());
        assertEquals(2, await(audit.find(AuditQuery.builder()
                .from(Instant.ofEpochMilli(start + 1000)).to(Instant.ofEpochMilli(start + 3000)).build())).total());
        assertEquals(0, await(audit.find(AuditQuery.builder().plugin("Crates").build())).total());
        assertEquals(4, await(audit.find(AuditQuery.builder().plugin("market").build())).total());

        AuditPage second = await(audit.find(AuditQuery.builder().page(2).pageSize(3).build()));
        assertEquals(4, second.total());
        assertEquals(2, second.pages());
        assertEquals(1, second.entries().size());
        AuditEntry oldest = second.entries().get(0);
        assertEquals("Market", oldest.plugin());
        assertEquals(steve, oldest.actorId());
        assertEquals("Alex", oldest.targetName());
        assertEquals(0, new BigDecimal("4500").compareTo(oldest.money()));
        assertEquals("16x Diamond", oldest.items());
        assertFalse(oldest.hasLocation());
        assertEquals(start, oldest.createdAt());
    }

    @Test
    void actorsAndPlacesAreStoredAsTheyWere() {
        AuditLog audit = audit("Market", db);
        Player steveOnline = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> steve;
                    case "getName" -> "Steve";
                    case "getLocation" -> new Location(world("spawn"), 10.7, 64, -3.2);
                    default -> null;
                });
        ConsoleCommandSender console = (ConsoleCommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ConsoleCommandSender.class}, (proxy, method, args) -> null);
        audit.entry("one").actor(steveOnline).record();
        owns.set(false);
        audit.entry("two").actor(steveOnline).record();
        audit.entry("three").actor(console).at(new Location(world("nether"), 1, 2, 3)).record();
        audit.entry("four").at(new Location(null, 1, 2, 3)).details("x".repeat(5000)).actor(null, "n".repeat(40)).record();
        List<AuditEntry> entries = await(audit.find(AuditQuery.builder().build())).entries();

        AuditEntry one = entries.get(3);
        assertEquals("Steve", one.actorName());
        assertEquals("spawn", one.world());
        assertEquals(10.7, one.x());
        assertEquals(-3.2, one.z());
        assertFalse(entries.get(2).hasLocation(), "not read from a thread that does not own the player");
        AuditEntry three = entries.get(1);
        assertNull(three.actorId());
        assertEquals(AuditLog.CONSOLE, three.actorName());
        assertEquals("nether", three.world());
        AuditEntry four = entries.get(0);
        assertFalse(four.hasLocation(), "a place without a world is no place");
        assertEquals(AuditLog.MAX_TEXT, four.details().length());
        assertEquals(AuditLog.MAX_NAME, four.actorName().length());
    }

    @Test
    void actionsAreKeysAndABuilderRecordsOnce() {
        AuditLog audit = audit("Market", db);
        assertEquals("crate-keys-give", AuditLog.normalizeAction(" Crate Keys_Give "));
        assertThrows(IllegalArgumentException.class, () -> audit.entry(" "));
        assertThrows(IllegalArgumentException.class, () -> audit.entry("x".repeat(49)));
        assertThrows(IllegalArgumentException.class, () -> audit.entry("x").money(Double.NaN));
        AuditBuilder builder = audit.entry("x");
        builder.record();
        assertThrows(IllegalStateException.class, builder::record);
        assertEquals(1, audit.queued());
    }

    @Test
    void oldEntriesArePurgedAfterKeepDays() {
        AuditLog audit = audit("Market", db);
        AuditLog other = audit("Crates", db);
        audit.entry("old").record();
        other.entry("old").record();
        now.addAndGet(2 * 86_400_000L);
        audit.entry("new").record();
        await(audit.flush());
        await(other.flush());
        settings = new AuditSettings(1, 10);
        assertEquals(1, await(audit.purge()));
        assertEquals(2, rows(), "only this plugin's old entry");
        settings = new AuditSettings(0, 10);
        now.addAndGet(365 * 86_400_000L);
        assertEquals(0, await(other.purge()), "0 keeps them for ever");
        assertEquals(2, rows());
    }

    @Test
    void shutdownWritesWhatIsQueued() {
        AuditLog audit = audit("Market", db);
        audit.entry("a").record();
        audit.entry("b").record();
        audit.shutdown();
        assertEquals(2, rows());
    }

    @Test
    void aSearchMergesEveryPluginAndPagesTheWhole() throws Exception {
        AuditLog market = audit("Market", db);
        AuditLog crates = audit("Crates", database(Files.createDirectory(dir.resolve("crates"))));
        for (int i = 0; i < 3; i++) {
            market.entry("m" + i).actor(steve, "Steve").record();
            now.addAndGet(10);
            crates.entry("c" + i).target(steve, "Steve").record();
            now.addAndGet(10);
        }
        crates.entry("other").actor(alex, "Alex").record();
        AuditQuery first = AuditQuery.builder().player(steve).pageSize(4).build();
        AuditPage page = await(AuditLog.search(List.of(market, crates), first));
        assertEquals(6, page.total());
        assertEquals(List.of("c2", "m2", "c1", "m1"), page.entries().stream().map(AuditEntry::action).toList());
        AuditPage second = await(AuditLog.search(List.of(market, crates),
                AuditQuery.builder().player(steve).pageSize(4).page(2).build()));
        assertEquals(List.of("c0", "m0"), second.entries().stream().map(AuditEntry::action).toList());
        AuditPage onlyCrates = await(AuditLog.search(List.of(market, crates),
                AuditQuery.builder().plugin("CRATES").build()));
        assertEquals(4, onlyCrates.total());
    }

    @Test
    void readingALogThatNeverRecordedDoesNotCreateItsTable() throws Exception {
        RsDb empty = database(Files.createDirectory(dir.resolve("empty")));
        AuditLog idle = audit("Idle", empty);
        AuditPage page = await(AuditLog.search(List.of(idle), AuditQuery.builder().build()));
        assertEquals(0, page.total());
        assertTrue(periods.isEmpty(), "reading starts no timers");
        assertFalse(empty.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                statement -> statement.setString(1, AuditStore.TABLE), rows -> rows.next()).join());

        AuditLog before = audit("Market", db);
        before.entry("kept").record();
        await(before.flush());
        AuditLog afterRestart = audit("Market", db);
        assertEquals(1, await(afterRestart.find(AuditQuery.builder().build())).total(),
                "entries from before a restart are read without recording first");
    }
}
