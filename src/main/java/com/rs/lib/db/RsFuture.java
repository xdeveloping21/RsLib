package com.rs.lib.db;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;

import com.rs.lib.Rs;
import com.rs.lib.db.internal.MainHops;

/**
 * The result of database work (or any async work), consumed on the main thread.
 *
 * <p>Two ways to get the value onto the main thread, and they differ:</p>
 * <ul>
 *   <li>{@link #thenSync} returns <b>this same future</b>. Several {@code thenSync} calls on one
 *   future are siblings that each hop to the main thread on their own, in no promised order;
 *   they are not a sequence.</li>
 *   <li>{@link #chainSync} returns a <b>new future</b> that completes only after the callback
 *   ran on the main thread. {@code a.chainSync(x).chainSync(y)} runs {@code x}, then {@code y}.
 *   Chain {@code chainSync} only on {@code chainSync}.</li>
 * </ul>
 *
 * <p>{@link #exceptionally} and {@link #orDisablePlugin} return this same future too. On Folia
 * "main thread" means the global region.</p>
 *
 * @param <T> the value type
 */
public final class RsFuture<T> {

    private static final int TRACE_FRAMES = 5;

    private final DbHost host;
    private final CompletableFuture<T> future;
    /** Completes only from a main-thread task: waiting for it on the main thread would freeze. */
    private final boolean mainCompleted;
    private final boolean bornInTeardown;

    RsFuture(DbHost host, CompletableFuture<T> future, boolean mainCompleted) {
        this.host = host;
        this.future = Objects.requireNonNull(future, "future");
        this.mainCompleted = mainCompleted;
        this.bornInTeardown = host.shuttingDown();
    }

    /** Adapts any future of {@code rs}'s plugin to this API. */
    public static <T> RsFuture<T> wrap(Rs rs, CompletableFuture<T> future) {
        return new RsFuture<>(DbHost.of(Objects.requireNonNull(rs, "rs")), future, false);
    }

    /**
     * Adapts a future that is only ever completed from a main-thread task: {@link #join()} on
     * the main thread then throws instead of freezing the server.
     */
    public static <T> RsFuture<T> wrapMainCompleted(Rs rs, CompletableFuture<T> future) {
        return new RsFuture<>(DbHost.of(Objects.requireNonNull(rs, "rs")), future, true);
    }

    /**
     * Hands the value to {@code consumer} on the main thread and returns <b>this</b> future.
     * A failure is logged once as a warning and the consumer is not called; when the plugin is
     * no longer enabled the value is dropped.
     */
    public RsFuture<T> thenSync(Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        future.whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                host.logger().log(Level.WARNING, "Async task finished with an error: " + describe(cause), cause);
                return;
            }
            if (!host.isEnabled()) {
                return;
            }
            try {
                host.sync(() -> accept(consumer, value));
            } catch (RuntimeException disabledMeanwhile) {
                host.logger().warning("Hop to main discarded: plugin disabled during scheduling");
            }
        });
        return this;
    }

    /**
     * Runs {@code consumer} on the main thread and returns a <b>new</b> future that completes
     * with the same value once it ran.
     *
     * <ul>
     *   <li>This future failed: the consumer does not run and the new one fails the same way.</li>
     *   <li>The consumer throws: the new future fails with that exception.</li>
     *   <li>The hop is impossible (plugin disabled or shutting down): the new future completes
     *   with the value without running the consumer, so a shutdown never waits on it.</li>
     * </ul>
     */
    public RsFuture<T> chainSync(Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        CompletableFuture<T> next = new CompletableFuture<>();
        RsFuture<T> chained = new RsFuture<>(host, next, true);
        future.whenComplete((value, error) -> {
            if (error != null) {
                next.completeExceptionally(unwrap(error));
                return;
            }
            if (!host.isEnabled() || host.shuttingDown()) {
                next.complete(value);
                return;
            }
            MainHops.Hop hop = MainHops.add(host.owner(), () -> next.complete(value));
            try {
                host.sync(() -> {
                    if (!hop.claim()) {
                        return;
                    }
                    try {
                        consumer.accept(value);
                        next.complete(value);
                    } catch (RuntimeException | Error e) {
                        next.completeExceptionally(e);
                    }
                });
            } catch (RuntimeException notScheduled) {
                if (!hop.claim()) {
                    return;
                }
                if (!host.isEnabled() || host.shuttingDown()) {
                    next.complete(value);
                } else {
                    next.completeExceptionally(notScheduled);
                }
            }
        });
        return chained;
    }

    /**
     * Calls {@code handler} with the real cause if this future fails (completion wrappers
     * removed), on the thread that completed it. Returns this future.
     */
    public RsFuture<T> exceptionally(Consumer<Throwable> handler) {
        Objects.requireNonNull(handler, "handler");
        future.whenComplete((value, error) -> {
            if (error == null) {
                return;
            }
            try {
                handler.accept(unwrap(error));
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "An error handler failed", e);
            }
        });
        return this;
    }

    /**
     * If this future fails, logs it as severe and disables the plugin (right away on the main
     * thread, otherwise on the next tick). For work the plugin cannot run without, such as
     * {@code bootstrap(...)}. Returns this future.
     */
    public RsFuture<T> orDisablePlugin() {
        future.whenComplete((value, error) -> {
            if (error == null) {
                return;
            }
            Throwable cause = unwrap(error);
            host.logger().log(Level.SEVERE, "Critical database operation failed; disabling " + host.name() + ".",
                    cause);
            if (!host.isEnabled()) {
                return;
            }
            if (host.isPrimaryThread()) {
                host.disablePlugin();
                return;
            }
            try {
                host.sync(() -> {
                    if (host.isEnabled()) {
                        host.disablePlugin();
                    }
                });
            } catch (RuntimeException disabledMeanwhile) {
                // Already going down.
            }
        });
        return this;
    }

    /**
     * Waits for the value without a time limit and returns it, or throws a
     * {@link CompletionException} with the failure. Waiting on the main thread outside startup
     * work and shutdown logs a warning with the caller (it is not prevented).
     *
     * @throws IllegalStateException on the main thread, for a future that only the main thread
     *                               can complete and that is still pending
     */
    public T join() {
        guardMainThread();
        warnIfBlocking("join()");
        return future.join();
    }

    /**
     * Waits at most {@code timeout}, for shutdown saves against a database that may not answer.
     * The main-thread rules are those of {@link #join()}: silent during startup work and
     * shutdown, a warning with the caller anywhere else.
     *
     * @return true when the future completed normally; false when the time ran out or the
     *         thread was interrupted (the work goes on and is not cancelled)
     * @throws CompletionException   when the future failed, like {@link #join()}
     * @throws IllegalStateException in the same case as {@link #join()}
     */
    public boolean joinWithin(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        guardMainThread();
        warnIfBlocking("joinWithin()");
        try {
            future.get(TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof CompletionException ce ? ce : new CompletionException(cause);
        }
    }

    /** A copy of the underlying future, to combine with other futures. */
    public CompletableFuture<T> toCompletableFuture() {
        return future.copy();
    }

    private void guardMainThread() {
        if (mainCompleted && !bornInTeardown && !future.isDone() && host.isPrimaryThread()) {
            throw new IllegalStateException("Waiting on the main thread for a result that only the main thread "
                    + "can deliver would freeze the server; use thenSync or chainSync instead.");
        }
    }

    private void accept(Consumer<T> consumer, T value) {
        try {
            consumer.accept(value);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "A thenSync callback failed", e);
        }
    }

    private void warnIfBlocking(String call) {
        if (!future.isDone() && host.isPrimaryThread() && !host.shuttingDown() && !host.bootstrapping()) {
            warnBlocking(call);
        }
    }

    private void warnBlocking(String call) {
        StringBuilder where = new StringBuilder(call).append(" is blocking the main thread of ").append(host.name())
                .append(" until a database result arrives; use thenSync instead.");
        int shown = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.equals(Thread.class.getName()) || cls.equals(RsFuture.class.getName())) {
                continue;
            }
            where.append("\n    at ").append(frame);
            if (++shown == TRACE_FRAMES) {
                break;
            }
        }
        host.logger().warning(where.toString());
    }

    static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
