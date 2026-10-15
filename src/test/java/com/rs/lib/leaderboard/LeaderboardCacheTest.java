package com.rs.lib.leaderboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import com.rs.lib.db.FakeDbHost;
import com.rs.lib.db.RsFuture;
import com.rs.lib.db.TestDbs;
import com.rs.lib.leaderboard.LeaderboardCache.Entry;
import com.rs.lib.leaderboard.LeaderboardCache.Snapshot;
import com.rs.lib.scheduler.TaskHandle;

class LeaderboardCacheTest {

    /** A host whose timers the test fires by hand. */
    static final class FakeHost implements BoardHost {

        final List<String> warnings = new ArrayList<>();
        final List<Timer> timers = new ArrayList<>();
        Function<String, String> top;
        BiFunction<OfflinePlayer, String, String> position;
        String exposed;
        private final Logger logger = Logger.getAnonymousLogger();

        FakeHost() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    warnings.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public TaskHandle every(long periodTicks, Runnable task) {
            Timer timer = new Timer(periodTicks, task);
            timers.add(timer);
            return timer;
        }

        @Override
        public void debug(Supplier<String> message) {
        }

        @Override
        public boolean expose(String identifier, Function<String, String> top,
                BiFunction<OfflinePlayer, String, String> position) {
            this.exposed = identifier;
            this.top = top;
            this.position = position;
            return true;
        }

        Timer last() {
            return timers.get(timers.size() - 1);
        }
    }

    static final class Timer implements TaskHandle {

        final long period;
        final Runnable task;
        boolean cancelled;

        Timer(long period, Runnable task) {
            this.period = period;
            this.task = task;
        }

        void fire() {
            if (!cancelled) {
                task.run();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private final FakeDbHost db = new FakeDbHost();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final UUID d = UUID.randomUUID();

    private RsFuture<List<Entry>> done(Entry... rows) {
        return TestDbs.future(db, CompletableFuture.completedFuture(Arrays.asList(rows)), false);
    }

    private static OfflinePlayer offline(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(LeaderboardCacheTest.class.getClassLoader(),
                new Class<?>[] {OfflinePlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    // ---------------------------------------------------------------- snapshots

    @Test
    void rowsAreOrderedByValueThenNameWithNullRowsDropped() {
        Snapshot snapshot = Snapshot.of(Arrays.asList(
                new Entry(a, "zed", 10), null, new Entry(b, "amy", 10), new Entry(c, "bob", 30), new Entry(d, null, 5)));
        assertEquals(List.of("bob", "amy", "zed", ""), snapshot.top(10).stream().map(Entry::name).toList());
        assertEquals(4, snapshot.size());
        assertEquals(1, snapshot.positionOf(c));
        assertEquals(2, snapshot.positionOf(b));
        assertEquals(0, snapshot.positionOf(UUID.randomUUID()));
        assertEquals(0, snapshot.positionOf(null));
        assertEquals(10, snapshot.valueOf(a));
        assertEquals(0, snapshot.valueOf(UUID.randomUUID()));
    }

    @Test
    void aPlayerListedTwiceKeepsTheBetterPlace() {
        Snapshot snapshot = Snapshot.of(List.of(new Entry(a, "amy", 5), new Entry(b, "bob", 7), new Entry(a, "amy", 9)));
        assertEquals(2, snapshot.size());
        assertEquals(1, snapshot.positionOf(a));
        assertEquals(9, snapshot.valueOf(a));
        assertEquals(2, snapshot.positionOf(b));
    }

    @Test
    void equalRowsKeepTheirOrderAndNanGoesLast() {
        Entry first = new Entry(a, "same", 1);
        Entry second = new Entry(b, "same", 1);
        Entry broken = new Entry(c, "nan", Double.NaN);
        Snapshot snapshot = Snapshot.of(List.of(broken, first, second));
        assertEquals(List.of(first, second, broken), snapshot.top(3));
    }

    @Test
    void topIsBoundedAndEmptyIsShared() {
        Snapshot snapshot = Snapshot.of(List.of(new Entry(a, "amy", 1), new Entry(b, "bob", 2)));
        assertEquals(1, snapshot.top(1).size());
        assertEquals(2, snapshot.top(50).size());
        assertTrue(snapshot.top(0).isEmpty());
        assertTrue(snapshot.top(-3).isEmpty());
        assertSame(Snapshot.empty(), Snapshot.of(null));
        assertSame(Snapshot.empty(), Snapshot.of(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.top(2).add(new Entry(c, "x", 0)));
    }

    @Test
    void wholeValuesShowWithoutDecimals() {
        assertEquals("10", LeaderboardCache.formatValue(10.0));
        assertEquals("-3", LeaderboardCache.formatValue(-3));
        assertEquals("0", LeaderboardCache.formatValue(0));
        assertEquals("12.5", LeaderboardCache.formatValue(12.5));
        assertEquals("12345678.5", LeaderboardCache.formatValue(12345678.5));
        assertEquals("100000000000000000000", LeaderboardCache.formatValue(1e20));
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void periodsAreRoundedToTicksAndAtLeastOneSecond() {
        assertEquals(20, LeaderboardCache.periodTicks(Duration.ZERO));
        assertEquals(20, LeaderboardCache.periodTicks(Duration.ofMillis(300)));
        assertEquals(30, LeaderboardCache.periodTicks(Duration.ofMillis(1520)));
        assertEquals(1200, LeaderboardCache.periodTicks(Duration.ofMinutes(1)));
    }

    @Test
    void boardsReadEmptyUntilTheFirstResult() {
        FakeHost host = new FakeHost();
        LeaderboardCache cache = new LeaderboardCache(host, System::currentTimeMillis);
        CompletableFuture<List<Entry>> query = new CompletableFuture<>();
        cache.register("kills", Duration.ofSeconds(30), () -> TestDbs.future(db, query, false));
        assertEquals(600, host.last().period);
        host.last().fire();
        assertTrue(cache.getTop("kills", 10).isEmpty());
        assertEquals(0, cache.positionOf("kills", a));
        query.complete(List.of(new Entry(a, "amy", 3)));
        assertEquals(1, cache.positionOf("kills", a));
        assertEquals(3, cache.valueOf("kills", a));
        assertTrue(cache.getTop("nothing", 5).isEmpty());
    }

    @Test
    void aSupplierThatThrowsOrReturnsNullSkipsTheRefresh() {
        FakeHost host = new FakeHost();
        LeaderboardCache cache = new LeaderboardCache(host, System::currentTimeMillis);
        int[] mode = {0};
        cache.register("kills", Duration.ofSeconds(1), () -> switch (mode[0]) {
            case 0 -> done(new Entry(a, "amy", 1));
            case 1 -> throw new IllegalStateException("no database");
            default -> null;
        });
        host.last().fire();
        mode[0] = 1;
        host.last().fire();
        mode[0] = 2;
        host.last().fire();
        assertEquals(2, host.warnings.size(), host.warnings.toString());
        assertEquals(1, cache.positionOf("kills", a), "the last rows stay");
    }

    @Test
    void aRunningQueryHoldsBackTheNextUntilItIsStale() {
        FakeHost host = new FakeHost();
        long[] now = {0};
        LeaderboardCache cache = new LeaderboardCache(host, () -> now[0]);
        List<CompletableFuture<List<Entry>>> started = new ArrayList<>();
        cache.register("kills", Duration.ofSeconds(1), () -> {
            CompletableFuture<List<Entry>> query = new CompletableFuture<>();
            started.add(query);
            return TestDbs.future(db, query, false);
        });
        host.last().fire();
        host.last().fire();
        assertEquals(1, started.size());
        now[0] = LeaderboardCache.STALE_QUERY_MILLIS + 1;
        host.last().fire();
        assertEquals(2, started.size());
        // The newer result wins even when the older one arrives last.
        started.get(1).complete(List.of(new Entry(b, "bob", 2)));
        started.get(0).complete(List.of(new Entry(a, "amy", 1)));
        assertEquals(1, cache.positionOf("kills", b));
        assertEquals(0, cache.positionOf("kills", a));
    }

    @Test
    void aFailingQueryKeepsTheRowsAndWarnsOncePerStreak() {
        FakeHost host = new FakeHost();
        LeaderboardCache cache = new LeaderboardCache(host, System::currentTimeMillis);
        boolean[] fail = {false};
        cache.register("kills", Duration.ofSeconds(1), () -> fail[0]
                ? TestDbs.future(db, CompletableFuture.failedFuture(new IllegalStateException("down")), false)
                : done(new Entry(a, "amy", 1)));
        host.last().fire();
        fail[0] = true;
        host.last().fire();
        host.last().fire();
        assertEquals(1, host.warnings.size());
        assertEquals(1, cache.positionOf("kills", a));
        fail[0] = false;
        host.last().fire();
        fail[0] = true;
        host.last().fire();
        assertEquals(2, host.warnings.size());
    }

    @Test
    void registeringAgainReplacesAndUnregisterStops() {
        FakeHost host = new FakeHost();
        LeaderboardCache cache = new LeaderboardCache(host, System::currentTimeMillis);
        cache.register("kills", Duration.ofSeconds(1), () -> done(new Entry(a, "amy", 1)));
        Timer old = host.last();
        cache.register("kills", Duration.ofSeconds(2), () -> done(new Entry(b, "bob", 1)));
        assertTrue(old.cancelled);
        old.task.run();
        assertEquals(0, cache.positionOf("kills", a), "the replaced board's timer does nothing");
        host.last().fire();
        assertEquals(1, cache.positionOf("kills", b));
        cache.unregister("kills");
        assertTrue(host.last().cancelled);
        assertTrue(cache.getTop("kills", 3).isEmpty());
        cache.shutdown();
        cache.register("kills", Duration.ofSeconds(1), () -> done());
        assertEquals(2, host.timers.size(), "nothing registers after shutdown");
    }

    // ---------------------------------------------------------------- placeholders

    @Test
    void placeholdersSplitTheBoardNameFromTheEnd() {
        FakeHost host = new FakeHost();
        LeaderboardCache cache = new LeaderboardCache(host, System::currentTimeMillis);
        cache.register("kills_weekly", Duration.ofSeconds(1), () -> done(new Entry(a, "amy", 12), new Entry(b, "bob", 2.5)));
        host.last().fire();
        assertTrue(cache.exposePlaceholders("rsrankup"));
        assertEquals("rsrankup", host.exposed);
        assertEquals("amy", host.top.apply("kills_weekly_1_name"));
        assertEquals("12", host.top.apply("kills_weekly_1_value"));
        assertEquals("2.5", host.top.apply("kills_weekly_2_VALUE"));
        assertEquals("", host.top.apply("kills_weekly_3_name"), "a place without a row is empty");
        assertEquals("amy", host.top.apply("KILLS_WEEKLY_1_name"), "board names match ignoring case");
        assertNull(host.top.apply("kills_weekly_1_uuid"));
        assertNull(host.top.apply("kills_weekly_x_name"));
        assertNull(host.top.apply("kills_weekly_0_name"));
        assertNull(host.top.apply("deaths_1_name"));
        assertNull(host.top.apply("1_name"));
        assertNull(host.top.apply("kills_weekly_1_"));
        assertEquals("2", host.position.apply(offline(b), "kills_weekly"));
        assertEquals("0", host.position.apply(offline(c), "kills_weekly"));
        assertNull(host.position.apply(offline(a), "deaths"));
        assertThrows(IllegalArgumentException.class, () -> cache.exposePlaceholders(" "));
        assertFalse(host.warnings.size() > 0);
    }
}
