package com.rs.lib.leaderboard;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.OfflinePlayer;

import com.rs.lib.Rs;
import com.rs.lib.db.RsFuture;
import com.rs.lib.scheduler.TaskHandle;

/**
 * Leaderboards refreshed now and then by an async query and read without waiting, from any
 * thread (fit for placeholders). Reached with {@code rs().leaderboards()}.
 *
 * <pre>
 * rs().leaderboards().register("kills", Duration.ofMinutes(1), () -&gt; rs().db().query(
 *         "SELECT uuid, name, kills FROM players ORDER BY kills DESC LIMIT 10",
 *         rs -&gt; ... list of Entry ...));
 * List&lt;LeaderboardCache.Entry&gt; top = rs().leaderboards().getTop("kills", 10);
 * </pre>
 *
 * <ul>
 *   <li>Each board keeps an immutable {@link Snapshot}; until the first result arrives it reads
 *   empty.</li>
 *   <li>The query supplier is called on the main thread and must only start async work (a
 *   database query, for example). A supplier that throws or returns null skips that refresh
 *   with a warning. While one query is still running the next refresh is skipped, and a result
 *   older than the one shown is dropped.</li>
 *   <li>Rows are ordered by value, highest first, ties by name A to Z; null rows are dropped
 *   and a player listed twice keeps the better place.</li>
 *   <li>The refresh period is rounded to ticks and is at least one second.</li>
 * </ul>
 */
public final class LeaderboardCache {

    /** One second: the shortest refresh period. */
    static final long MIN_PERIOD_TICKS = 20L;
    /** A query still running after this long no longer holds back the next refresh. */
    static final long STALE_QUERY_MILLIS = 5L * 60L * 1000L;
    private static final long MILLIS_PER_TICK = 50L;

    /** One row: a player, the name to show and the value ranked. A null name reads as empty. */
    public record Entry(UUID uuid, String name, double value) {

        public Entry {
            name = name == null ? "" : name;
        }
    }

    /** An immutable ranking. Positions are 1-based; 0 means "not on the board". */
    public static final class Snapshot {

        private static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());

        private final List<Entry> entries;
        private final Map<UUID, Integer> positions;

        private Snapshot(List<Entry> entries, Map<UUID, Integer> positions) {
            this.entries = entries;
            this.positions = positions;
        }

        public static Snapshot empty() {
            return EMPTY;
        }

        /** Orders {@code rows} (see the class notes); null or empty gives {@link #empty()}. */
        public static Snapshot of(List<Entry> rows) {
            if (rows == null || rows.isEmpty()) {
                return EMPTY;
            }
            List<Entry> sorted = new ArrayList<>(rows.size());
            for (Entry row : rows) {
                if (row != null) {
                    sorted.add(row);
                }
            }
            // List.sort is stable: rows equal in value and name keep the order they came in.
            sorted.sort(Snapshot::compare);
            List<Entry> kept = new ArrayList<>(sorted.size());
            Map<UUID, Integer> positions = new HashMap<>(Math.max(16, sorted.size() * 2));
            for (Entry row : sorted) {
                if (row.uuid() != null) {
                    if (positions.containsKey(row.uuid())) {
                        continue;
                    }
                    positions.put(row.uuid(), kept.size() + 1);
                }
                kept.add(row);
            }
            if (kept.isEmpty()) {
                return EMPTY;
            }
            return new Snapshot(Collections.unmodifiableList(kept), Collections.unmodifiableMap(positions));
        }

        /** Highest value first; NaN after every number; then name A to Z. */
        private static int compare(Entry a, Entry b) {
            boolean aNan = Double.isNaN(a.value());
            boolean bNan = Double.isNaN(b.value());
            int byValue = aNan || bNan ? Boolean.compare(aNan, bNan) : Double.compare(b.value(), a.value());
            return byValue != 0 ? byValue : a.name().compareTo(b.name());
        }

        /** The first {@code n} rows (fewer when the board is shorter; none for {@code n <= 0}). */
        public List<Entry> top(int n) {
            if (n <= 0) {
                return List.of();
            }
            return n >= entries.size() ? entries : entries.subList(0, n);
        }

        /** The row at 1-based {@code position}, or null. */
        Entry at(int position) {
            return position >= 1 && position <= entries.size() ? entries.get(position - 1) : null;
        }

        /** 1-based place of {@code player}; 0 when they are not on the board. */
        public int positionOf(UUID player) {
            Integer position = player == null ? null : positions.get(player);
            return position == null ? 0 : position;
        }

        /** The value of {@code player}; 0 when they are not on the board. */
        public double valueOf(UUID player) {
            int position = positionOf(player);
            return position == 0 ? 0 : entries.get(position - 1).value();
        }

        public int size() {
            return entries.size();
        }
    }

    private final BoardHost host;
    private final LongSupplier clock;
    private final Map<String, Board> boards = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    /** Created by the context. */
    public LeaderboardCache(Rs rs) {
        this(BoardHost.of(Objects.requireNonNull(rs, "rs")), System::currentTimeMillis);
    }

    LeaderboardCache(BoardHost host, LongSupplier clock) {
        this.host = host;
        this.clock = clock;
    }

    /**
     * Registers (or replaces) the board {@code id}, refreshed every {@code refreshInterval}
     * starting on the next tick. {@code query} runs on the main thread and returns the future
     * of an async query.
     */
    public void register(String id, Duration refreshInterval, Supplier<RsFuture<List<Entry>>> query) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        Objects.requireNonNull(query, "query");
        if (stopped) {
            host.logger().warning("Leaderboard '" + id + "' was registered after shutdown; ignored.");
            return;
        }
        Board board = new Board(id, query);
        Board previous = boards.put(id, board);
        if (previous != null) {
            previous.stop();
        }
        try {
            board.task = host.every(periodTicks(refreshInterval), () -> refresh(board));
        } catch (RuntimeException notScheduled) {
            boards.remove(id, board);
            throw notScheduled;
        }
    }

    /** Stops and forgets the board {@code id}. */
    public void unregister(String id) {
        Board board = id == null ? null : boards.remove(id);
        if (board != null) {
            board.stop();
        }
    }

    /** The first {@code n} rows of board {@code id}; empty when there is no such board yet. */
    public List<Entry> getTop(String id, int n) {
        return snapshot(id).top(n);
    }

    /** 1-based place of {@code player} on board {@code id}; 0 when not on it. */
    public int positionOf(String id, UUID player) {
        return snapshot(id).positionOf(player);
    }

    /** The value of {@code player} on board {@code id}; 0 when not on it. */
    public double valueOf(String id, UUID player) {
        return snapshot(id).valueOf(player);
    }

    /** The current snapshot of board {@code id}; empty when there is none. */
    public Snapshot snapshot(String id) {
        Board board = id == null ? null : boards.get(id);
        return board == null ? Snapshot.EMPTY : board.snapshot;
    }

    /**
     * Placeholders for every board under the expansion {@code identifier}:
     * {@code %<identifier>_top_<board>_<n>_name%}, {@code %<identifier>_top_<board>_<n>_value%}
     * (no player needed) and {@code %<identifier>_pos_<board>%} (the player's place, 0 when not
     * on it). A board name may contain {@code _}. A place without a row gives an empty text;
     * whole values show without {@code .0}. The expansion replaces any other with the same
     * identifier, so use one the plugin does not register itself. False when PlaceholderAPI did
     * not take it now (it is registered by itself once PlaceholderAPI is enabled).
     */
    public boolean exposePlaceholders(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("identifier is blank");
        }
        return host.expose(identifier.trim(), this::resolveTop, this::resolvePosition);
    }

    /** Stops every board; nothing refreshes or registers afterwards. */
    public void shutdown() {
        stopped = true;
        for (Board board : boards.values()) {
            board.stop();
        }
        boards.clear();
    }

    // ---------------------------------------------------------------- placeholders

    /** {@code <board>_<n>_name|value}; null (left unresolved) when it is not one. */
    String resolveTop(String rest) {
        int last = rest.lastIndexOf('_');
        if (last <= 0 || last == rest.length() - 1) {
            return null;
        }
        int previous = rest.lastIndexOf('_', last - 1);
        if (previous <= 0) {
            return null;
        }
        int rank = parseRank(rest, previous + 1, last);
        Board board = rank <= 0 ? null : board(rest.substring(0, previous));
        if (board == null) {
            return null;
        }
        boolean name = rest.regionMatches(true, last + 1, "name", 0, 4) && rest.length() - last - 1 == 4;
        boolean value = rest.regionMatches(true, last + 1, "value", 0, 5) && rest.length() - last - 1 == 5;
        if (!name && !value) {
            return null;
        }
        Entry entry = board.snapshot.at(rank);
        if (entry == null) {
            return "";
        }
        return name ? entry.name() : formatValue(entry.value());
    }

    /** The place of {@code player} on {@code boardName}; null when there is no such board. */
    String resolvePosition(OfflinePlayer player, String boardName) {
        Board board = board(boardName);
        if (board == null || player == null) {
            return null;
        }
        return Integer.toString(board.snapshot.positionOf(player.getUniqueId()));
    }

    /** {@code 10} for whole values, {@code 12.5} otherwise; never an exponent. */
    static String formatValue(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e18) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).toPlainString();
    }

    private Board board(String name) {
        Board board = boards.get(name);
        if (board != null) {
            return board;
        }
        for (Board candidate : boards.values()) {
            if (candidate.id.equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static int parseRank(String text, int from, int to) {
        if (to - from < 1 || to - from > 9) {
            return -1;
        }
        int rank = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            rank = rank * 10 + (c - '0');
        }
        return rank;
    }

    static long periodTicks(Duration interval) {
        long millis;
        try {
            millis = interval.toMillis();
        } catch (ArithmeticException tooLong) {
            millis = Long.MAX_VALUE;
        }
        long ticks = Math.round(millis / (double) MILLIS_PER_TICK);
        return Math.max(MIN_PERIOD_TICKS, ticks);
    }

    // ---------------------------------------------------------------- refresh

    /** On the main thread, every period. */
    void refresh(Board board) {
        if (stopped || boards.get(board.id) != board) {
            return;
        }
        long now = clock.getAsLong();
        synchronized (board) {
            if (board.inFlight && now - board.inFlightSince < STALE_QUERY_MILLIS) {
                host.debug(() -> "leaderboard " + board.id + ": the previous query is still running; refresh skipped");
                return;
            }
        }
        RsFuture<List<Entry>> future;
        try {
            future = board.query.get();
        } catch (RuntimeException | LinkageError e) {
            host.logger().log(Level.WARNING, "The query of leaderboard '" + board.id + "' failed; refresh skipped.", e);
            return;
        }
        if (future == null) {
            host.logger().warning("The query of leaderboard '" + board.id + "' returned no future; refresh skipped.");
            return;
        }
        CompletableFuture<List<Entry>> pending = future.toCompletableFuture();
        long sequence;
        synchronized (board) {
            sequence = ++board.launched;
            board.inFlight = true;
            board.inFlightSince = now;
        }
        pending.whenComplete((rows, error) -> board.finish(sequence, rows, error));
    }

    /** One registered board. */
    final class Board {

        final String id;
        final Supplier<RsFuture<List<Entry>>> query;
        volatile Snapshot snapshot = Snapshot.EMPTY;
        volatile TaskHandle task;
        volatile boolean dead;
        /** Guarded by this board. */
        long launched;
        long applied;
        boolean inFlight;
        long inFlightSince;
        boolean failing;

        Board(String id, Supplier<RsFuture<List<Entry>>> query) {
            this.id = id;
            this.query = query;
        }

        void stop() {
            dead = true;
            TaskHandle current = task;
            if (current != null) {
                current.cancel();
            }
        }

        /** On the thread that completed the query. */
        void finish(long sequence, List<Entry> rows, Throwable error) {
            if (error != null) {
                boolean first;
                synchronized (this) {
                    if (sequence == launched) {
                        inFlight = false;
                    }
                    first = !failing;
                    failing = true;
                }
                if (first) {
                    host.logger().log(Level.WARNING, "The query of leaderboard '" + id
                            + "' failed; the board keeps its last rows.", error);
                } else {
                    host.debug(() -> "leaderboard " + id + ": query failed again: " + error);
                }
                return;
            }
            Snapshot next;
            try {
                next = Snapshot.of(rows);
            } catch (RuntimeException e) {
                synchronized (this) {
                    if (sequence == launched) {
                        inFlight = false;
                    }
                }
                host.logger().log(Level.WARNING, "The rows of leaderboard '" + id + "' could not be ordered.", e);
                return;
            }
            synchronized (this) {
                if (sequence == launched) {
                    inFlight = false;
                }
                failing = false;
                if (dead || stopped || sequence <= applied) {
                    return;
                }
                applied = sequence;
                snapshot = next;
            }
        }
    }
}
