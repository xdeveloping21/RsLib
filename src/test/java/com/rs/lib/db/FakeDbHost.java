package com.rs.lib.db;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

import com.rs.lib.yml.YmlTestSupport.Captured;

/**
 * A plugin context without a server: "main thread" tasks wait in a queue until a test runs
 * them, and the async pool is a real thread pool.
 */
public final class FakeDbHost implements DbHost {

    public final Captured log = new Captured();
    public final Object owner = new Object();
    public final ClassLoader loader = new ClassLoader(FakeDbHost.class.getClassLoader()) {
    };
    public final List<Consumer<UUID>> quitCallbacks = new CopyOnWriteArrayList<>();
    public volatile String name = "Test";
    public volatile boolean enabled = true;
    public volatile boolean shuttingDown;
    public volatile boolean refuseSync;
    public volatile int disables;
    volatile RsDb db;

    private final BlockingQueue<Runnable> main = new LinkedBlockingQueue<>();
    private final ExecutorService asyncPool = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "fake-async");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Thread mainThread;

    @Override
    public String name() {
        return name;
    }

    @Override
    public Logger logger() {
        return log.logger;
    }

    @Override
    public ClassLoader classLoader() {
        return loader;
    }

    @Override
    public Object owner() {
        return owner;
    }

    @Override
    public boolean isPrimaryThread() {
        return Thread.currentThread() == mainThread;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean shuttingDown() {
        return shuttingDown;
    }

    @Override
    public boolean bootstrapping() {
        RsDb current = db;
        return current != null && current.bootstrapping();
    }

    @Override
    public void sync(Runnable task) {
        if (refuseSync || !enabled) {
            throw new IllegalStateException("Plugin attempted to register task while disabled");
        }
        main.add(task);
    }

    @Override
    public void async(Runnable task) {
        if (!enabled) {
            throw new IllegalStateException("Plugin attempted to register task while disabled");
        }
        asyncPool.execute(task);
    }

    @Override
    public void disablePlugin() {
        disables++;
        enabled = false;
    }

    @Override
    public void onQuit(Consumer<UUID> callback) {
        quitCallbacks.add(callback);
    }

    /** The player left the server. */
    public void quit(UUID player) {
        asMain(() -> quitCallbacks.forEach(callback -> callback.accept(player)));
    }

    /** Tasks waiting for the main thread. */
    public int queued() {
        return main.size();
    }

    /** Runs {@code action} as if on the main thread. */
    public void asMain(Runnable action) {
        Thread previous = mainThread;
        mainThread = Thread.currentThread();
        try {
            action.run();
        } finally {
            mainThread = previous;
        }
    }

    /** Runs the main-thread tasks queued so far, like one tick (new ones wait); returns how many ran. */
    public int runMain() {
        int count = main.size();
        asMain(() -> {
            for (int i = 0; i < count; i++) {
                main.remove().run();
            }
        });
        return count;
    }

    /** Runs main-thread tasks as they arrive until {@code done} holds; fails after five seconds. */
    public void runMainUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        asMain(() -> {
            while (!done.getAsBoolean()) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Timed out waiting on the main thread");
                }
                try {
                    Runnable task = main.poll(10, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        task.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        });
    }

    /** Waits until a main-thread task is queued; fails after five seconds. */
    public void awaitQueued(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (main.size() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for a main-thread task");
            }
            Thread.onSpinWait();
        }
    }
}
