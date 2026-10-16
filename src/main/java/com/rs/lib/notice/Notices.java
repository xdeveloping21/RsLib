package com.rs.lib.notice;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.db.RsDb;
import com.rs.lib.scheduler.TaskHandle;

/**
 * Messages left for a player, reached with {@code rs().notices()} (needs {@code db()} and
 * {@code lang()} in the spec).
 *
 * <pre>
 * // onInnerEnable: declare every category the plugin sends
 * rs().notices().category("sold").summary("notices.sold-summary");
 * rs().notices().category("expired");
 *
 * // any thread
 * rs().notices().send(seller, "sold", "notices.sold", Ph.literal("item", "Diamond x16"), Ph.of("price", 4500));
 * </pre>
 *
 * <ul>
 *   <li>A player online on this server sees the message at once.</li>
 *   <li>Otherwise it is stored in the plugin's database (table {@code rslib_notices}) and shown
 *   when they join, {@code notices.join-delay-seconds} later (RsLib's config.yml) if they are
 *   still online. Waiting notices are grouped by category, in the order each first arrived: a
 *   category with a {@link NoticeCategory#summary summary} shows that one message with
 *   {@code {count}} and {@code {sum_<value>}}; one without shows its oldest five notices and
 *   {@code rslib.notices.more} for the rest.</li>
 *   <li>Each notice is shown once: it is taken by deleting its row, and only the delete that
 *   removed the row shows it (two servers sharing a MySQL database never both show it). A
 *   player who leaves before the message gets the notices written back.</li>
 *   <li>A player has at most {@code notices.max-per-player} waiting notices of this plugin;
 *   past that new ones are refused and the console says so. Notices nobody read are deleted
 *   after {@code notices.keep-days} days.</li>
 *   <li>Values made with {@link Ph#literal} stay literal when the notice is shown later. A
 *   message key is rendered with the language file in use when it is shown.</li>
 * </ul>
 *
 * <p>Every method is safe from any thread; the database is only touched by its own threads.</p>
 */
public final class Notices {

    static final int MAX_CATEGORY = 64;
    static final int MAX_KEY = 255;
    /** Notices of a category without a summary shown at join before {@link #MORE_KEY}. */
    static final int LIST_LIMIT = 5;
    static final String MORE_KEY = "rslib.notices.more";
    /** One minute after start, then every six hours. */
    static final long FIRST_SWEEP_TICKS = 1200L;
    static final long SWEEP_PERIOD_TICKS = 432_000L;
    private static final long CLOSE_WAIT_SECONDS = 5L;

    /** What happened to one notice; for tests. */
    enum Outcome {
        SHOWN, STORED, REFUSED, FAILED
    }

    /** One line of the join summary: a message key and its values. */
    record Line(String key, Ph[] phs) {
    }

    /** Notices taken from the database (or about to be shown live) but not shown yet. */
    private static final class Pending {
        final UUID player;
        final List<Notice> notices;
        private final AtomicBoolean handled = new AtomicBoolean();

        Pending(UUID player, List<Notice> notices) {
            this.player = player;
            this.notices = notices;
        }

        boolean claim() {
            return handled.compareAndSet(false, true);
        }
    }

    private final NoticeHost host;
    private final String owner;
    private final Runnable requirements;
    private final NoticeStore store;
    private final Map<String, NoticeCategory> categories = new ConcurrentHashMap<>();
    private final Set<UUID> limitWarned = ConcurrentHashMap.newKeySet();
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private volatile CompletableFuture<Void> ready;
    private volatile TaskHandle sweep;
    private volatile boolean stopped;

    /** Created by the context when the spec declares {@code db()}; starts with the first category. */
    public Notices(Rs rs) {
        this(NoticeHost.of(rs), rs.plugin().getName(), rs::lang);
    }

    Notices(NoticeHost host, String owner, Runnable requirements) {
        this.host = host;
        this.owner = owner;
        this.requirements = requirements;
        this.store = new NoticeStore(owner);
    }

    /**
     * Declares the category {@code id} (1 to 64 characters), or returns it when it already is.
     * Declare every category in {@code onInnerEnable}: the first one starts the module, which
     * then shows waiting notices to every player who joins.
     *
     * @throws UnsupportedOperationException when the plugin did not declare {@code lang()}
     */
    public NoticeCategory category(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank() || id.length() > MAX_CATEGORY) {
            throw new IllegalArgumentException("A notice category needs 1 to " + MAX_CATEGORY + " characters: '" + id + "'");
        }
        requirements.run();
        NoticeCategory category = categories.computeIfAbsent(id, NoticeCategory::new);
        ready();
        return category;
    }

    /**
     * Leaves the message {@code messageKey} of the plugin's language file for {@code player}:
     * shown now when they are online here, otherwise kept until they join.
     *
     * @throws IllegalArgumentException when {@code category} was not declared or the key is blank
     *                                  or longer than 255 characters
     */
    public void send(UUID player, String category, String messageKey, Ph... values) {
        deliver(player, category, messageKey, values);
    }

    CompletableFuture<Outcome> deliver(UUID player, String category, String messageKey, Ph... values) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(messageKey, "messageKey");
        if (!categories.containsKey(category)) {
            throw new IllegalArgumentException("Notice category '" + category + "' is not declared: call rs().notices()"
                    + ".category(\"" + category + "\") in onInnerEnable");
        }
        if (messageKey.isBlank() || messageKey.length() > MAX_KEY) {
            throw new IllegalArgumentException("A notice message key needs 1 to " + MAX_KEY + " characters: '" + messageKey + "'");
        }
        List<Ph> copy = new ArrayList<>(values.length);
        for (Ph value : values) {
            copy.add(Objects.requireNonNull(value, "value"));
        }
        Notice notice = new Notice(0L, category, messageKey, copy, host.now());
        if (stopped || !host.isOnline(player)) {
            return store(player, notice);
        }
        Pending live = new Pending(player, List.of(notice));
        CompletableFuture<Outcome> outcome = new CompletableFuture<>();
        pending.add(live);
        boolean scheduled = host.runFor(player, () -> {
            if (!take(live)) {
                return;
            }
            if (host.isOnline(player)) {
                show(player, new Line(notice.message(), notice.phs()));
                outcome.complete(Outcome.SHOWN);
            } else {
                pipe(store(player, notice), outcome);
            }
        }, () -> {
            if (take(live)) {
                pipe(store(player, notice), outcome);
            }
        });
        if (!scheduled && take(live)) {
            pipe(store(player, notice), outcome);
        }
        return outcome;
    }

    /**
     * Stops the join listener and the sweep, and writes back the notices that were taken but not
     * shown yet. Called by the context on shutdown, before the database closes.
     */
    public void shutdown() {
        stopped = true;
        TaskHandle running = sweep;
        if (running != null) {
            running.cancel();
        }
        host.stop();
        if (pending.isEmpty()) {
            return;
        }
        // The table first: a write queued behind it would reach the database after it closed.
        CompletableFuture<Void> table = ready;
        if (table != null) {
            try {
                table.get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // Logged where it failed; the write-back below fails and says so.
            }
        }
        for (Pending each : pending) {
            if (take(each)) {
                writeBack(each.player, each.notices);
            }
        }
    }

    // ------------------------------------------------------------------ join

    /** A player joined: their notices are shown after the configured delay if they are still online. */
    void joined(UUID player) {
        if (stopped) {
            return;
        }
        try {
            host.later(host.settings().joinDelayTicks(), () -> {
                if (host.isOnline(player)) {
                    claim(player);
                }
            });
        } catch (RuntimeException disabling) {
            // The plugin is disabling: the notices wait for the next join.
        }
    }

    /** Takes and shows the waiting notices of {@code player}; the value is how many were shown. */
    CompletableFuture<Integer> claim(UUID player) {
        CompletableFuture<Integer> shown = new CompletableFuture<>();
        if (stopped) {
            shown.complete(0);
            return shown;
        }
        long cutoff = host.now() - host.settings().keepMillis();
        AtomicReference<List<Notice>> taken = new AtomicReference<>(List.of());
        run(connection -> taken.set(store.take(connection, player, cutoff))).whenComplete((ignored, error) -> {
            if (error != null) {
                host.logger().log(Level.WARNING, "Could not read the waiting notices of " + player, error);
                shown.complete(0);
                return;
            }
            List<Notice> notices = taken.get();
            if (notices.isEmpty()) {
                shown.complete(0);
                return;
            }
            Pending batch = new Pending(player, notices);
            pending.add(batch);
            boolean scheduled = !stopped && host.runFor(player, () -> {
                if (!take(batch)) {
                    shown.complete(0);
                    return;
                }
                if (host.isOnline(player)) {
                    summarize(notices).forEach(line -> show(player, line));
                    shown.complete(notices.size());
                } else {
                    writeBack(player, notices);
                    shown.complete(0);
                }
            }, () -> {
                if (take(batch)) {
                    writeBack(player, notices);
                }
                shown.complete(0);
            });
            if (!scheduled) {
                if (take(batch)) {
                    writeBack(player, notices);
                }
                shown.complete(0);
            }
        });
        return shown;
    }

    /**
     * The join messages for {@code notices} (oldest first): grouped by category in the order
     * each first appears; a summary line per category that has one, otherwise the first
     * {@link #LIST_LIMIT} notices and a line with how many more there were.
     */
    List<Line> summarize(List<Notice> notices) {
        Map<String, List<Notice>> groups = new LinkedHashMap<>();
        for (Notice notice : notices) {
            groups.computeIfAbsent(notice.category(), ignored -> new ArrayList<>()).add(notice);
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, List<Notice>> group : groups.entrySet()) {
            NoticeCategory category = categories.get(group.getKey());
            String summary = category == null ? null : category.summaryKey();
            List<Notice> members = group.getValue();
            if (summary != null) {
                lines.add(new Line(summary, totals(members)));
                continue;
            }
            for (int i = 0; i < Math.min(LIST_LIMIT, members.size()); i++) {
                lines.add(new Line(members.get(i).message(), members.get(i).phs()));
            }
            if (members.size() > LIST_LIMIT) {
                lines.add(new Line(MORE_KEY, new Ph[] {Ph.of("count", members.size() - LIST_LIMIT)}));
            }
        }
        return lines;
    }

    /** {@code {count}} and {@code {sum_<key>}} for every value that is a number in at least one notice. */
    static Ph[] totals(List<Notice> notices) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Notice notice : notices) {
            for (Ph value : notice.values()) {
                BigDecimal number = number(value.value());
                if (number != null) {
                    sums.merge(value.key(), number, BigDecimal::add);
                }
            }
        }
        List<Ph> phs = new ArrayList<>(sums.size() + 1);
        phs.add(Ph.of("count", notices.size()));
        sums.forEach((key, sum) -> phs.add(Ph.of("sum_" + key, plain(sum))));
        return phs.toArray(new Ph[0]);
    }

    // ------------------------------------------------------------------ internals

    private CompletableFuture<Void> ready() {
        CompletableFuture<Void> current = ready;
        if (current != null) {
            return current;
        }
        synchronized (lock) {
            if (ready != null) {
                return ready;
            }
            RsDb db = host.db();
            var type = db.config().type();
            CompletableFuture<Void> created = db.bootstrap(connection -> NoticeStore.create(connection, type))
                    .toCompletableFuture();
            created.whenComplete((ignored, error) -> {
                if (error != null) {
                    host.logger().log(Level.SEVERE, "Could not create the table " + NoticeStore.TABLE
                            + "; notices of " + owner + " are not kept", error);
                }
            });
            ready = created;
            if (!stopped) {
                start();
            }
            return created;
        }
    }

    private void start() {
        host.listenJoins(this::joined);
        try {
            sweep = host.every(FIRST_SWEEP_TICKS, SWEEP_PERIOD_TICKS, this::sweep);
        } catch (RuntimeException e) {
            host.logger().warning("Could not schedule the sweep of old notices (" + e.getMessage()
                    + "); they are deleted when their player joins.");
        }
        for (UUID online : host.online()) {
            joined(online);
        }
    }

    /** Deletes the notices nobody read in time; the value is how many. */
    CompletableFuture<Integer> sweep() {
        long cutoff = host.now() - host.settings().keepMillis();
        AtomicInteger deleted = new AtomicInteger();
        return run(connection -> deleted.set(store.purge(connection, cutoff))).handle((ignored, error) -> {
            if (error != null) {
                host.logger().log(Level.WARNING, "Could not delete old notices", error);
            }
            return deleted.get();
        });
    }

    private CompletableFuture<Outcome> store(UUID player, Notice notice) {
        int max = host.settings().maxPerPlayer();
        AtomicBoolean stored = new AtomicBoolean();
        return run(connection -> stored.set(store.insert(connection, player, notice, max))).handle((ignored, error) -> {
            if (error != null) {
                host.logger().log(Level.WARNING, "Could not keep a notice (" + notice.category() + ") for " + player, error);
                return Outcome.FAILED;
            }
            if (!stored.get()) {
                if (limitWarned.add(player)) {
                    host.logger().warning(player + " already has " + max + " waiting notices of " + owner
                            + "; new ones are refused until they join (notices.max-per-player in RsLib's config.yml).");
                }
                return Outcome.REFUSED;
            }
            limitWarned.remove(player);
            return Outcome.STORED;
        });
    }

    private void writeBack(UUID player, List<Notice> notices) {
        run(connection -> store.insertAll(connection, player, notices)).whenComplete((ignored, error) -> {
            if (error != null) {
                host.logger().log(Level.WARNING, "Could not keep " + notices.size() + " notice(s) for " + player
                        + " who left before seeing them; they are lost", error);
            }
        });
    }

    private CompletableFuture<Void> run(RsDb.SqlConsumer<Connection> work) {
        return ready().thenCompose(ignored -> host.db().transaction(work).toCompletableFuture());
    }

    private boolean take(Pending each) {
        if (!each.claim()) {
            return false;
        }
        pending.remove(each);
        return true;
    }

    private void show(UUID player, Line line) {
        try {
            host.show(player, line.key(), line.phs());
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not show the notice " + line.key() + " to " + player, e);
        }
    }

    private static <T> void pipe(CompletableFuture<T> from, CompletableFuture<T> to) {
        from.whenComplete((value, error) -> {
            if (error != null) {
                to.completeExceptionally(error);
            } else {
                to.complete(value);
            }
        });
    }

    private static BigDecimal number(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return null;
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }
}
