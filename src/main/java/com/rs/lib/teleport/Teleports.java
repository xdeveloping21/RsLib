package com.rs.lib.teleport;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.util.TimeUtil;

/**
 * Teleports with a wait ({@code /home}, {@code /warp}), reached with {@code rs().teleports()}
 * once the spec declares {@code teleports()}.
 *
 * <pre>
 * switch (rs().teleports().request(player, home, TeleportOptions.builder()
 *         .warmupSeconds(5).cooldown("home", 60).build())) {
 *     case ON_COOLDOWN -&gt; ...
 *     default -&gt; { }
 * }
 * </pre>
 *
 * <ul>
 *   <li>A request is decided in this order: no player or destination, an offline player or a
 *   plugin shutting down gives {@link TeleportResult#FAILED}; a wait already running for the
 *   player (from this plugin) gives {@link TeleportResult#ALREADY_PENDING}; a running cooldown
 *   of the options' category gives {@link TeleportResult#ON_COOLDOWN}; a destination in a world
 *   that is not loaded gives {@link TeleportResult#FAILED}; no wait teleports at once
 *   ({@link TeleportResult#TELEPORTED}); otherwise the wait starts with its message. Without
 *   options (or with null) the request is {@link TeleportOptions#instant()}.</li>
 *   <li>The wait is cancelled, with the "moved" message, when the player walks into another
 *   block, is teleported by anything else (another plugin, a pearl, a portal) or changes world;
 *   with the "damage" message when they take damage or die. Turning the head or moving inside
 *   the same block does not count. A player who leaves, an explicit {@link #cancel} or the
 *   plugin shutting down cancel it without a message.</li>
 *   <li>When the wait ends with the player online, the cooldown starts (category and seconds
 *   above 0) and the player is moved with {@code teleportAsync} (safe on Folia).
 *   {@code onComplete} runs on the player's thread only when the teleport really happened.</li>
 * </ul>
 */
public final class Teleports {

    /** Live modules of every plugin, for the shared listener. */
    private static final CopyOnWriteArrayList<Teleports> LIVE = new CopyOnWriteArrayList<>();
    /** Waits running across every plugin: the listener returns at once while it is 0. */
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final Listener LISTENER = new CancelListener();

    private final TeleportHost host;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    /** Created by the context when the spec declares {@code teleports()}. */
    public Teleports(Rs rs) {
        this(TeleportHost.of(Objects.requireNonNull(rs, "rs")));
        rs.onQuit(player -> cancel(player.getUniqueId()));
    }

    Teleports(TeleportHost host) {
        this.host = host;
        LIVE.add(this);
    }

    /** The listener every plugin shares; RsLib registers it. */
    public static Listener sharedListener() {
        return LISTENER;
    }

    /** {@link #request(Player, Location, TeleportOptions)} with {@link TeleportOptions#instant()}. */
    public TeleportResult request(Player player, Location destination) {
        return request(player, destination, TeleportOptions.instant());
    }

    /** Asks for a teleport; see the class notes for the order of the checks. */
    public TeleportResult request(Player player, Location destination, TeleportOptions options) {
        if (player == null || destination == null || stopped || host.shuttingDown() || !player.isOnline()) {
            return TeleportResult.FAILED;
        }
        TeleportOptions opts = options == null ? TeleportOptions.instant() : options;
        UUID id = player.getUniqueId();
        if (pending.containsKey(id)) {
            return TeleportResult.ALREADY_PENDING;
        }
        String category = opts.cooldownCategory();
        if (category != null && host.cooldownLeft(id, category) > 0) {
            return TeleportResult.ON_COOLDOWN;
        }
        if (!host.worldLoaded(destination)) {
            return TeleportResult.FAILED;
        }
        Location target = destination.clone();
        if (opts.warmupSeconds() <= 0) {
            if (host.ownsHere(player)) {
                complete(player, target, opts);
            } else {
                try {
                    host.runFor(player, () -> complete(player, target, opts));
                } catch (RuntimeException notScheduled) {
                    return TeleportResult.FAILED;
                }
            }
            return TeleportResult.TELEPORTED;
        }
        Pending wait = new Pending(id, player, target, opts);
        if (pending.putIfAbsent(id, wait) != null) {
            return TeleportResult.ALREADY_PENDING;
        }
        PENDING.incrementAndGet();
        try {
            wait.task = host.later(player, opts.warmupTicks(), () -> fire(wait));
        } catch (RuntimeException notScheduled) {
            release(wait);
            return TeleportResult.FAILED;
        }
        if (!opts.silent()) {
            host.send(player, opts.warmupKey(),
                    Ph.of("time", TimeUtil.humanizeShort(opts.warmupSeconds() * 1000L)));
        }
        return TeleportResult.WARMUP_STARTED;
    }

    public boolean isPending(Player player) {
        return player != null && isPending(player.getUniqueId());
    }

    public boolean isPending(UUID player) {
        return player != null && pending.containsKey(player);
    }

    /** Cancels the wait of {@code player} without a message; true when there was one. */
    public boolean cancel(Player player) {
        return player != null && cancel(player.getUniqueId());
    }

    /** Cancels the wait of {@code player} without a message; true when there was one. */
    public boolean cancel(UUID player) {
        Pending wait = player == null ? null : pending.get(player);
        if (wait == null || !release(wait)) {
            return false;
        }
        wait.stopTask();
        return true;
    }

    /** Cancels every wait without messages or {@code onComplete}; no new request is accepted. */
    public void shutdown() {
        stopped = true;
        LIVE.remove(this);
        for (Pending wait : new ArrayList<>(pending.values())) {
            if (release(wait)) {
                wait.stopTask();
            }
        }
    }

    /** The player moved to another block, was teleported or changed world: waits end with the "moved" message. */
    public static void dispatchMove(Player player) {
        dispatch(player, true);
    }

    /** The player took damage or died: waits end with the "damage" message. */
    public static void dispatchDamage(Player player) {
        dispatch(player, false);
    }

    // ---------------------------------------------------------------- internals

    private static void dispatch(Player player, boolean moved) {
        if (PENDING.get() == 0 || player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        for (Teleports teleports : LIVE) {
            teleports.interrupt(id, player, moved);
        }
    }

    void interrupt(UUID id, Player player, boolean moved) {
        Pending wait = pending.get(id);
        if (wait == null || !release(wait)) {
            return;
        }
        wait.stopTask();
        if (!wait.options.silent()) {
            try {
                host.send(player, moved ? wait.options.cancelledMoveKey() : wait.options.cancelledDamageKey());
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "Could not send the teleport cancel message", e);
            }
        }
    }

    /** The wait ended. */
    private void fire(Pending wait) {
        if (release(wait)) {
            complete(wait.player, wait.destination, wait.options);
        }
    }

    /** True for whoever removes {@code wait} first. */
    private boolean release(Pending wait) {
        if (pending.remove(wait.id, wait)) {
            PENDING.decrementAndGet();
            return true;
        }
        return false;
    }

    /** On the player's thread: the cooldown starts, then the player moves. */
    private void complete(Player player, Location destination, TeleportOptions options) {
        if (!player.isOnline() || stopped) {
            return;
        }
        String category = options.cooldownCategory();
        if (category != null && options.cooldownSeconds() > 0) {
            host.startCooldown(player.getUniqueId(), category, options.cooldownSeconds());
        }
        CompletableFuture<Boolean> moved;
        try {
            moved = host.teleport(player, destination);
        } catch (RuntimeException e) {
            host.logger().log(Level.WARNING, "Could not teleport " + player.getName(), e);
            return;
        }
        if (moved == null) {
            return;
        }
        Consumer<Player> done = options.onComplete();
        moved.whenComplete((ok, error) -> {
            if (error != null) {
                host.logger().log(Level.WARNING, "The teleport of " + player.getName() + " failed", error);
                return;
            }
            if (done != null && Boolean.TRUE.equals(ok)) {
                runOnComplete(player, done);
            }
        });
    }

    private void runOnComplete(Player player, Consumer<Player> done) {
        Runnable call = () -> {
            try {
                done.accept(player);
            } catch (RuntimeException e) {
                host.logger().log(Level.WARNING, "A teleport onComplete callback failed", e);
            }
        };
        if (host.ownsHere(player)) {
            call.run();
            return;
        }
        try {
            host.runFor(player, call);
        } catch (RuntimeException notScheduled) {
            // The plugin is going down: the callback is dropped like any other task.
        }
    }

    /** True when {@code to} is in another block (or world) than {@code from}. */
    static boolean changedBlock(Location from, Location to) {
        if (to == null || from == null) {
            return false;
        }
        return from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ() || from.getWorld() != to.getWorld();
    }

    /** Waits running across every plugin (for tests). */
    static int pendingTotal() {
        return PENDING.get();
    }

    /** One running wait. */
    private static final class Pending {

        final UUID id;
        final Player player;
        final Location destination;
        final TeleportOptions options;
        volatile TaskHandle task;

        Pending(UUID id, Player player, Location destination, TeleportOptions options) {
            this.id = id;
            this.player = player;
            this.destination = destination;
            this.options = options;
        }

        void stopTask() {
            TaskHandle current = task;
            if (current != null) {
                current.cancel();
            }
        }
    }

    /** The events that end a wait, shared by every plugin. */
    static final class CancelListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onMove(PlayerMoveEvent event) {
            if (PENDING.get() != 0 && changedBlock(event.getFrom(), event.getTo())) {
                dispatchMove(event.getPlayer());
            }
        }

        /** Another plugin, a pearl or a portal; RsLib's own teleport ended the wait before moving. */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onTeleport(PlayerTeleportEvent event) {
            if (PENDING.get() != 0 && changedBlock(event.getFrom(), event.getTo())) {
                dispatchMove(event.getPlayer());
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onWorldChange(PlayerChangedWorldEvent event) {
            dispatchMove(event.getPlayer());
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            if (PENDING.get() != 0 && event.getEntity() instanceof Player player) {
                dispatchDamage(player);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDeath(PlayerDeathEvent event) {
            dispatchDamage(event.getEntity());
        }
    }
}
