package com.rs.lib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The shared ticker: same timing and order as one timer per job, one task in all, clean cancels. */
class SharedTickerTest {

    private final List<LogRecord> logs = new ArrayList<>();
    private Server server;
    private SharedTicker ticker;

    @BeforeEach
    void setUp() {
        server = new Server();
        ticker = new SharedTicker(logger(logs), "TestPlugin", server::repeatFromNextTick);
    }

    // ---------------------------------------------------------------- timing

    @Test
    void everyJobRunsWhenItsOwnTimerWouldHave() {
        // Jobs added at different ticks with different periods: each runs on the ticks a
        // timer(period, period) added at that tick would run on.
        List<String> ran = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        int[][] jobs = {{0, 20}, {0, 7}, {3, 20}, {5, 1}, {11, 40}, {12, 7}};
        int last = 100;
        for (int t = 0; t <= last; t++) {
            if (t > 0) {
                server.advance();
            }
            for (int j = 0; j < jobs.length; j++) {
                if (jobs[j][0] == t) {
                    String name = "job" + j;
                    ticker.add(jobs[j][1], () -> ran.add(server.tick + ":" + name));
                }
            }
        }
        for (int t = 1; t <= last; t++) {
            for (int j = 0; j < jobs.length; j++) {
                int added = jobs[j][0];
                int period = jobs[j][1];
                if (t > added && (t - added) % period == 0) {
                    expected.add(t + ":job" + j);
                }
            }
        }
        assertEquals(expected, ran);
    }

    @Test
    void jobsDueInTheSameTickRunInTheOrderTheyWereAdded() {
        List<String> ran = new ArrayList<>();
        ticker.add(2, () -> ran.add("a"));
        ticker.add(1, () -> ran.add("b"));
        ticker.add(2, () -> ran.add("c"));
        server.advance();
        server.advance();
        assertEquals(List.of("b", "a", "b", "c"), ran);
    }

    @Test
    void aPeriodBelowOneIsOne() {
        AtomicInteger runs = new AtomicInteger();
        ticker.add(0, runs::incrementAndGet);
        server.advance();
        server.advance();
        assertEquals(2, runs.get());
    }

    // ---------------------------------------------------------------- one task

    @Test
    void manyJobsUseOneTaskWhichStopsWithTheLastJob() {
        List<TaskHandle> handles = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            handles.add(ticker.add(20 + i % 3, () -> { }));
        }
        assertEquals(1, server.tasks.size(), "one scheduled task for 100 jobs");
        assertEquals(1, ticker.starts());
        assertTrue(ticker.ticking());

        handles.forEach(TaskHandle::cancel);
        assertFalse(ticker.ticking(), "no jobs, no task");
        assertTrue(server.tasks.get(0).cancelled);
        handles.forEach(handle -> assertTrue(handle.isCancelled()));

        ticker.add(5, () -> { });
        assertEquals(2, ticker.starts(), "started again for a new job");
    }

    @Test
    void aJobCancelledByAnEarlierJobOfTheSameTickDoesNotRun() {
        AtomicInteger second = new AtomicInteger();
        TaskHandle[] later = new TaskHandle[1];
        ticker.add(1, () -> later[0].cancel());
        later[0] = ticker.add(1, second::incrementAndGet);
        server.advance();
        assertEquals(0, second.get());
        assertTrue(later[0].isCancelled());
        assertEquals(1, ticker.size());
    }

    @Test
    void aJobAddedWhileTheTickerRunsStartsCountingFromThatTick() {
        List<Integer> ran = new ArrayList<>();
        TaskHandle[] self = new TaskHandle[1];
        self[0] = ticker.add(2, () -> {
            ticker.add(3, () -> ran.add(server.tick));
            self[0].cancel();
        });
        for (int i = 0; i < 8; i++) {
            server.advance();
        }
        assertEquals(List.of(5, 8), ran);
        assertTrue(ticker.ticking());
    }

    @Test
    void cancellingTheLastJobWhileTheTickerRunsStopsItAfterTheTick() {
        TaskHandle[] self = new TaskHandle[1];
        self[0] = ticker.add(1, () -> self[0].cancel());
        server.advance();
        assertFalse(ticker.ticking());
        assertEquals(0, ticker.size());
    }

    // ---------------------------------------------------------------- errors and shutdown

    @Test
    void aJobThatThrowsIsLoggedOnceAndCancelledAndTheOthersGoOn() {
        AtomicInteger good = new AtomicInteger();
        TaskHandle bad = ticker.add(1, () -> {
            throw new IllegalStateException("kaboom");
        });
        ticker.add(1, good::incrementAndGet);
        server.advance();
        server.advance();

        assertTrue(bad.isCancelled());
        assertEquals(2, good.get());
        assertEquals(1, logs.size());
        LogRecord record = logs.get(0);
        assertEquals(Level.SEVERE, record.getLevel());
        assertTrue(record.getMessage().contains("TestPlugin"), record.getMessage());
        assertTrue(record.getMessage().contains("IllegalStateException: kaboom"), record.getMessage());
        assertTrue(record.getMessage().contains("cancelled"), record.getMessage());
        assertNotNull(record.getThrown());
    }

    @Test
    void whenTheTickersTaskIsCancelledFromOutsideItsJobsAreGoneToo() {
        AtomicInteger runs = new AtomicInteger();
        TaskHandle job = ticker.add(1, runs::incrementAndGet);
        server.tasks.get(0).cancel();
        assertTrue(job.isCancelled(), "like a timer cancelled with every task of the plugin");

        AtomicInteger fresh = new AtomicInteger();
        ticker.add(1, fresh::incrementAndGet);
        server.advance();
        assertEquals(0, runs.get(), "the old job never comes back");
        assertEquals(1, fresh.get());
        assertEquals(1, ticker.size());
    }

    @Test
    void cancelAllCancelsEveryJobAndTheTask() {
        TaskHandle a = ticker.add(1, () -> { });
        TaskHandle b = ticker.add(9, () -> { });
        ticker.cancelAll();
        assertTrue(a.isCancelled());
        assertTrue(b.isCancelled());
        assertFalse(ticker.ticking());
        assertEquals(0, ticker.size());
    }

    @Test
    void aTickerThatCannotStartAddsNothing() {
        SharedTicker refused = new SharedTicker(logger(logs), "TestPlugin", runner -> {
            throw new IllegalStateException("plugin disabled");
        });
        assertThrows(IllegalStateException.class, () -> refused.add(5, () -> { }));
        assertEquals(0, refused.size());
    }

    // ---------------------------------------------------------------- through RsScheduler

    @Test
    void theSchedulerSharesOneTaskOnPaperAndKeepsEntityTasksOnFolia() {
        Backend paper = new Backend(server);
        RsScheduler scheduler = new RsScheduler(logger(logs), "TestPlugin", () -> true, paper, false);
        Entity entity = entity();
        for (int i = 0; i < 10; i++) {
            scheduler.sharedTimer(20, () -> { });
            scheduler.sharedEntityTimer(entity, 10, () -> { });
        }
        assertEquals(1, server.tasks.size(), "20 repeating jobs, one task");
        assertEquals(0, paper.entityTimers);
        scheduler.cancelAll();
        assertEquals(0, scheduler.ticker().size());

        Backend folia = new Backend(new Server());
        RsScheduler regionized = new RsScheduler(logger(logs), "TestPlugin", () -> true, folia, true);
        regionized.sharedEntityTimer(entity, 10, () -> { });
        regionized.sharedEntityTimer(entity, 10, () -> { });
        assertEquals(2, folia.entityTimers, "on Folia each player keeps a task on its own scheduler");
        assertEquals(0, regionized.ticker().size());
    }

    // ---------------------------------------------------------------- fakes

    private static Logger logger(List<LogRecord> into) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                into.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private static Entity entity() {
        return (Entity) Proxy.newProxyInstance(SharedTickerTest.class.getClassLoader(), new Class<?>[] {Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    /** A server that runs every live repeating task once per tick, from the tick after it was scheduled. */
    static final class Server {
        final List<Task> tasks = new ArrayList<>();
        int tick;

        TaskHandle repeatFromNextTick(Runnable runnable) {
            Task task = new Task(runnable);
            tasks.add(task);
            return task;
        }

        void advance() {
            tick++;
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.cancelled) {
                    task.runnable.run();
                }
            }
        }
    }

    static final class Task implements TaskHandle {
        final Runnable runnable;
        boolean cancelled;

        Task(Runnable runnable) {
            this.runnable = runnable;
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

    /** Only the timers matter here; everything else is never called. */
    static final class Backend implements RsScheduler.Backend {
        final Server server;
        int entityTimers;

        Backend(Server server) {
            this.server = server;
        }

        @Override
        public TaskHandle timer(long delay, long period, Runnable task) {
            assertEquals(1L, delay);
            assertEquals(1L, period);
            return server.repeatFromNextTick(task);
        }

        @Override
        public TaskHandle entityTimer(Entity entity, long delay, long period, Runnable task) {
            entityTimers++;
            return new Task(task);
        }

        @Override
        public TaskHandle sync(Runnable task) {
            throw new AssertionError();
        }

        @Override
        public TaskHandle async(Runnable task) {
            throw new AssertionError();
        }

        @Override
        public TaskHandle syncLater(long delay, Runnable task) {
            throw new AssertionError();
        }

        @Override
        public TaskHandle asyncLater(long delay, Runnable task) {
            throw new AssertionError();
        }

        @Override
        public TaskHandle timerAsync(long delay, long period, Runnable task) {
            throw new AssertionError();
        }

        @Override
        public boolean ownsHere(Entity entity) {
            return true;
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task) {
            throw new AssertionError();
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task, Runnable retired) {
            throw new AssertionError();
        }

        @Override
        public TaskHandle entityLater(Entity entity, long delay, Runnable task) {
            throw new AssertionError();
        }

        @Override
        public boolean ownsHere(Location location) {
            return true;
        }

        @Override
        public TaskHandle at(Location location, Runnable task) {
            throw new AssertionError();
        }

        @Override
        public void cancelAll() {
            server.tasks.forEach(Task::cancel);
        }
    }
}
