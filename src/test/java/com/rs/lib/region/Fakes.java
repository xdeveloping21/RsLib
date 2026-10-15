package com.rs.lib.region;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.rs.lib.Ph;
import com.rs.lib.event.RsSelectionCompleteEvent;
import com.rs.lib.scheduler.TaskHandle;

/** Worlds, players and a host without a server for the region tests. */
final class Fakes {

    private Fakes() {
    }

    /** A world that only knows its name; equal only to itself. */
    static World world(String name) {
        return (World) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A player standing somewhere, online until told otherwise, with the permissions in {@link #perms}. */
    static final class FakePlayer {
        final UUID id = UUID.randomUUID();
        final String name;
        final Set<String> perms = new HashSet<>();
        boolean online = true;
        Location at;
        final Player player;

        FakePlayer(String name, Location start) {
            this.name = name;
            this.at = start;
            this.player = (Player) Proxy.newProxyInstance(Fakes.class.getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "getName" -> name;
                        case "isOnline" -> online;
                        case "hasPermission" -> perms.contains((String) args[0]);
                        case "getLocation" -> {
                            Location here = this.at;
                            if (args == null || args[0] == null) {
                                yield here.clone();
                            }
                            Location into = (Location) args[0];
                            into.setWorld(here.getWorld());
                            into.setX(here.getX());
                            into.setY(here.getY());
                            into.setZ(here.getZ());
                            yield into;
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> name;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /** A repeating task fired by hand. */
    static final class Timer implements TaskHandle {
        final long period;
        final Runnable task;
        boolean cancelled;

        Timer(long period, Runnable task) {
            this.period = period;
            this.task = task;
        }

        void fire() {
            if (!cancelled) {
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

    /** Messages, events, timers and particles recorded; time moved by hand. */
    static final class Host implements SelectionHost {
        final List<String> sent = new ArrayList<>();
        final List<RsSelectionCompleteEvent> events = new ArrayList<>();
        final List<Timer> timers = new ArrayList<>();
        final Map<Player, Integer> particles = new HashMap<>();
        final List<Player> worldPlayers = new ArrayList<>();
        final Set<Player> elsewhere = new HashSet<>();
        final Map<UUID, Player> online = new HashMap<>();
        final List<String> warnings = new ArrayList<>();
        Predicate<RsSelectionCompleteEvent> cancel = e -> false;
        long now = 1_000_000L;
        boolean shuttingDown;
        Particle lastParticle;
        Object lastData;
        private final Logger logger = Logger.getAnonymousLogger();
        private final String namespace;

        Host(String namespace) {
            this.namespace = namespace;
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

        Timer lastTimer() {
            return timers.get(timers.size() - 1);
        }

        int particlesFor(Player viewer) {
            return particles.getOrDefault(viewer, 0);
        }

        @Override
        public Plugin plugin() {
            return null;
        }

        @Override
        public String namespace() {
            return namespace;
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
        public long nowMillis() {
            return now;
        }

        @Override
        public void send(Player player, String key, Ph... phs) {
            StringBuilder line = new StringBuilder(key.substring(key.lastIndexOf('.') + 1));
            for (Ph ph : phs) {
                line.append(' ').append(ph.key()).append('=').append(ph.value());
            }
            sent.add(line.toString());
        }

        @Override
        public boolean fire(RsSelectionCompleteEvent event) {
            events.add(event);
            if (cancel.test(event)) {
                event.setCancelled(true);
            }
            return !event.isCancelled();
        }

        @Override
        public TaskHandle timer(Player player, long periodTicks, Runnable task) {
            Timer timer = new Timer(periodTicks, task);
            timers.add(timer);
            return timer;
        }

        @Override
        public boolean ownsHere(Player player) {
            return !elsewhere.contains(player);
        }

        @Override
        public Collection<? extends Player> playersIn(World world) {
            return worldPlayers;
        }

        @Override
        public Player online(UUID id) {
            return online.get(id);
        }

        @Override
        public void particle(Player viewer, Particle particle, double x, double y, double z, Object data) {
            particles.merge(viewer, 1, Integer::sum);
            lastParticle = particle;
            lastData = data;
        }

        @Override
        public ItemStack wandBase(SelectionSpec spec) {
            throw new UnsupportedOperationException("items need a server");
        }
    }
}
