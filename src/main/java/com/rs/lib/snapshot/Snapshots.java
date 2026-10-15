package com.rs.lib.snapshot;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.entity.Player;

import com.rs.lib.Rs;
import com.rs.lib.yml.internal.YamlText;

/**
 * Player snapshots kept on disk ({@code rs().snapshots()}), so a player's state survives a
 * restart or a crash: staff mode, arenas, events.
 *
 * <pre>
 * rs().snapshots().saveAndClear(player, "staff").thenAccept(saved -&gt; {
 *     if (saved) giveStaffItems(player);
 * });
 * rs().snapshots().restore(player, "staff");
 * </pre>
 *
 * <p>Each snapshot is {@code plugins/<Plugin>/snapshots/<uuid>/<key>.yml}; keys are 1 to 32
 * characters among {@code a-z}, {@code 0-9}, {@code _} and {@code -}. Every call may come from
 * any thread: the player is captured, cleared and restored on the player's own thread (the main
 * thread on Paper, the player's region on Folia) and files are read and written off it. The
 * futures complete on the player's thread (or the main thread when there is no player, or
 * when the player has left), and never exceptionally: a failure is logged and gives false.</p>
 *
 * <p>Operations on the same player and key run strictly in the order they were asked for.
 * Files are written to a temporary file and moved into place. A restore first renames the file
 * to {@code <key>.yml.restoring} and deletes it in the same tick it applies it, so a snapshot
 * is never applied twice; a {@code .restoring} file left by a crash is restored normally next
 * time. A snapshot not yet on disk when the plugin shuts down is written synchronously, and if
 * even that fails its YAML goes to the log so it can be put back by hand.</p>
 */
public final class Snapshots {

    /** The longest key. */
    public static final int MAX_KEY_LENGTH = 32;

    static final String EXTENSION = ".yml";
    static final String CLAIMED = ".yml.restoring";
    private static final int STRIPES = 64;
    private static final CompletableFuture<Void> IDLE = CompletableFuture.completedFuture(null);

    private final SnapshotHost host;
    /** The last operation queued on each player and key; guarded by itself. */
    private final Map<String, CompletableFuture<Void>> lanes = new HashMap<>();
    /** Saves captured but not yet on disk, by lane. */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Steps scheduled on a thread that may never run them (the shutdown runs them). */
    private final Set<Step> waiting = ConcurrentHashMap.newKeySet();
    /** File locks: one lane always maps to the same lock. */
    private final Object[] locks = new Object[STRIPES];
    private volatile boolean closing;

    public Snapshots(Rs rs) {
        this(SnapshotHost.of(Objects.requireNonNull(rs, "rs")));
    }

    Snapshots(SnapshotHost host) {
        this.host = host;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    // ------------------------------------------------------------------ API

    /** Saves the {@link SnapshotPart#DEFAULT} parts; false when {@code key} already exists. */
    public CompletableFuture<Boolean> save(Player player, String key) {
        return save(player, key, SnapshotOptions.defaults());
    }

    /**
     * Saves the {@link SnapshotPart#DEFAULT} parts and clears them in the same tick; false (and
     * nothing cleared) when {@code key} already exists. If the file cannot be written the player
     * gets everything back.
     */
    public CompletableFuture<Boolean> saveAndClear(Player player, String key) {
        return save(player, key, SnapshotOptions.defaults().withClear(true));
    }

    /**
     * Captures {@code player} and writes the snapshot under {@code key}. True once the file is
     * on disk; false when the key exists (without {@code overwrite}), the player left, an item
     * could not be written or the file could not be written (a cleared player then gets
     * everything back, or, if the player left, the snapshot is kept in memory and written later).
     *
     * @throws IllegalArgumentException when {@code key} is not a valid key
     */
    public CompletableFuture<Boolean> save(Player player, String key, SnapshotOptions options) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(options, "options");
        checkKey(key);
        UUID id = player.getUniqueId();
        String lane = laneOf(id, key);
        return deliver(player, enqueue(lane, () -> saveSteps(player, id, key, lane, options)));
    }

    /**
     * Replaces the captured parts of {@code player} with the snapshot {@code key} and deletes
     * it. False when there is none, the player left or is dead (the snapshot stays), or the
     * snapshot cannot be read (it stays, and nothing is applied).
     *
     * @throws IllegalArgumentException when {@code key} is not a valid key
     */
    public CompletableFuture<Boolean> restore(Player player, String key) {
        Objects.requireNonNull(player, "player");
        checkKey(key);
        UUID id = player.getUniqueId();
        String lane = laneOf(id, key);
        return deliver(player, enqueue(lane, () -> restoreSteps(player, id, key, lane)));
    }

    /** Whether a snapshot {@code key} exists for {@code player} (on disk, or still being written). */
    public CompletableFuture<Boolean> exists(UUID player, String key) {
        Objects.requireNonNull(player, "player");
        checkKey(key);
        String lane = laneOf(player, key);
        return deliver(null, enqueue(lane, () -> onDisk(() -> pending.containsKey(lane)
                || Files.exists(file(player, key)) || Files.exists(claimed(player, key)))));
    }

    /** Deletes the snapshot {@code key} of {@code player}; true when there was one. */
    public CompletableFuture<Boolean> delete(UUID player, String key) {
        Objects.requireNonNull(player, "player");
        checkKey(key);
        String lane = laneOf(player, key);
        return deliver(null, enqueue(lane, () -> onDisk(() -> {
            boolean any = pending.remove(lane) != null;
            synchronized (lock(lane)) {
                any |= Files.deleteIfExists(file(player, key));
                any |= Files.deleteIfExists(claimed(player, key));
            }
            return any;
        })));
    }

    /** Writes now, on the calling thread, every snapshot that is not on disk yet. */
    public void flushPending() {
        for (Pending entry : List.copyOf(pending.values())) {
            persist(entry);
        }
    }

    /**
     * Part of the plugin's shutdown: runs every step still waiting for a thread (on this one, or
     * as "the player left" when this thread cannot touch the player), writes what is not on disk
     * and logs the YAML of anything that still could not be written.
     */
    public void shutdown() {
        closing = true;
        while (!waiting.isEmpty()) {
            for (Step step : List.copyOf(waiting)) {
                step.drain();
            }
        }
        flushPending();
        for (Pending entry : pending.values()) {
            host.logger().severe("Snapshot '" + entry.key + "' of " + entry.name + " (" + entry.id
                    + ") could not be written to " + file(entry.id, entry.key)
                    + ". Save the text below as that file to give it back:\n" + entry.yaml);
        }
    }

    /**
     * Throws {@link IllegalArgumentException} unless {@code key} is 1 to 32 characters among
     * {@code a-z}, {@code 0-9}, {@code _} and {@code -}.
     */
    public static void checkKey(String key) {
        if (!validKey(key)) {
            throw new IllegalArgumentException("Snapshot key '" + key + "' must be 1 to " + MAX_KEY_LENGTH
                    + " characters among a-z, 0-9, _ and -");
        }
    }

    static boolean validKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ save

    private CompletableFuture<Boolean> saveSteps(Player player, UUID id, String key, String lane, SnapshotOptions options) {
        CompletableFuture<Boolean> free = options.overwrite() && !pending.containsKey(lane)
                ? CompletableFuture.completedFuture(true)
                : onDisk(() -> {
                    retryPending(lane);
                    if (pending.containsKey(lane)) {
                        host.logger().warning("Snapshot '" + key + "' of " + player.getName()
                                + " was not saved: an earlier one with that key still waits for the disk.");
                        return false;
                    }
                    return options.overwrite() || !(Files.exists(file(id, key)) || Files.exists(claimed(id, key)));
                });
        return free.thenCompose(isFree -> isFree
                ? onPlayer(player, () -> capture(player, id, key, lane, options), () -> null)
                : CompletableFuture.completedFuture(null))
                .thenCompose(entry -> entry == null ? CompletableFuture.completedFuture(false) : write(entry));
    }

    /** On the player's thread: captures, encodes and (when asked) clears. Null when nothing was saved. */
    private Pending capture(Player player, UUID id, String key, String lane, SnapshotOptions options) {
        if (pending.containsKey(lane)) {
            return null;
        }
        Set<SnapshotPart> parts = options.parts();
        if (parts.contains(SnapshotPart.INVENTORY) || parts.contains(SnapshotPart.ENDER_CHEST)) {
            player.closeInventory();
        }
        PlayerSnapshot snapshot;
        String yaml;
        try {
            snapshot = PlayerSnapshot.capture(player, parts);
            yaml = snapshot.toYaml(host.codec());
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Snapshot '" + key + "' of " + player.getName()
                    + " was not saved; nothing was cleared.", e);
            return null;
        }
        Pending entry = new Pending(lane, id, key, player, snapshot, yaml, options.clear());
        pending.put(lane, entry);
        if (options.clear()) {
            try {
                PlayerSnapshot.clear(player, parts);
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not clear " + player.getName()
                        + " after saving snapshot '" + key + "'; the save goes on.", e);
            }
        }
        return entry;
    }

    private CompletableFuture<Boolean> write(Pending entry) {
        return onDisk(() -> persist(entry)).thenCompose(written -> written
                ? CompletableFuture.completedFuture(true)
                : writeFailed(entry));
    }

    /** Writes {@code entry} unless it is already on disk or was deleted; true when it is on disk. */
    private boolean persist(Pending entry) {
        synchronized (lock(entry.lane)) {
            if (entry.written) {
                return true;
            }
            if (pending.get(entry.lane) != entry) {
                return false;
            }
            try {
                YamlText.write(file(entry.id, entry.key), entry.yaml);
            } catch (IOException | RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not write snapshot '" + entry.key + "' of " + entry.name, e);
                return false;
            }
            entry.written = true;
            pending.remove(entry.lane, entry);
            return true;
        }
    }

    /** Not cleared: nothing to give back. Cleared: give it back, or keep it in memory if the player left. */
    private CompletableFuture<Boolean> writeFailed(Pending entry) {
        if (!entry.cleared) {
            pending.remove(entry.lane, entry);
            return CompletableFuture.completedFuture(false);
        }
        return onPlayer(entry.player, () -> {
            if (entry.player.isDead()) {
                host.logger().severe(entry.name + " is dead, so snapshot '" + entry.key + "' cannot be given back now;"
                        + " it stays in memory and is written as soon as the disk allows (at the latest on shutdown).");
                return false;
            }
            try {
                entry.player.closeInventory();
                entry.snapshot.apply(entry.player, true);
            } catch (RuntimeException e) {
                host.logger().log(Level.SEVERE, "Could not give " + entry.name + " back what snapshot '"
                        + entry.key + "' holds; it stays in memory and is written as soon as the disk allows.", e);
                return false;
            }
            pending.remove(entry.lane, entry);
            host.logger().warning(entry.name + " got back what snapshot '" + entry.key + "' held, since it could not be written.");
            return false;
        }, () -> {
            host.logger().severe(entry.name + " left before getting back what snapshot '" + entry.key
                    + "' holds; it stays in memory and is written as soon as the disk allows (at the latest on shutdown).");
            return false;
        });
    }

    /** A save still in memory (its write failed and the player could not get it back) is tried again. */
    private void retryPending(String lane) {
        Pending entry = pending.get(lane);
        if (entry != null) {
            persist(entry);
        }
    }

    // ------------------------------------------------------------------ restore

    private CompletableFuture<Boolean> restoreSteps(Player player, UUID id, String key, String lane) {
        return onDisk(() -> claim(player, id, key, lane)).thenCompose(text -> text == null
                ? CompletableFuture.completedFuture(false)
                : onPlayer(player, () -> applyClaimed(player, id, key, lane, text), () -> {
                    unclaim(id, key, lane);
                    return false;
                }));
    }

    /** Renames the snapshot to {@code .restoring} (unless a crash left one) and reads it; null when there is none. */
    private String claim(Player player, UUID id, String key, String lane) {
        retryPending(lane);
        if (pending.containsKey(lane)) {
            host.logger().warning("Snapshot '" + key + "' of " + player.getName()
                    + " cannot be restored yet: it still waits for the disk.");
            return null;
        }
        Path file = file(id, key);
        Path claimed = claimed(id, key);
        synchronized (lock(lane)) {
            if (!Files.exists(claimed)) {
                if (!Files.exists(file)) {
                    return null;
                }
                try {
                    move(file, claimed);
                } catch (IOException e) {
                    host.logger().log(Level.WARNING, "Could not claim snapshot " + file + "; it stays.", e);
                    return null;
                }
            }
            try {
                return YamlText.decode(Files.readAllBytes(claimed));
            } catch (IOException e) {
                host.logger().log(Level.WARNING, "Could not read snapshot " + claimed + "; it stays.", e);
                unclaimLocked(file, claimed);
                return null;
            }
        }
    }

    /** On the player's thread: reads everything first, deletes the claimed file, then applies. */
    private boolean applyClaimed(Player player, UUID id, String key, String lane, String text) {
        if (player.isDead()) {
            unclaim(id, key, lane);
            return false;
        }
        PlayerSnapshot snapshot;
        try {
            snapshot = PlayerSnapshot.fromYaml(text, host.codec());
        } catch (RuntimeException e) {
            host.logger().log(Level.SEVERE, "Snapshot '" + key + "' of " + player.getName()
                    + " cannot be read; nothing was applied and the file stays: " + e.getMessage(), e);
            unclaim(id, key, lane);
            return false;
        }
        synchronized (lock(lane)) {
            try {
                Files.deleteIfExists(claimed(id, key));
            } catch (IOException e) {
                host.logger().log(Level.SEVERE, "Could not delete " + claimed(id, key)
                        + "; snapshot not applied, so it can never be applied twice.", e);
                return false;
            }
        }
        if (snapshot.skippedEffects() > 0) {
            host.logger().warning("Snapshot '" + key + "' of " + player.getName() + ": " + snapshot.skippedEffects()
                    + " potion effect(s) of a type this server does not have were left out.");
        }
        try {
            Set<SnapshotPart> parts = snapshot.parts();
            if (parts.contains(SnapshotPart.INVENTORY) || parts.contains(SnapshotPart.ENDER_CHEST)) {
                player.closeInventory();
            }
            snapshot.restoreTo(player);
            return true;
        } catch (RuntimeException e) {
            host.logger().log(Level.SEVERE, "Restoring snapshot '" + key + "' of " + player.getName()
                    + " failed part way; the snapshot is written back so it can be restored again.", e);
            keep(id, key, lane, player.getName(), text);
            return false;
        }
    }

    /** Puts {@code text} back as the snapshot file; logs it when even that fails. */
    private void keep(UUID id, String key, String lane, String name, String text) {
        synchronized (lock(lane)) {
            try {
                YamlText.write(file(id, key), text);
            } catch (IOException | RuntimeException e) {
                host.logger().log(Level.SEVERE, "Could not write snapshot '" + key + "' of " + name + " (" + id
                        + ") back to " + file(id, key) + ". Save the text below as that file to give it back:\n" + text, e);
            }
        }
    }

    private void unclaim(UUID id, String key, String lane) {
        synchronized (lock(lane)) {
            unclaimLocked(file(id, key), claimed(id, key));
        }
    }

    /** Renames the claimed file back, unless a newer snapshot took its name (then it stays, restored first next time). */
    private void unclaimLocked(Path file, Path claimed) {
        try {
            if (Files.exists(claimed) && !Files.exists(file)) {
                move(claimed, file);
            }
        } catch (IOException e) {
            host.logger().log(Level.WARNING, "Could not rename " + claimed + " back; it is restored next time anyway.", e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to);
        }
    }

    // ------------------------------------------------------------------ ordering and threads

    /** Runs {@code op} once every earlier operation on {@code lane} has finished. */
    private <T> CompletableFuture<T> enqueue(String lane, Supplier<CompletableFuture<T>> op) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<Void> previous;
        synchronized (lanes) {
            previous = lanes.getOrDefault(lane, IDLE);
            lanes.put(lane, gate);
        }
        CompletableFuture<T> result = previous.thenCompose(ignored -> start(op));
        result.whenComplete((value, error) -> {
            synchronized (lanes) {
                lanes.remove(lane, gate);
            }
            gate.complete(null);
        });
        return result;
    }

    private static <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> op) {
        try {
            return Objects.requireNonNull(op.get(), "step");
        } catch (RuntimeException | Error e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Runs {@code work} off the main thread, or right here while the plugin shuts down. */
    private <T> CompletableFuture<T> onDisk(Callable<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Step step = new Step(() -> complete(future, work), null);
        if (inline()) {
            step.run();
            return future;
        }
        waiting.add(step);
        try {
            host.async(step);
        } catch (RuntimeException notScheduled) {
            step.run();
        }
        return future;
    }

    /** Runs {@code work} on the player's thread while the player is there, {@code gone} otherwise. */
    private <T> CompletableFuture<T> onPlayer(Player player, Callable<T> work, Callable<T> gone) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Step step = new Step(() -> complete(future, here(player) ? work : gone), () -> complete(future, gone));
        if (host.ownsHere(player)) {
            step.run();
            return future;
        }
        if (inline()) {
            step.retire();
            return future;
        }
        waiting.add(step);
        try {
            host.runFor(player, step, step::retire);
        } catch (RuntimeException notScheduled) {
            step.retire();
        }
        return future;
    }

    /** Completes the public future on the player's thread, or the main thread without a player. */
    private CompletableFuture<Boolean> deliver(Player player, CompletableFuture<Boolean> internal) {
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        internal.whenComplete((value, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                host.logger().log(Level.WARNING, "A snapshot operation failed", cause);
            }
            boolean result = error == null && Boolean.TRUE.equals(value);
            Runnable done = () -> out.complete(result);
            if (inline() || (player != null && host.ownsHere(player))) {
                done.run();
                return;
            }
            try {
                if (player != null && host.present(player)) {
                    host.runFor(player, done, () -> onMain(done));
                } else {
                    onMain(done);
                }
            } catch (RuntimeException notScheduled) {
                done.run();
            }
        });
        return out;
    }

    /** While the plugin shuts down nothing is scheduled any more: every step runs on the calling thread. */
    private boolean inline() {
        return closing || host.shuttingDown();
    }

    /** True when the calling thread may touch {@code player} and the player is still there. */
    private boolean here(Player player) {
        return host.ownsHere(player) && host.present(player);
    }

    private void onMain(Runnable task) {
        try {
            host.main(task);
        } catch (RuntimeException notScheduled) {
            task.run();
        }
    }

    private static <T> void complete(CompletableFuture<T> future, Callable<T> work) {
        try {
            future.complete(work.call());
        } catch (Exception | Error e) {
            future.completeExceptionally(e);
        }
    }

    // ------------------------------------------------------------------ files

    private Path file(UUID player, String key) {
        return host.folder().resolve(player.toString()).resolve(key + EXTENSION);
    }

    private Path claimed(UUID player, String key) {
        return host.folder().resolve(player.toString()).resolve(key + CLAIMED);
    }

    private static String laneOf(UUID player, String key) {
        return player.toString() + '/' + key;
    }

    private Object lock(String lane) {
        return locks[(lane.hashCode() & Integer.MAX_VALUE) % STRIPES];
    }

    /**
     * A task that runs once: on its thread, or when that thread can never run it ({@code retire}),
     * or from the shutdown ({@code drain}).
     */
    private final class Step implements Runnable {

        private final AtomicBoolean taken = new AtomicBoolean();
        private final Runnable body;
        private final Runnable retired;

        Step(Runnable body, Runnable retired) {
            this.body = body;
            this.retired = retired;
        }

        @Override
        public void run() {
            if (taken.compareAndSet(false, true)) {
                waiting.remove(this);
                body.run();
            }
        }

        void retire() {
            if (retired == null) {
                run();
            } else if (taken.compareAndSet(false, true)) {
                waiting.remove(this);
                retired.run();
            }
        }

        /** From the shutdown: a disk step runs here; a player step runs here only if this thread owns the player. */
        void drain() {
            run();
        }
    }

    /** A captured snapshot on its way to the disk. */
    private static final class Pending {

        final String lane;
        final UUID id;
        final String key;
        final String name;
        final Player player;
        final PlayerSnapshot snapshot;
        final String yaml;
        final boolean cleared;
        /** Guarded by the lane's lock. */
        boolean written;

        Pending(String lane, UUID id, String key, Player player, PlayerSnapshot snapshot, String yaml, boolean cleared) {
            this.lane = lane;
            this.id = id;
            this.key = key;
            this.name = player.getName();
            this.player = player;
            this.snapshot = snapshot;
            this.yaml = yaml;
            this.cleared = cleared;
        }
    }
}
