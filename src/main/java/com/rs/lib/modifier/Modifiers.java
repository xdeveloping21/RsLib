package com.rs.lib.modifier;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.random.RandomGenerator;

import org.bukkit.entity.Player;

import com.rs.lib.Rs;

/**
 * Multipliers on what players earn, reached with {@code rs().modifiers()}.
 *
 * <pre>
 * double paid = rs().modifiers().apply(player, Modifiers.MONEY, "gens", 250);
 * int items = rs().modifiers().drops(player, "envoys", 3);
 * rs().modifiers().register("weekend", (player, type, source) -&gt; weekend ? 2 : 1);
 * </pre>
 *
 * <ul>
 *   <li>A multiplier has a <b>type</b> ({@link #MONEY}, {@link #XP}, {@link #DROPS} or any
 *   lower-case name a plugin uses) and a <b>source</b>, what pays ({@code gens},
 *   {@code envoys}). Both are matched in lower case.</li>
 *   <li>Built in, by permission: {@code <plugin>.modifier.<type>.<source>.<factor>} and
 *   {@code <plugin>.modifier.<type>.<factor>} for every source, where {@code <plugin>} is the
 *   plugin's name in lower case and the factor is {@code 2}, {@code 1.5}, ... Among a player's
 *   matching nodes the highest wins.</li>
 *   <li>Plugins add providers with {@link #register}. A provider registered by one plugin
 *   applies to the payouts of every plugin, and is removed when its plugin is disabled.</li>
 *   <li>The final multiplier is the permission factor times every provider's factor. A
 *   provider that throws or answers something that is not a number counts as 1; below 0
 *   counts as 0. Without permissions or providers it is 1 and amounts come back unchanged.</li>
 *   <li>Whole amounts (items, experience) keep the fraction with a roll: 3 items at x1.5 are
 *   4.5, which is 4 or 5 with even odds.</li>
 *   <li>Safe from any thread. A player's permission nodes are cached: dropped when they quit,
 *   when LuckPerms reports a change of their permissions or of a group, and after 60 seconds
 *   at most; without LuckPerms, after 5 seconds. {@link #invalidate} drops them at once.</li>
 * </ul>
 */
public final class Modifiers {

    /** Money earned. */
    public static final String MONEY = "money";
    /** Experience earned. */
    public static final String XP = "xp";
    /** Items dropped or given. */
    public static final String DROPS = "drops";

    /** A provider and who registered it. */
    record Registration(String owner, String id, ModifierProvider provider) {
    }

    /** Providers of every plugin. */
    static final class Registry {

        private final CopyOnWriteArrayList<Registration> entries = new CopyOnWriteArrayList<>();

        synchronized void put(String owner, String id, ModifierProvider provider) {
            Registration added = new Registration(owner, id, provider);
            for (int i = 0; i < entries.size(); i++) {
                Registration current = entries.get(i);
                if (current.owner.equals(owner) && current.id.equals(id)) {
                    entries.set(i, added);
                    return;
                }
            }
            entries.add(added);
        }

        void remove(String owner, String id) {
            entries.removeIf(r -> r.owner.equals(owner) && r.id.equals(id));
        }

        void removeOwner(String owner) {
            entries.removeIf(r -> r.owner.equals(owner));
        }

        Iterable<Registration> all() {
            return entries;
        }
    }

    private static final Registry SHARED = new Registry();

    private final ModifierHost host;
    private final Registry registry;
    private final String owner;
    private final PermissionModifiers permissions;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Created by the context. */
    public Modifiers(Rs rs) {
        this(ModifierHost.of(rs), SHARED, rs.plugin().getName());
        rs.onQuit(player -> invalidate(player.getUniqueId()));
    }

    /** {@code owner} is the plugin's name; its lower case is the permission prefix. */
    Modifiers(ModifierHost host, Registry registry, String owner) {
        this.host = host;
        this.registry = registry;
        this.owner = owner;
        this.permissions = new PermissionModifiers(host, owner);
    }

    // ---------------------------------------------------------------- asking

    /**
     * The multiplier for {@code player} on {@code type} from {@code source} (null for none: only
     * permission nodes without a source match then); 1 for a null player.
     */
    public double factor(Player player, String type, String source) {
        Objects.requireNonNull(type, "type");
        if (player == null) {
            return 1.0;
        }
        String t = type.toLowerCase(Locale.ROOT);
        String s = source == null ? null : source.toLowerCase(Locale.ROOT);
        double result = ask("permissions", permissions, player, t, s);
        for (Registration registration : registry.all()) {
            result *= ask(registration.owner + ":" + registration.id, registration.provider, player, t, s);
        }
        return result;
    }

    /** {@code amount} times the multiplier, for money or any amount that may have decimals. */
    public double apply(Player player, String type, String source, double amount) {
        return amount * factor(player, type, source);
    }

    /**
     * {@code amount} times the multiplier as a whole number, the fraction kept with a roll
     * (experience points, items). An amount of 0 or below comes back unchanged.
     */
    public long applyRolled(Player player, String type, String source, long amount) {
        if (amount <= 0) {
            return amount;
        }
        return roll(amount * factor(player, type, source), host.random());
    }

    /** {@link #applyRolled} for {@link #DROPS}: how many items to give instead of {@code amount}. */
    public int drops(Player player, String source, int amount) {
        return (int) Math.min(Integer.MAX_VALUE, applyRolled(player, DROPS, source, amount));
    }

    /**
     * {@code value} as a whole number: its whole part, plus one with a chance equal to its
     * fraction (2.25 is 3 one time in four). 0 for a value that is not a number above 0.
     */
    public static long roll(double value, RandomGenerator random) {
        if (!(value > 0)) {
            return 0;
        }
        if (value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long whole = (long) Math.floor(value);
        double fraction = value - whole;
        return fraction > 0 && random.nextDouble() < fraction ? whole + 1 : whole;
    }

    // ---------------------------------------------------------------- providers

    /**
     * Adds a provider under {@code id}, or replaces this plugin's provider with that id. It
     * applies to the payouts of every plugin until {@link #unregister} or until this plugin is
     * disabled.
     */
    public void register(String id, ModifierProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        registry.put(owner, id, provider);
    }

    /** Removes this plugin's provider registered under {@code id}. */
    public void unregister(String id) {
        registry.remove(owner, id);
    }

    /** Drops the cached permission nodes of {@code player}; they are read again on the next ask. */
    public void invalidate(UUID player) {
        if (player != null) {
            permissions.invalidate(player);
        }
    }

    /** Called by the context when the plugin shuts down: its providers go away and the cache is emptied. */
    public void shutdown() {
        registry.removeOwner(owner);
        host.stopWatching();
        permissions.invalidateAll();
    }

    // ---------------------------------------------------------------- internals

    private double ask(String who, ModifierProvider provider, Player player, String type, String source) {
        double factor;
        try {
            factor = provider.factor(player, type, source);
        } catch (RuntimeException | LinkageError e) {
            if (warned.add("throw:" + who)) {
                host.logger().warning("The modifier provider " + who + " failed (" + e + "); it counts as x1.");
            }
            return 1.0;
        }
        if (!Double.isFinite(factor)) {
            if (warned.add("nan:" + who)) {
                host.logger().warning("The modifier provider " + who + " answered " + factor + "; it counts as x1.");
            }
            return 1.0;
        }
        return Math.max(0, factor);
    }
}
