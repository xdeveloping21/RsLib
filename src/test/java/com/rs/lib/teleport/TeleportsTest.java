package com.rs.lib.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.scheduler.TaskHandle;

class TeleportsTest {

    /** A host without a server: timers fired by hand, cooldowns on a fake clock, teleports recorded. */
    static final class FakeHost implements TeleportHost {

        final List<String> sent = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<Timer> timers = new ArrayList<>();
        final List<Location> moves = new ArrayList<>();
        final Map<String, Long> cooldowns = new HashMap<>();
        final List<String> events = new ArrayList<>();
        boolean loaded = true;
        boolean shuttingDown;
        /** What a teleport answers; it may also fire events like the server would. */
        Function<Player, CompletableFuture<Boolean>> teleport = p -> CompletableFuture.completedFuture(true);
        private final Logger logger = Logger.getAnonymousLogger();

        FakeHost() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    warnings.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        Timer last() {
            return timers.get(timers.size() - 1);
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public boolean shuttingDown() {
            return shuttingDown;
        }

        @Override
        public boolean ownsHere(Player player) {
            return true;
        }

        @Override
        public void runFor(Player player, Runnable task) {
            task.run();
        }

        @Override
        public TaskHandle later(Player player, long ticks, Runnable task) {
            Timer timer = new Timer(ticks, task);
            timers.add(timer);
            return timer;
        }

        @Override
        public boolean worldLoaded(Location destination) {
            return loaded && destination.getWorld() != null;
        }

        @Override
        public long cooldownLeft(UUID player, String category) {
            return cooldowns.getOrDefault(category, 0L);
        }

        @Override
        public void startCooldown(UUID player, String category, int seconds) {
            events.add("cooldown " + category + " " + seconds);
            cooldowns.put(category, seconds * 1000L);
        }

        @Override
        public void send(Player player, String key, Ph... phs) {
            StringBuilder line = new StringBuilder(key);
            for (Ph ph : phs) {
                line.append(' ').append(ph.key()).append('=').append(ph.value());
            }
            sent.add(line.toString());
        }

        @Override
        public CompletableFuture<Boolean> teleport(Player player, Location destination) {
            events.add("teleport");
            moves.add(destination);
            return teleport.apply(player);
        }
    }

    static final class Timer implements TaskHandle {

        final long ticks;
        final Runnable task;
        boolean cancelled;

        Timer(long ticks, Runnable task) {
            this.ticks = ticks;
            this.task = task;
        }

        void fire() {
            if (!cancelled) {
                cancelled = true;
                task.run();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final World WORLD = world("world");
    private static final World NETHER = world("nether");

    private final List<Teleports> created = new ArrayList<>();
    private final UUID id = UUID.randomUUID();
    private boolean online = true;
    private final Player player = (Player) Proxy.newProxyInstance(TeleportsTest.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "Steve";
                case "isOnline" -> online;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "Steve";
                default -> throw new UnsupportedOperationException(method.getName());
            });

    private static World world(String name) {
        return (World) Proxy.newProxyInstance(TeleportsTest.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Teleports teleports(FakeHost host) {
        Teleports teleports = new Teleports(host);
        created.add(teleports);
        return teleports;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(Teleports::shutdown);
        assertEquals(0, Teleports.pendingTotal());
    }

    private final Location home = new Location(WORLD, 100.5, 64, -20.5);
    /** The builder's default wait. */
    private static final TeleportOptions WAIT = TeleportOptions.builder().build();

    // ---------------------------------------------------------------- requests

    @Test
    void requestsAreDecidedInAFixedOrder() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        assertEquals(TeleportResult.FAILED, teleports.request(null, home));
        assertEquals(TeleportResult.FAILED, teleports.request(player, null));
        assertEquals(TeleportResult.FAILED, teleports.request(player, new Location(null, 0, 0, 0)));
        host.loaded = false;
        assertEquals(TeleportResult.FAILED, teleports.request(player, home));
        host.loaded = true;
        TeleportOptions options = TeleportOptions.builder().warmupSeconds(5).cooldown("home", 60).build();
        assertEquals(TeleportResult.WARMUP_STARTED, teleports.request(player, home, options));
        // Pending comes before the cooldown check.
        host.cooldowns.put("home", 1000L);
        assertEquals(TeleportResult.ALREADY_PENDING, teleports.request(player, home, options));
        teleports.cancel(player);
        assertEquals(TeleportResult.ON_COOLDOWN, teleports.request(player, home, options));
        host.cooldowns.clear();
        assertEquals(TeleportResult.TELEPORTED, teleports.request(player, home, TeleportOptions.instant()));
        assertEquals(1, host.moves.size());
        host.shuttingDown = true;
        assertEquals(TeleportResult.FAILED, teleports.request(player, home, options));
        assertTrue(TeleportResult.TELEPORTED.accepted());
        assertTrue(TeleportResult.ON_COOLDOWN.rejected());
    }

    /** Pending and cooldown are answered before an unloaded world is noticed. */
    @Test
    void anUnloadedWorldIsCheckedAfterPendingAndCooldown() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        TeleportOptions options = TeleportOptions.builder().warmupSeconds(5).cooldown("home", 60).build();
        assertEquals(TeleportResult.WARMUP_STARTED, teleports.request(player, home, options));
        host.loaded = false;
        assertEquals(TeleportResult.ALREADY_PENDING, teleports.request(player, home, options));
        teleports.cancel(player);
        host.cooldowns.put("home", 1000L);
        assertEquals(TeleportResult.ON_COOLDOWN, teleports.request(player, home, options));
        host.cooldowns.clear();
        assertEquals(TeleportResult.FAILED, teleports.request(player, home, options));
        assertEquals(TeleportResult.FAILED, teleports.request(player, new Location(null, 0, 0, 0), options));
    }

    /** Without options (or with null ones) the teleport is instant. */
    @Test
    void noOptionsMeansAnInstantTeleport() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        assertEquals(TeleportResult.TELEPORTED, teleports.request(player, home));
        assertEquals(TeleportResult.TELEPORTED, teleports.request(player, home, null));
        assertEquals(2, host.moves.size());
        assertTrue(host.timers.isEmpty(), "no wait was scheduled");
        assertTrue(host.sent.isEmpty(), "no warmup message");
    }

    @Test
    void theWaitSendsItsMessageAndMovesThePlayerToACopyOfTheDestination() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        assertEquals(TeleportResult.WARMUP_STARTED, teleports.request(player, home, TeleportOptions.warmup(5)));
        assertEquals(List.of("rslib.teleport.warmup time=5s"), host.sent);
        assertEquals(100, host.last().ticks);
        home.setX(0);
        assertTrue(teleports.isPending(player));
        host.last().fire();
        assertFalse(teleports.isPending(player.getUniqueId()));
        assertEquals(100.5, host.moves.get(0).getX());
        assertNotSame(home, host.moves.get(0));
    }

    @Test
    void theCooldownStartsWhenTheTeleportCompletesNotWhenAsked() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        TeleportOptions options = TeleportOptions.builder().warmupSeconds(3).cooldown("home", 60).build();
        teleports.request(player, home, options);
        assertTrue(host.events.isEmpty());
        teleports.request(player, home, options);
        host.last().fire();
        assertEquals(List.of("cooldown home 60", "teleport"), host.events);
        assertEquals(TeleportResult.ON_COOLDOWN, teleports.request(player, home, options));
        // A cancelled wait starts no cooldown.
        host.cooldowns.clear();
        host.events.clear();
        teleports.request(player, home, options);
        Teleports.dispatchDamage(player);
        assertTrue(host.events.isEmpty());
    }

    @Test
    void onCompleteRunsOnlyWhenTheTeleportHappened() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        List<String> done = new ArrayList<>();
        TeleportOptions options = TeleportOptions.builder().warmupSeconds(0).onComplete(p -> done.add("done")).build();
        teleports.request(player, home, options);
        assertEquals(List.of("done"), done);
        host.teleport = p -> CompletableFuture.completedFuture(false);
        teleports.request(player, home, options);
        host.teleport = p -> CompletableFuture.failedFuture(new IllegalStateException("vetoed"));
        teleports.request(player, home, options);
        host.teleport = p -> {
            throw new IllegalStateException("broken");
        };
        teleports.request(player, home, options);
        assertEquals(List.of("done"), done);
        assertEquals(2, host.warnings.size());
    }

    @Test
    void aPlayerWhoLeftDuringTheWaitIsNotMoved() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        teleports.request(player, home, TeleportOptions.builder().cooldown("home", 10).build());
        online = false;
        host.last().fire();
        assertTrue(host.events.isEmpty());
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    void movingToAnotherBlockCancelsWithTheMoveMessage() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        Teleports.CancelListener listener = (Teleports.CancelListener) Teleports.sharedListener();
        teleports.request(player, home, TeleportOptions.builder().cancelledMoveKey("clans.moved").build());
        host.sent.clear();
        Location from = new Location(WORLD, 10.2, 64, 10.2, 0, 0);
        // Turning the head and moving inside the same block do not count.
        listener.onMove(new PlayerMoveEvent(player, from, new Location(WORLD, 10.2, 64, 10.2, 90, 30)));
        listener.onMove(new PlayerMoveEvent(player, from, new Location(WORLD, 10.9, 64.5, 10.7)));
        assertTrue(teleports.isPending(player));
        listener.onMove(new PlayerMoveEvent(player, from, new Location(WORLD, 11.1, 64, 10.2)));
        assertFalse(teleports.isPending(player));
        assertEquals(List.of("clans.moved"), host.sent);
        assertTrue(host.timers.get(0).cancelled);
        host.timers.get(0).task.run();
        assertTrue(host.moves.isEmpty(), "a cancelled wait never teleports");
    }

    @Test
    void anotherTeleportOrAWorldChangeCancelsTheWait() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        Teleports.CancelListener listener = (Teleports.CancelListener) Teleports.sharedListener();
        Location from = new Location(WORLD, 0, 64, 0);
        teleports.request(player, home, WAIT);
        listener.onTeleport(new PlayerTeleportEvent(player, from, new Location(WORLD, 0.5, 64, 0.5)));
        assertTrue(teleports.isPending(player), "a teleport inside the same block does not count");
        listener.onTeleport(new PlayerTeleportEvent(player, from, new Location(NETHER, 0, 64, 0)));
        assertFalse(teleports.isPending(player));
        teleports.request(player, home, WAIT);
        listener.onWorldChange(new PlayerChangedWorldEvent(player, NETHER));
        assertFalse(teleports.isPending(player));
        long moved = host.sent.stream().filter(TeleportOptions.CANCELLED_MOVE_KEY::equals).count();
        assertEquals(2, moved);
    }

    @Test
    void damageAndDeathCancelWithTheDamageMessage() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        teleports.request(player, home, WAIT);
        host.sent.clear();
        Teleports.dispatchDamage(player);
        assertFalse(teleports.isPending(player));
        assertEquals(List.of(TeleportOptions.CANCELLED_DAMAGE_KEY), host.sent);
    }

    @Test
    void silentWaitsSendNothing() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        teleports.request(player, home, TeleportOptions.builder().silent(true).build());
        Teleports.dispatchMove(player);
        assertFalse(teleports.isPending(player));
        assertTrue(host.sent.isEmpty());
    }

    @Test
    void explicitCancelQuitAndShutdownSendNothingAndRunNothing() {
        FakeHost host = new FakeHost();
        Teleports teleports = teleports(host);
        List<String> done = new ArrayList<>();
        TeleportOptions options = TeleportOptions.builder().onComplete(p -> done.add("done")).build();
        teleports.request(player, home, options);
        host.sent.clear();
        assertTrue(teleports.cancel(player));
        assertFalse(teleports.cancel(player.getUniqueId()));
        teleports.request(player, home, options);
        host.sent.clear();
        teleports.shutdown();
        assertFalse(teleports.isPending(player));
        host.last().task.run();
        assertTrue(host.sent.isEmpty());
        assertTrue(done.isEmpty());
        assertTrue(host.moves.isEmpty());
        assertEquals(TeleportResult.FAILED, teleports.request(player, home, options));
    }

    @Test
    void theLibrarysOwnTeleportDoesNotCancelItselfButCancelsOtherPlugins() {
        FakeHost hostA = new FakeHost();
        FakeHost hostB = new FakeHost();
        Teleports a = teleports(hostA);
        Teleports b = teleports(hostB);
        // The server fires PlayerTeleportEvent while moving the player.
        hostA.teleport = p -> {
            Teleports.dispatchMove(p);
            return CompletableFuture.completedFuture(true);
        };
        List<String> done = new ArrayList<>();
        a.request(player, home, TeleportOptions.builder().onComplete(p -> done.add("a")).build());
        b.request(player, home, WAIT);
        hostA.timers.get(0).fire();
        assertEquals(List.of("a"), done);
        assertFalse(b.isPending(player), "plugin B's wait ends when plugin A moves the player");
        assertEquals(TeleportOptions.CANCELLED_MOVE_KEY, hostB.sent.get(hostB.sent.size() - 1));
    }

    @Test
    void builtInMessagesExistInBothLanguagesAndReadOldKeys() throws Exception {
        assertEquals("&7Teleporting in &e{time}&7. Do not move.", TeleportHost.Defaults.get(TeleportOptions.WARMUP_KEY));
        assertEquals(TeleportHost.Defaults.get(TeleportOptions.CANCELLED_MOVE_KEY),
                TeleportHost.Defaults.get("snlib.teleport.cancelled-move"));
        assertEquals("messages.home-warmup", TeleportHost.Defaults.get("messages.home-warmup"));
        for (String language : new String[] {"en", "es"}) {
            org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
            try (java.io.Reader in = new java.io.InputStreamReader(
                    TeleportsTest.class.getResourceAsStream("/rslib-messages_" + language + ".yml"),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                yaml.load(in);
            }
            for (String key : new String[] {TeleportOptions.WARMUP_KEY, TeleportOptions.CANCELLED_MOVE_KEY,
                    TeleportOptions.CANCELLED_DAMAGE_KEY}) {
                assertTrue(yaml.isString(key), language + " " + key);
            }
        }
    }

    @Test
    void optionsHaveSaneDefaults() {
        TeleportOptions defaults = TeleportOptions.builder().warmupSeconds(-4).cooldown(" ", 9).warmupKey(null).build();
        assertEquals(0, defaults.warmupSeconds());
        assertEquals(0, defaults.warmupTicks());
        assertEquals(null, defaults.cooldownCategory());
        assertEquals(TeleportOptions.WARMUP_KEY, defaults.warmupKey());
        assertEquals(TeleportOptions.DEFAULT_WARMUP_SECONDS, TeleportOptions.builder().build().warmupSeconds());
        assertEquals(0, TeleportOptions.instant().warmupSeconds());
        assertEquals(140, TeleportOptions.warmup(7).warmupTicks());
        assertTrue(Teleports.changedBlock(new Location(WORLD, -0.5, 0, 0), new Location(WORLD, 0.5, 0, 0)));
        assertFalse(Teleports.changedBlock(new Location(WORLD, 0, 0, 0), null));
    }
}
