package com.rs.lib.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A small benchmark harness for RsLib's hot paths: warm-up, then timed rounds with
 * {@link System#nanoTime()}, reported as a table. No dependency beyond the JDK.
 *
 * <p>Benchmarks are JUnit tests that only run when asked: environment variable
 * {@code RSLIB_BENCH=1} or system property {@code -Drslib.bench=true}. Rounds and warm-up can be
 * shortened with {@code RSLIB_BENCH_MS} (milliseconds per round, 200 by default).</p>
 *
 * <p>The numbers are for comparing runs on the same machine (before and after a change), not
 * absolute: the server is replaced by fakes, so Bukkit's own costs are not in them.</p>
 */
public final class Bench {

    private static final int ROUNDS = 5;
    private static final Object PRINT_LOCK = new Object();
    /** Keeps results alive so the JIT cannot drop the work. */
    private static volatile int sink;

    private final String title;
    private final List<Result> results = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private Bench(String title) {
        this.title = title;
    }

    /** A new table called {@code title}. */
    public static Bench table(String title) {
        return new Bench(title);
    }

    /** True when benchmarks were asked for; the JUnit condition of every benchmark class. */
    public static boolean enabled() {
        String env = System.getenv("RSLIB_BENCH");
        return (env != null && !env.isBlank() && !env.equals("0") && !env.equalsIgnoreCase("false"))
                || Boolean.getBoolean("rslib.bench");
    }

    /** Milliseconds per round (and for the warm-up). */
    static long roundMillis() {
        String value = System.getenv("RSLIB_BENCH_MS");
        try {
            return value == null ? 200L : Math.max(10L, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 200L;
        }
    }

    /** Hands a value to a field the JIT cannot see through. */
    public static void consume(Object value) {
        sink += value == null ? 0 : System.identityHashCode(value);
    }

    /** Same as {@link #consume(Object)} for an int. */
    public static void consume(int value) {
        sink += value;
    }

    /**
     * Times {@code body}, which does {@code opsPerCall} operations per call: warm-up for one
     * round, then {@value #ROUNDS} rounds; the median round gives the result.
     */
    public Result run(String name, int opsPerCall, Runnable body) {
        long round = roundMillis() * 1_000_000L;
        loop(body, round);
        double[] nsPerOp = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            long[] measured = loop(body, round);
            nsPerOp[r] = (double) measured[1] / (measured[0] * (double) opsPerCall);
        }
        Arrays.sort(nsPerOp);
        Result result = new Result(name, nsPerOp[ROUNDS / 2], nsPerOp[0], nsPerOp[ROUNDS - 1]);
        results.add(result);
        return result;
    }

    /** A line printed under the table (counts, settings). */
    public void note(String line) {
        notes.add(line);
    }

    /** Calls and nanoseconds of one round of at least {@code nanos}. */
    private static long[] loop(Runnable body, long nanos) {
        long calls = 0;
        long start = System.nanoTime();
        long elapsed;
        do {
            for (int i = 0; i < 16; i++) {
                body.run();
            }
            calls += 16;
            elapsed = System.nanoTime() - start;
        } while (elapsed < nanos);
        return new long[] {calls, elapsed};
    }

    /** The table, ready to print. */
    public String render() {
        int width = "Benchmark".length();
        for (Result result : results) {
            width = Math.max(width, result.name().length());
        }
        StringBuilder out = new StringBuilder();
        String line = "-".repeat(width + 46);
        out.append('\n').append(title).append('\n').append(line).append('\n');
        out.append(String.format(Locale.ROOT, "%-" + width + "s %12s %14s %16s%n", "Benchmark", "ns/op", "ops/s",
                "range ns/op"));
        out.append(line).append('\n');
        for (Result result : results) {
            out.append(String.format(Locale.ROOT, "%-" + width + "s %12s %14s %16s%n", result.name(),
                    number(result.nsPerOp()), String.format(Locale.ROOT, "%,.0f", 1e9 / result.nsPerOp()),
                    number(result.best()) + "-" + number(result.worst())));
        }
        out.append(line).append('\n');
        for (String note : notes) {
            out.append("  ").append(note).append('\n');
        }
        return out.toString();
    }

    /** Prints the table to standard output. */
    public void print() {
        synchronized (PRINT_LOCK) {
            System.out.print(render());
            System.out.flush();
        }
    }

    private static String number(double ns) {
        return ns >= 100 ? String.format(Locale.ROOT, "%,.0f", ns) : String.format(Locale.ROOT, "%.1f", ns);
    }

    /** One benchmark: the median and the best and worst rounds, in nanoseconds per operation. */
    public record Result(String name, double nsPerOp, double best, double worst) {
    }
}
