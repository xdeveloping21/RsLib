package com.rs.lib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsSchedulerTest {

    private final List<LogRecord> logs = new ArrayList<>();
    private FakeBackend backend;
    private RsScheduler scheduler;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        backend = new FakeBackend();
        scheduler = new RsScheduler(logger, "TestPlugin", () -> true, backend);
    }

    // ---------------------------------------------------------------- one-shot

    @Test
    void everyOneShotKindLogsItsErrorOnceWithThePluginAndTheStackTrace() {
        Entity entity = entity();
        Location location = new Location(world(), 1, 2, 3);
        List<Function<Runnable, TaskHandle>> kinds = List.of(
                scheduler::sync,
                scheduler::async,
                task -> scheduler.syncLater(5, task),
                task -> scheduler.asyncLater(5, task),
                task -> scheduler.entity(entity, task),
                task -> scheduler.entity(entity, task, () -> { }),
                task -> scheduler.entityLater(entity, 5, task),
                task -> scheduler.at(location, task));
        for (Function<Runnable, TaskHandle> kind : kinds) {
            logs.clear();
            TaskHandle handle = kind.apply(boom());
            FakeTask scheduled = backend.last();
            assertSame(scheduled, handle, "the handle is the scheduler's own");

            scheduled.fire();

            assertEquals(1, logs.size());
            LogRecord record = logs.get(0);
            assertEquals(Level.SEVERE, record.getLevel());
            assertTrue(record.getMessage().contains("TestPlugin"), record.getMessage());
            assertTrue(record.getMessage().contains("IllegalStateException: kaboom"), record.getMessage());
            assertNotNull(record.getThrown(), "the stack trace is logged");
            assertFalse(handle.isCancelled(), "a one-shot task is only logged");
        }
    }

    @Test
    void theRetiredCallbackIsGuardedToo() {
        scheduler.entity(entity(), () -> { }, boom());
        backend.last().retired.run();
        assertEquals(1, logs.size());
    }

    @Test
    void aTaskThatDoesNotThrowLogsNothing() {
        AtomicInteger runs = new AtomicInteger();
        scheduler.sync(runs::incrementAndGet);
        backend.last().fire();
        TaskHandle timer = scheduler.timer(1, 1, runs::incrementAndGet);
        backend.last().fire();
        backend.last().fire();

        assertEquals(3, runs.get());
        assertTrue(logs.isEmpty());
        assertFalse(timer.isCancelled());
    }

    // ---------------------------------------------------------------- repeating

    @Test
    void everyRepeatingKindIsCancelledAfterItsFirstErrorAndLogsOnce() {
        Entity entity = entity();
        List<Function<Runnable, TaskHandle>> kinds = List.of(
                task -> scheduler.timer(0, 20, task),
                task -> scheduler.timerAsync(0, 20, task),
                task -> scheduler.entityTimer(entity, 0, 20, task));
        for (Function<Runnable, TaskHandle> kind : kinds) {
            logs.clear();
            AtomicInteger runs = new AtomicInteger();
            TaskHandle handle = kind.apply(() -> {
                if (runs.incrementAndGet() == 2) {
                    throw new IllegalStateException("kaboom");
                }
            });
            FakeTask scheduled = backend.last();

            scheduled.fire();
            assertFalse(handle.isCancelled());
            scheduled.fire();
            assertTrue(handle.isCancelled(), "a repeating task that throws is cancelled");
            scheduled.fire();

            assertEquals(2, runs.get(), "it never runs again");
            assertEquals(1, logs.size(), "logged once");
            assertTrue(logs.get(0).getMessage().contains("TestPlugin"), logs.get(0).getMessage());
            assertTrue(logs.get(0).getMessage().contains("cancelled"), logs.get(0).getMessage());
            assertNotNull(logs.get(0).getThrown());
        }
    }

    @Test
    void aThrowBeforeTheHandleIsKnownStillCancels() {
        backend.runAtOnce = true;
        TaskHandle handle = scheduler.timerAsync(1, 1, boom());

        assertTrue(handle.isCancelled());
        assertEquals(1, logs.size());
    }

    @Test
    void cancellingByHandWorksAsBefore() {
        TaskHandle handle = scheduler.timer(1, 1, () -> { });
        handle.cancel();
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertTrue(logs.isEmpty());
    }

    // ---------------------------------------------------------------- fakes

    private static Runnable boom() {
        return () -> {
            throw new IllegalStateException("kaboom");
        };
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(RsSchedulerTest.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    private static Entity entity() {
        return (Entity) Proxy.newProxyInstance(RsSchedulerTest.class.getClassLoader(), new Class<?>[] {Entity.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    /** A task the test runs by hand; a cancelled one no longer runs, like on both schedulers. */
    private static final class FakeTask implements TaskHandle {

        final Runnable task;
        final Runnable retired;
        private boolean cancelled;

        FakeTask(Runnable task, Runnable retired) {
            this.task = task;
            this.retired = retired;
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

    /** Keeps every task instead of running it; {@link #runAtOnce} runs it once while scheduling. */
    private static final class FakeBackend implements RsScheduler.Backend {

        final List<FakeTask> tasks = new ArrayList<>();
        boolean runAtOnce;

        FakeTask last() {
            return tasks.get(tasks.size() - 1);
        }

        private TaskHandle add(Runnable task, Runnable retired) {
            FakeTask fake = new FakeTask(task, retired);
            tasks.add(fake);
            if (runAtOnce) {
                fake.fire();
            }
            return fake;
        }

        @Override
        public TaskHandle sync(Runnable task) {
            return add(task, null);
        }

        @Override
        public TaskHandle async(Runnable task) {
            return add(task, null);
        }

        @Override
        public TaskHandle syncLater(long delay, Runnable task) {
            return add(task, null);
        }

        @Override
        public TaskHandle asyncLater(long delay, Runnable task) {
            return add(task, null);
        }

        @Override
        public TaskHandle timer(long delay, long period, Runnable task) {
            return add(task, null);
        }

        @Override
        public TaskHandle timerAsync(long delay, long period, Runnable task) {
            return add(task, null);
        }

        @Override
        public boolean ownsHere(Entity entity) {
            return true;
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task) {
            return add(task, null);
        }

        @Override
        public TaskHandle entity(Entity entity, Runnable task, Runnable retired) {
            return add(task, retired);
        }

        @Override
        public TaskHandle entityLater(Entity entity, long delay, Runnable task) {
            return add(task, null);
        }

        @Override
        public TaskHandle entityTimer(Entity entity, long delay, long period, Runnable task) {
            return add(task, null);
        }

        @Override
        public boolean ownsHere(Location location) {
            return true;
        }

        @Override
        public TaskHandle at(Location location, Runnable task) {
            return add(task, null);
        }

        @Override
        public void cancelAll() {
            tasks.forEach(FakeTask::cancel);
        }
    }
}
