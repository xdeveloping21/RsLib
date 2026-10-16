package com.rs.lib.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.db.internal.MainHops;

/** The main-thread semantics of {@link RsFuture} that plugins rely on. */
class RsFutureTest {

    private final FakeDbHost host = new FakeDbHost();
    private final CompletableFuture<String> source = new CompletableFuture<>();
    private final RsFuture<String> future = TestDbs.future(host, source, false);
    private final List<String> events = new CopyOnWriteArrayList<>();

    @AfterEach
    void releaseHops() {
        MainHops.release(host.owner);
    }

    /** Trap: thenSync returns the same future; two calls are siblings, not a sequence. */
    @Test
    void thenSyncReturnsTheSameFutureAndSiblingsEachRun() {
        assertSame(future, future.thenSync(v -> events.add("a:" + v)));
        assertSame(future, future.thenSync(v -> events.add("b:" + v)));
        source.complete("x");
        assertTrue(events.isEmpty(), "nothing runs off the main thread");
        assertEquals(2, host.runMain());
        assertEquals(2, events.size());
        assertTrue(events.containsAll(List.of("a:x", "b:x")));
    }

    /** Trap: a result that arrives after the plugin was disabled is dropped. */
    @Test
    void thenSyncDropsTheValueWhenThePluginIsDisabled() {
        future.thenSync(events::add);
        host.enabled = false;
        source.complete("x");
        assertEquals(0, host.queued());
        assertTrue(events.isEmpty());
        assertTrue(host.log.warnings().isEmpty());
    }

    /** Trap: a failure is only logged, once per thenSync, and the consumer does not run. */
    @Test
    void thenSyncLogsAFailureAndSkipsTheConsumer() {
        future.thenSync(events::add);
        source.completeExceptionally(new CompletionException(new SQLException("no such table: x")));
        assertEquals(0, host.queued());
        assertTrue(events.isEmpty());
        assertEquals(List.of("Async task finished with an error: SQLException: no such table: x"), host.log.warnings());
        assertTrue(host.log.records.get(0).getThrown() instanceof SQLException);
    }

    @Test
    void thenSyncRaceWithDisableIsLogged() {
        future.thenSync(events::add);
        host.refuseSync = true;
        source.complete("x");
        assertEquals(List.of("Hop to main discarded: plugin disabled during scheduling"), host.log.warnings());
    }

    @Test
    void thenSyncCallbackFailureIsLogged() {
        future.thenSync(v -> {
            throw new IllegalStateException("bug");
        });
        source.complete("x");
        host.runMain();
        assertEquals(List.of("A thenSync callback failed"), host.log.warnings());
    }

    /** Trap: chainSync is a real sequence: the next step starts after the previous ran on the main thread. */
    @Test
    void chainSyncRunsStepsInOrder() {
        RsFuture<String> first = future.chainSync(v -> events.add("first:" + v));
        RsFuture<String> second = first.chainSync(v -> events.add("second:" + v));
        assertNotSame(future, first);
        assertNotSame(first, second);
        source.complete("x");
        assertEquals(1, host.runMain());
        assertEquals(List.of("first:x"), events);
        assertFalse(second.toCompletableFuture().isDone());
        assertEquals(1, host.runMain());
        assertEquals(List.of("first:x", "second:x"), events);
        assertEquals("x", second.join());
    }

    @Test
    void chainSyncPassesFailuresOn() {
        RsFuture<String> chained = future.chainSync(events::add);
        SQLException cause = new SQLException("down");
        source.completeExceptionally(new CompletionException(cause));
        CompletionException failure = assertThrows(CompletionException.class, chained::join);
        assertSame(cause, failure.getCause());
        assertTrue(events.isEmpty());
        assertEquals(0, host.queued());
    }

    @Test
    void chainSyncFailsWhenTheConsumerThrows() {
        IllegalStateException bug = new IllegalStateException("bug");
        RsFuture<String> chained = future.chainSync(v -> {
            throw bug;
        });
        source.complete("x");
        host.runMain();
        assertSame(bug, assertThrows(CompletionException.class, chained::join).getCause());
    }

    /** Trap: when the hop cannot happen (disabled or shutting down) the chain completes with the value. */
    @Test
    void chainSyncCompletesWithTheValueWhenTheHopIsImpossible() {
        host.enabled = false;
        RsFuture<String> disabled = future.chainSync(events::add);
        source.complete("x");
        assertEquals("x", disabled.join());

        FakeDbHost closing = new FakeDbHost();
        closing.shuttingDown = true;
        CompletableFuture<String> other = new CompletableFuture<>();
        RsFuture<String> teardown = TestDbs.future(closing, other, false).chainSync(events::add);
        other.complete("y");
        assertEquals("y", teardown.join());
        assertTrue(events.isEmpty());
    }

    @Test
    void chainSyncFailsWhenTheSchedulerFailsForAnotherReason() {
        host.refuseSync = true;
        RsFuture<String> chained = future.chainSync(events::add);
        source.complete("x");
        assertTrue(assertThrows(CompletionException.class, chained::join).getCause() instanceof IllegalStateException);
    }

    /** A chain waits for a main-thread task: waiting for it on the main thread throws instead of freezing. */
    @Test
    void mainThreadJoinOfAPendingChainThrows() {
        RsFuture<String> chained = future.chainSync(events::add);
        source.complete("x");
        host.asMain(() -> {
            assertThrows(IllegalStateException.class, chained::join);
            assertThrows(IllegalStateException.class, () -> chained.joinWithin(Duration.ofSeconds(1)));
        });
        host.runMain();
        host.asMain(() -> assertEquals("x", chained.join()));
    }

    @Test
    void wrapMainCompletedGuardsTheMainThreadUnlessBornInTeardown() {
        RsFuture<String> mainOnly = TestDbs.future(host, new CompletableFuture<>(), true);
        host.asMain(() -> assertThrows(IllegalStateException.class, mainOnly::join));

        host.shuttingDown = true;
        CompletableFuture<String> late = new CompletableFuture<>();
        RsFuture<String> teardown = TestDbs.future(host, late, true);
        CompletableFuture.runAsync(() -> {
            RsDbTest.pause(50);
            late.complete("z");
        });
        host.asMain(() -> assertEquals("z", teardown.join()));
    }

    /** A hop the scheduler dropped at shutdown would leave its chain pending forever; shutdown releases it. */
    @Test
    void shutdownReleasesHopsThatNeverRan() {
        RsFuture<String> chained = future.chainSync(events::add);
        source.complete("x");
        assertEquals(1, host.queued());
        assertEquals(1, MainHops.pending(host.owner));
        MainHops.release(host.owner);
        assertEquals("x", chained.join());
        host.runMain();
        assertTrue(events.isEmpty(), "a released hop does not run its consumer later");
        assertEquals(0, MainHops.pending(host.owner));
    }

    @Test
    void joinWithinReportsTimeoutAndFailure() {
        assertFalse(future.joinWithin(Duration.ofMillis(20)));
        source.complete("x");
        assertTrue(future.joinWithin(Duration.ofMillis(20)));
        assertTrue(future.joinWithin(Duration.ofSeconds(Long.MAX_VALUE)));

        CompletableFuture<String> failing = new CompletableFuture<>();
        RsFuture<String> failed = TestDbs.future(host, failing, false);
        SQLException cause = new SQLException("x");
        failing.completeExceptionally(cause);
        assertSame(cause, assertThrows(CompletionException.class, () -> failed.joinWithin(Duration.ofSeconds(1))).getCause());
    }

    @Test
    void joinWithinReturnsFalseWhenInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(future.joinWithin(Duration.ofSeconds(5)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void exceptionallySeesTheRealCause() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        assertSame(future, future.exceptionally(seen::set));
        SQLException cause = new SQLException("x");
        source.completeExceptionally(new CompletionException(new ExecutionException(cause)));
        assertSame(cause, seen.get());
    }

    @Test
    void exceptionallyIsNotCalledOnSuccess() {
        future.exceptionally(error -> events.add("error"));
        source.complete("x");
        assertTrue(events.isEmpty());
    }

    @Test
    void orDisablePluginDisablesOnTheMainThread() {
        assertSame(future, future.orDisablePlugin());
        source.completeExceptionally(new SQLException("cannot connect"));
        assertEquals(0, host.disables);
        host.runMain();
        assertEquals(1, host.disables);
        assertEquals(List.of("Critical database operation failed; disabling Test."), host.log.messages(Level.SEVERE));
    }

    @Test
    void orDisablePluginOnTheMainThreadDisablesRightAway() {
        CompletableFuture<String> failing = new CompletableFuture<>();
        TestDbs.future(host, failing, false).orDisablePlugin();
        host.asMain(() -> failing.completeExceptionally(new SQLException("x")));
        assertEquals(1, host.disables);
        assertEquals(0, host.queued());
    }

    @Test
    void orDisablePluginIgnoresSuccess() {
        future.orDisablePlugin();
        source.complete("x");
        host.runMain();
        assertEquals(0, host.disables);
    }

    @Test
    void toCompletableFutureIsACopy() {
        CompletableFuture<String> copy = future.toCompletableFuture();
        copy.complete("other");
        assertFalse(source.isDone());
        List<String> got = new ArrayList<>();
        future.toCompletableFuture().thenAccept(got::add);
        source.complete("x");
        assertEquals(List.of("x"), got);
    }
}
