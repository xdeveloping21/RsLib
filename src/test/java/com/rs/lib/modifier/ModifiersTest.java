package com.rs.lib.modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ModifiersTest {

    /** Permissions in a map, a clock the test moves and a seeded random. */
    static class FakeHost implements ModifierHost {

        final Map<UUID, List<String>> permissions = new HashMap<>();
        final List<String> warnings = new ArrayList<>();
        int reads;
        long now = 1_000;
        boolean luckPerms;
        Consumer<UUID> onPlayer;
        Runnable onEveryone;
        boolean stopped;
        RandomGenerator random = new Random(42);
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

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public Collection<String> grantedPermissions(Player player) {
            reads++;
            return permissions.getOrDefault(player.getUniqueId(), List.of());
        }

        @Override
        public boolean watchPermissions(Consumer<UUID> onPlayer, Runnable onEveryone) {
            this.onPlayer = onPlayer;
            this.onEveryone = onEveryone;
            return luckPerms;
        }

        @Override
        public void stopWatching() {
            stopped = true;
        }

        @Override
        public RandomGenerator random() {
            return random;
        }
    }

    static Player player(String name) {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(ModifiersTest.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private final FakeHost host = new FakeHost();
    private final Modifiers.Registry registry = new Modifiers.Registry();
    private final Modifiers modifiers = new Modifiers(host, registry, "RsGens");
    private final Player steve = player("Steve");

    private void grant(Player player, String... nodes) {
        host.permissions.put(player.getUniqueId(), List.of(nodes));
        modifiers.invalidate(player.getUniqueId());
    }

    // ---------------------------------------------------------------- permission nodes

    @Test
    void nodesParseWholeAndDecimalFactorsWithOrWithoutSource() {
        assertEquals(new PermissionModifiers.Node("money", "gens", 1.5), PermissionModifiers.parse("money.gens.1.5"));
        assertEquals(new PermissionModifiers.Node("money", "gens", 2), PermissionModifiers.parse("money.gens.2"));
        assertEquals(new PermissionModifiers.Node("money", null, 1.5), PermissionModifiers.parse("money.1.5"));
        assertEquals(new PermissionModifiers.Node("xp", null, 2), PermissionModifiers.parse("xp.2"));
        assertEquals(new PermissionModifiers.Node("drops", "envoys", 0.75), PermissionModifiers.parse("drops.envoys.0.75"));
        assertEquals(new PermissionModifiers.Node("clan-exp", "boss_fight", 10.25),
                PermissionModifiers.parse("clan-exp.boss_fight.10.25"));
        for (String bad : new String[] {"money", "money.gens", "money.gens.x", "money.gens.1.5.5", "money..2",
                ".gens.2", "money.10.2.5", "money.gens.2.", "money.ge ns.2", "money.*.2", "money.gens.-2"}) {
            assertNull(PermissionModifiers.parse(bad), bad);
        }
    }

    @Test
    void theHighestMatchingNodeWinsAndOthersAreIgnored() {
        grant(steve, "rsgens.modifier.money.1.5", "rsgens.modifier.money.gens.2", "rsgens.modifier.money.gens.1.25",
                "rsgens.modifier.xp.gens.3", "rsother.modifier.money.gens.9", "rsgens.modifier.money.gens.lots",
                "RSGENS.MODIFIER.MONEY.ENVOYS.1.75");
        assertEquals(2.0, modifiers.factor(steve, "money", "gens"));
        assertEquals(1.75, modifiers.factor(steve, "MONEY", "Envoys"));
        assertEquals(1.5, modifiers.factor(steve, Modifiers.MONEY, "votes"), "the node without source applies to every source");
        assertEquals(1.5, modifiers.factor(steve, Modifiers.MONEY, null));
        assertEquals(3.0, modifiers.factor(steve, Modifiers.XP, "gens"));
        assertEquals(1.0, modifiers.factor(steve, Modifiers.XP, "envoys"));
        assertEquals(1.0, modifiers.factor(steve, Modifiers.DROPS, "gens"), "a type is never a wildcard");
        assertEquals(1.0, modifiers.factor(null, Modifiers.MONEY, "gens"));
    }

    @Test
    void aSingleNodeBelowOneReduces() {
        grant(steve, "rsgens.modifier.money.gens.0.5");
        assertEquals(50.0, modifiers.apply(steve, Modifiers.MONEY, "gens", 100));
    }

    // ---------------------------------------------------------------- providers

    @Test
    void factorsOfEveryProviderMultiply() {
        grant(steve, "rsgens.modifier.money.gens.2");
        modifiers.register("event", (player, type, source) -> type.equals("money") ? 1.5 : 1);
        Modifiers other = new Modifiers(host, registry, "RsBoosters");
        other.register("booster", (player, type, source) -> "gens".equals(source) ? 3 : 1);
        assertEquals(9.0, modifiers.factor(steve, "money", "gens"), 1e-9);
        assertEquals(900.0, modifiers.apply(steve, "money", "gens", 100), 1e-9);
        assertEquals(4.5, other.factor(steve, "money", "gens"), 1e-9, "providers are shared, the permission prefix is not");
        assertEquals(1.5, modifiers.factor(steve, "money", "envoys"), 1e-9);
        // Replacing an id keeps one provider; shutting a plugin down removes its providers.
        modifiers.register("event", (player, type, source) -> 1.25);
        assertEquals(7.5, modifiers.factor(steve, "money", "gens"), 1e-9);
        other.shutdown();
        assertEquals(2.5, modifiers.factor(steve, "money", "gens"), 1e-9);
        modifiers.unregister("event");
        assertEquals(2.0, modifiers.factor(steve, "money", "gens"), 1e-9);
    }

    @Test
    void providersReceiveLowerCaseNames() {
        List<String> seen = new ArrayList<>();
        modifiers.register("spy", (player, type, source) -> {
            seen.add(type + "/" + source);
            return 1;
        });
        modifiers.factor(steve, "Money", "GENS");
        modifiers.factor(steve, "Money", null);
        assertEquals(List.of("money/gens", "money/null"), seen);
    }

    @Test
    void brokenProvidersCountAsOneAndNegativesAsZero() {
        modifiers.register("throws", (player, type, source) -> {
            throw new IllegalStateException("broken");
        });
        modifiers.register("nan", (player, type, source) -> Double.NaN);
        assertEquals(1.0, modifiers.factor(steve, "money", "gens"));
        assertEquals(1.0, modifiers.factor(steve, "money", "gens"));
        assertEquals(2, host.warnings.size(), "one warning per provider: " + host.warnings);
        modifiers.register("negative", (player, type, source) -> -2);
        assertEquals(0.0, modifiers.factor(steve, "money", "gens"));
    }

    @Test
    void withoutPermissionsOrProvidersAmountsComeBackUnchanged() {
        assertEquals(1.0, modifiers.factor(steve, "money", "gens"));
        assertEquals(250.0, modifiers.apply(steve, "money", "gens", 250));
        assertEquals(7, modifiers.drops(steve, "envoys", 7));
        assertEquals(12, modifiers.applyRolled(steve, Modifiers.XP, "gens", 12));
    }

    // ---------------------------------------------------------------- cache

    @Test
    void permissionsAreReadOncePerPlayerUntilTheTtlEnds() {
        grant(steve, "rsgens.modifier.money.2");
        for (int i = 0; i < 5; i++) {
            modifiers.factor(steve, "money", "gens");
        }
        assertEquals(1, host.reads);
        host.permissions.put(steve.getUniqueId(), List.of("rsgens.modifier.money.3"));
        assertEquals(2.0, modifiers.factor(steve, "money", "gens"), "still cached");
        host.now += PermissionModifiers.TTL_MILLIS;
        assertEquals(3.0, modifiers.factor(steve, "money", "gens"));
        assertEquals(2, host.reads);
    }

    @Test
    void invalidateAndLuckPermsEventsDropTheCache() {
        host.luckPerms = true;
        grant(steve, "rsgens.modifier.money.2");
        Player alex = player("Alex");
        host.permissions.put(alex.getUniqueId(), List.of("rsgens.modifier.money.4"));
        modifiers.factor(steve, "money", "gens");
        modifiers.factor(alex, "money", "gens");
        host.permissions.put(steve.getUniqueId(), List.of("rsgens.modifier.money.5"));
        host.permissions.put(alex.getUniqueId(), List.of("rsgens.modifier.money.6"));
        host.now += PermissionModifiers.TTL_MILLIS;
        assertEquals(2.0, modifiers.factor(steve, "money", "gens"), "a watched cache lives longer than the short TTL");
        host.onPlayer.accept(steve.getUniqueId());
        assertEquals(5.0, modifiers.factor(steve, "money", "gens"));
        assertEquals(4.0, modifiers.factor(alex, "money", "gens"), "only that player was dropped");
        host.onEveryone.run();
        assertEquals(6.0, modifiers.factor(alex, "money", "gens"));
        host.now += PermissionModifiers.WATCHED_TTL_MILLIS;
        host.permissions.put(alex.getUniqueId(), List.of());
        assertEquals(1.0, modifiers.factor(alex, "money", "gens"), "the long TTL still ends");
        modifiers.shutdown();
        assertTrue(host.stopped);
    }

    @Test
    void aPermissionReadThatFailsIsNotCachedAndCountsAsOne() {
        ModifierHost failing = new FakeHost() {
            @Override
            public Collection<String> grantedPermissions(Player player) {
                reads++;
                throw new java.util.ConcurrentModificationException();
            }
        };
        Modifiers broken = new Modifiers(failing, registry, "RsGens");
        assertEquals(1.0, broken.factor(steve, "money", "gens"));
        assertEquals(1.0, broken.factor(steve, "money", "gens"));
        assertEquals(2, ((FakeHost) failing).reads);
    }

    // ---------------------------------------------------------------- rolls

    @Test
    void wholeValuesNeverRollAndBadValuesAreZero() {
        Random random = new Random(1);
        for (int i = 0; i < 100; i++) {
            assertEquals(4, Modifiers.roll(4.0, random));
        }
        assertEquals(0, Modifiers.roll(0, random));
        assertEquals(0, Modifiers.roll(-2.5, random));
        assertEquals(0, Modifiers.roll(Double.NaN, random));
        assertEquals(Long.MAX_VALUE, Modifiers.roll(Double.POSITIVE_INFINITY, random));
    }

    @Test
    void fractionsRollUpWithTheirOwnChance() {
        Random random = new Random(7);
        int up = 0;
        int trials = 20_000;
        for (int i = 0; i < trials; i++) {
            long rolled = Modifiers.roll(2.25, random);
            assertTrue(rolled == 2 || rolled == 3, "rolled " + rolled);
            if (rolled == 3) {
                up++;
            }
        }
        assertEquals(0.25, up / (double) trials, 0.02);
    }

    @Test
    void dropsAreBoostedAndTheFractionIsRolledWithTheSeed() {
        grant(steve, "rsgens.modifier.drops.envoys.1.5");
        host.random = new Random(42);
        Random expected = new Random(42);
        int total = 0;
        for (int i = 0; i < 200; i++) {
            int drops = modifiers.drops(steve, "envoys", 3);
            assertEquals(expected.nextDouble() < 0.5 ? 5 : 4, drops);
            total += drops;
        }
        assertEquals(4.5, total / 200.0, 0.2);
        assertEquals(0, modifiers.drops(steve, "envoys", 0));
        assertEquals(3, modifiers.drops(steve, "gens", 3), "no node for that source");
    }
}
