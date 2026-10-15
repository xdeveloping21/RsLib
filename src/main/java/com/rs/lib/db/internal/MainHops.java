package com.rs.lib.db.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Main-thread tasks of {@code RsFuture.chainSync} that are scheduled but have not run yet, per
 * plugin.
 *
 * <p>When a plugin shuts down, its scheduler drops the tasks that never ran, and the futures
 * waiting on them would stay pending forever. {@link #release} completes each of them the way
 * a hop that cannot happen does (with the value, without the callback). Each hop runs at most
 * once: either its task {@linkplain Hop#claim() claims} it or the release does.</p>
 */
public final class MainHops {

    private static final Map<Object, Set<Hop>> PENDING = new HashMap<>();

    private MainHops() {
    }

    /** Registers a hop of {@code owner}; {@code onRelease} runs if the owner shuts down first. */
    public static Hop add(Object owner, Runnable onRelease) {
        Hop hop = new Hop(owner, onRelease);
        synchronized (PENDING) {
            PENDING.computeIfAbsent(owner, k -> new HashSet<>()).add(hop);
        }
        return hop;
    }

    /** Completes every pending hop of {@code owner} without its callback; called on shutdown. */
    public static void release(Object owner) {
        Set<Hop> hops;
        synchronized (PENDING) {
            hops = PENDING.remove(owner);
        }
        if (hops != null) {
            for (Hop hop : hops) {
                hop.onRelease.run();
            }
        }
    }

    /** Pending hops of {@code owner}, for tests. */
    public static int pending(Object owner) {
        synchronized (PENDING) {
            Set<Hop> hops = PENDING.get(owner);
            return hops == null ? 0 : hops.size();
        }
    }

    /** One scheduled main-thread task. */
    public static final class Hop {

        private final Object owner;
        private final Runnable onRelease;

        private Hop(Object owner, Runnable onRelease) {
            this.owner = owner;
            this.onRelease = onRelease;
        }

        /** True exactly once, for whoever gets here first: the task, or a failed schedule. */
        public boolean claim() {
            synchronized (PENDING) {
                Set<Hop> hops = PENDING.get(owner);
                if (hops == null || !hops.remove(this)) {
                    return false;
                }
                if (hops.isEmpty()) {
                    PENDING.remove(owner);
                }
                return true;
            }
        }
    }
}
