package com.rs.lib.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.rs.lib.scheduler.TaskHandle;

/** Calendar jobs: timing from the real clock, lag, replacement, catch-up and shutdown. */
class RsCronTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static long millis(String local) {
        return LocalDateTime.parse(local).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** A fake server: tasks wait until {@link #advance} moves the clock past them. */
    private static final class FakeHost implements RsCron.Host {

        final long[] now = {millis("2026-10-15T10:00:00")};
        final List<Timer> timers = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, Long> marks = new HashMap<>();
        boolean hasStore = true;
        final List<String> debug = new ArrayList<>();
        int online;
        java.util.function.UnaryOperator<String> papi = text -> text;
        /** How many real milliseconds one tick takes; 50 on a healthy server. */
        long millisPerTick = 50;
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

        final class Timer implements TaskHandle {
            final long dueAt;
            final Runnable task;
            boolean cancelled;

            Timer(long ticks, Runnable task) {
                this.dueAt = now[0] + ticks * millisPerTick;
                this.task = task;
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

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public TaskHandle later(long ticks, Runnable task) {
            assertTrue(ticks >= 1 && ticks <= RsCron.MAX_STEP_TICKS, "ticks " + ticks);
            Timer timer = new Timer(ticks, task);
            timers.add(timer);
            return timer;
        }

        @Override
        public void debug(Supplier<String> message) {
            debug.add(message.get());
        }

        @Override
        public int onlinePlayers() {
            return online;
        }

        @Override
        public String papi(String text) {
            return papi.apply(text);
        }

        @Override
        public RsCron.LastRuns lastRuns() {
            if (!hasStore) {
                return null;
            }
            return new RsCron.LastRuns() {
                @Override
                public long get(String id) {
                    return marks.getOrDefault(id, -1L);
                }

                @Override
                public void put(String id, long value) {
                    marks.put(id, value);
                }
            };
        }

        /** Moves the clock to {@code local}, firing every live task that comes due on the way. */
        void advance(String local) {
            long until = millis(local);
            while (true) {
                Timer next = null;
                for (Timer t : timers) {
                    if (!t.cancelled && t.dueAt <= until && (next == null || t.dueAt < next.dueAt)) {
                        next = t;
                    }
                }
                if (next == null) {
                    break;
                }
                next.cancelled = true;
                now[0] = Math.max(now[0], next.dueAt);
                next.task.run();
            }
            now[0] = until;
        }
    }

    private final FakeHost host = new FakeHost();
    private final RsCron cron = new RsCron(host, () -> host.now[0], UTC);
    private final List<String> runs = new ArrayList<>();

    private Runnable record(String what) {
        return () -> runs.add(what + "@" + java.time.Instant.ofEpochMilli(host.now[0]));
    }

    @Test
    void runsAtEachCalendarTimeAndPlansFromTheRealClock() {
        cron.schedule("payout", "0 12 * * *", record("payout"));
        assertEquals("2026-10-15T12:00Z", cron.nextRun("payout").toString());
        host.advance("2026-10-15T11:59:00");
        assertTrue(runs.isEmpty());
        host.advance("2026-10-17T00:00:00");
        assertEquals(List.of("payout@2026-10-15T12:00:00Z", "payout@2026-10-16T12:00:00Z"), runs);
    }

    @Test
    void aLaggingServerIsCorrectedByTheClock() {
        host.millisPerTick = 100; // 10 TPS: a plain tick count would run two hours late
        cron.schedule("noon", "0 12 * * *", record("noon"));
        host.advance("2026-10-15T13:00:00");
        assertEquals(1, runs.size());
        long ranAt = java.time.Instant.parse(runs.get(0).substring(runs.get(0).indexOf('@') + 1)).toEpochMilli();
        long late = ranAt - millis("2026-10-15T12:00:00");
        assertTrue(late >= 0, "never early");
        assertTrue(late <= 1_000, "about a second late at most, was " + late + " ms");
    }

    @Test
    void aJobWithTheSameIdReplacesTheOldOneAndCancelStopsIt() {
        cron.schedule("a", "hourly", record("old"));
        cron.schedule("a", "hourly :30", record("new"));
        host.advance("2026-10-15T11:59:00");
        assertEquals(List.of("new@2026-10-15T10:30:00Z", "new@2026-10-15T11:30:00Z"), runs);
        assertTrue(cron.cancel("a"));
        assertFalse(cron.cancel("a"));
        assertFalse(cron.isScheduled("a"));
        assertNull(cron.nextRun("a"));
        host.advance("2026-10-15T14:00:00");
        assertEquals(2, runs.size());
    }

    @Test
    void aFailingTaskIsLoggedAndKeepsItsSchedule() {
        cron.schedule("boom", "hourly", () -> {
            runs.add("boom");
            throw new IllegalStateException("broken");
        });
        host.advance("2026-10-15T12:30:00");
        assertEquals(2, runs.size());
        assertEquals(2, host.warnings.size());
    }

    @Test
    void invalidExpressionWarnsAndSchedulesNothing() {
        cron.schedule("x", "61 * * * *", record("x"));
        assertFalse(cron.isScheduled("x"));
        assertEquals(1, host.warnings.size());
        String warning = host.warnings.get(0);
        assertTrue(warning.startsWith("Cron job 'x': Cron expression '61 * * * *': minute"), warning);
        cron.schedule("y", "0 4 * *", record("y"));
        assertEquals("Cron job 'y': Cron expression '0 4 * *': expected 5 fields (minute hour day month day-of-week),"
                + " got 4; not scheduled.", host.warnings.get(1));
        assertTrue(warning.endsWith("; not scheduled."), warning);
        assertThrows(IllegalArgumentException.class, () -> cron.schedule(" ", "hourly", record("x")));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("61 * * * *"));
    }

    @Test
    void invalidExpressionLeavesAnEarlierJobUntouched() {
        cron.schedule("job", "hourly", record("job"));
        ZonedDateTime next = cron.nextRun("job");
        cron.create("job", "").catchUp(true).schedule(record("other"));
        assertEquals(List.of("Cron job 'job': Cron expression is empty; not scheduled."), host.warnings);
        assertTrue(cron.isScheduled("job"));
        assertEquals(next, cron.nextRun("job"));
        host.advance("2026-10-15T11:00:00");
        assertEquals(List.of("job@2026-10-15T11:00:00Z"), runs);
    }

    @Test
    void catchUpRunsOnceForMissedRunsAndANewInstallNeverDoes() {
        cron.create("daily", "daily 04:00").catchUp(true).schedule(record("first"));
        assertEquals(host.now[0], host.marks.get("daily"), "a new install only stores now");
        host.advance("2026-10-15T10:00:01");
        assertTrue(runs.isEmpty());

        // The server was off for three days: one catch-up run, then the normal schedule.
        host.marks.put("daily", millis("2026-10-12T04:00:00"));
        cron.create("daily", "daily 04:00").catchUp(true).schedule(record("again"));
        host.advance("2026-10-15T10:00:05");
        assertEquals(List.of("again@2026-10-15T10:00:01.050Z"), runs);
        assertEquals(millis("2026-10-15T10:00:01.050"), host.marks.get("daily"));
        assertEquals("2026-10-16T04:00Z", cron.nextRun("daily").toString());
    }

    @Test
    void catchUpWithoutAConfigWarnsOnceAndStillRuns() {
        host.hasStore = false;
        cron.create("a", "hourly").catchUp(true).schedule(record("a"));
        cron.create("b", "hourly").catchUp(true).schedule(record("b"));
        host.advance("2026-10-15T11:00:00");
        assertEquals(1, host.warnings.size());
        assertEquals(2, runs.size());
    }

    @Test
    void shutdownStopsEverythingAndLaterJobsAreIgnored() {
        cron.schedule("a", "hourly", record("a"));
        cron.shutdown();
        host.advance("2026-10-15T12:00:00");
        assertTrue(runs.isEmpty());
        cron.schedule("b", "hourly", record("b"));
        assertFalse(cron.isScheduled("b"));
        assertEquals(1, host.warnings.size());
    }

    // ---------------------------------------------------------------- conditions

    private long skips() {
        return host.debug.stream().filter(line -> line.contains("skipped")).count();
    }

    @Test
    void playerLimitsAreCheckedEachTimeTheJobIsDue() {
        host.online = 2;
        cron.create("envoy", "hourly").minPlayers(3).maxPlayers(5).schedule(record("envoy"));
        host.advance("2026-10-15T11:00:00");
        assertEquals(List.of(), runs);
        assertEquals(1, skips());
        assertTrue(host.debug.stream().anyMatch(line -> line.equals(
                "Cron job 'envoy' skipped: 2 players online, it needs at least 3.")), host.debug.toString());
        host.online = 3;
        host.advance("2026-10-15T12:00:00");
        host.online = 6;
        host.advance("2026-10-15T13:00:00");
        host.online = 5;
        host.advance("2026-10-15T14:00:00");
        assertEquals(List.of("envoy@2026-10-15T12:00:00Z", "envoy@2026-10-15T14:00:00Z"), runs);
        assertEquals(2, skips());
        // A skipped run is not retried: the job waits for its next time.
        assertEquals("2026-10-15T15:00Z", cron.nextRun("envoy").toString());
    }

    @Test
    void requirementsUsePlaceholderApiForTheServer() {
        String[] tps = {"17.5"};
        host.papi = text -> text.replace("%server_tps%", tps[0]);
        cron.create("koth", "hourly").requirements(List.of("%server_tps% >= 18", "%unknown|ok% = ok"))
                .schedule(record("koth"));
        host.advance("2026-10-15T11:00:00");
        tps[0] = "19.9";
        host.advance("2026-10-15T12:00:00");
        assertEquals(List.of("koth@2026-10-15T12:00:00Z"), runs);
        assertEquals(1, skips());
    }

    @Test
    void aRequirementWrittenWrongIsReportedAndNeverHolds() {
        cron.create("broken", "hourly").requirements(List.of("%server_tps% >=")).schedule(record("broken"));
        assertEquals(1, host.warnings.size());
        assertTrue(host.warnings.get(0).startsWith("Cron job 'broken': Requirement"), host.warnings.get(0));
        host.advance("2026-10-15T13:00:00");
        assertEquals(List.of(), runs);
        assertEquals(3, skips());
    }

    @Test
    void pluginConditionsAllHaveToHold() {
        boolean[] running = {true};
        cron.create("event", "hourly").condition(() -> !running[0]).condition(() -> true).schedule(record("event"));
        host.advance("2026-10-15T11:00:00");
        running[0] = false;
        host.advance("2026-10-15T12:00:00");
        assertEquals(List.of("event@2026-10-15T12:00:00Z"), runs);
        assertEquals(1, skips());
    }

    @Test
    void aConditionThatThrowsSkipsTheRunAndIsReported() {
        cron.create("odd", "hourly").condition(() -> {
            throw new IllegalStateException("broken");
        }).schedule(record("odd"));
        host.advance("2026-10-15T11:00:00");
        assertEquals(List.of(), runs);
        assertEquals(1, host.warnings.size());
        assertEquals(1, skips());
        assertTrue(cron.isScheduled("odd"));
    }

    @Test
    void aSkippedRunCountsAsDoneForCatchUp() {
        host.online = 0;
        host.marks.put("daily", millis("2026-10-14T03:00:00"));
        cron.create("daily", "0 4 * * *").catchUp(true).minPlayers(1).schedule(record("daily"));
        host.advance("2026-10-15T10:00:01");
        assertEquals(List.of(), runs);
        assertEquals(1, skips());
        assertTrue(host.marks.get("daily") >= millis("2026-10-15T10:00:00"), "the skipped run is marked as done");
    }

    @Test
    void withoutConditionsNothingIsAsked() {
        host.papi = text -> {
            throw new AssertionError("not asked");
        };
        host.online = -1;
        cron.schedule("plain", "hourly", record("plain"));
        host.advance("2026-10-15T11:00:00");
        assertEquals(List.of("plain@2026-10-15T11:00:00Z"), runs);
        assertEquals(0, skips());
    }
}
