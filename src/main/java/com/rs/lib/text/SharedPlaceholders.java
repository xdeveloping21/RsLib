package com.rs.lib.text;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.bukkit.Bukkit;

/**
 * Placeholder values that do not depend on the viewer, computed once per tick and reused by
 * every render of that tick that asks for them, while the caller turned sharing on.
 *
 * <p>Only RsLib's own viewer-free placeholders take part: those a file declares with
 * {@code RsYml#placeholder} and the {@code global} ones of an expansion declared with
 * {@code rs().papi().expansion(...)}. Other PlaceholderAPI placeholders are always resolved per
 * viewer, as RsLib cannot tell which of them depend on the player.</p>
 *
 * <p>Sharing is on only inside {@link #during} (RsLib's menus use it for their timed
 * refreshes, so every viewer refreshed in the same tick sees one computation). The values live
 * per thread and per server tick: on Folia each region thread keeps its own. Outside
 * {@link #during}, {@link #value} simply computes.</p>
 */
public final class SharedPlaceholders {

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static volatile LongSupplier clock = SharedPlaceholders::serverTick;

    private SharedPlaceholders() {
    }

    /** Runs {@code work} with sharing on for this thread; nested calls share the same values. */
    public static void during(Runnable work) {
        Objects.requireNonNull(work, "work");
        State state = STATE.get();
        if (state.depth++ == 0) {
            state.begin(clock);
        }
        try {
            work.run();
        } finally {
            state.depth--;
        }
    }

    /**
     * The value of {@code key} from {@code source}: computed now when sharing is off on this
     * thread, otherwise computed the first time in this tick and then reused (null included).
     *
     * @param source what answers the key (the supplier or resolver itself), so two sources
     *               never share a value
     */
    public static String value(Object source, String key, Supplier<String> compute) {
        State state = STATE.get();
        if (state.depth == 0) {
            return compute.get();
        }
        Key k = new Key(source, key);
        Map<Key, String> values = state.values;
        if (values.containsKey(k)) {
            state.hits++;
            return values.get(k);
        }
        String computed = compute.get();
        values.put(k, computed);
        return computed;
    }

    /** True while sharing is on for this thread. */
    public static boolean active() {
        return STATE.get().depth > 0;
    }

    /** Values reused on this thread since it started; for tests and benchmarks. */
    static long hits() {
        return STATE.get().hits;
    }

    /** Replaces the tick clock; for tests. Null restores the server's. */
    static void clock(LongSupplier ticks) {
        clock = ticks == null ? SharedPlaceholders::serverTick : ticks;
        STATE.get().values.clear();
    }

    /** The server's tick, or {@link Long#MIN_VALUE} when there is no server to ask. */
    private static long serverTick() {
        try {
            return Bukkit.getServer() == null ? Long.MIN_VALUE : Bukkit.getCurrentTick();
        } catch (RuntimeException | LinkageError noTick) {
            return Long.MIN_VALUE;
        }
    }

    private record Key(Object source, String key) {
    }

    private static final class State {
        final Map<Key, String> values = new HashMap<>();
        int depth;
        long tick = Long.MIN_VALUE;
        long hits;

        /** A new tick forgets the old values; without a clock every outermost call is its own tick. */
        void begin(LongSupplier ticks) {
            long now = ticks.getAsLong();
            if (now == Long.MIN_VALUE || now != tick) {
                values.clear();
                tick = now;
            }
        }
    }
}
