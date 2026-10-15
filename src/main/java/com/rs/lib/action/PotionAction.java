package com.rs.lib.action;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * {@code [potion] EFFECT [seconds] [amplifier]}: gives the player an effect, 10 seconds and
 * amplifier 0 by default. The effect is a registry key ({@code minecraft:speed},
 * {@code speed}) or an old Bukkit name ({@code INCREASE_DAMAGE}). Each argument is resolved
 * once; problems are reported once.
 */
final class PotionAction implements ActionHandler {

    static final double DEFAULT_SECONDS = 10;
    private static final int MAX_CACHED = 512;
    private static final PotionEffect NOTHING = null;

    private final BiConsumer<String, Supplier<String>> warn;
    private final Map<String, Ready> cache = new ConcurrentHashMap<>();

    PotionAction(BiConsumer<String, Supplier<String>> warn) {
        this.warn = warn;
    }

    @Override
    public void run(Player player, String arg, ActionContext context) {
        if (player == null) {
            return;
        }
        Ready ready = cache.get(arg);
        if (ready == null) {
            ready = new Ready(resolve(arg));
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(arg, ready);
        }
        if (ready.effect != NOTHING) {
            player.addPotionEffect(ready.effect);
        }
    }

    private PotionEffect resolve(String arg) {
        Consumer<String> report = message -> warn.accept("potion:" + arg, () -> message);
        Parsed parsed = parse(arg, report);
        if (parsed == null) {
            return NOTHING;
        }
        PotionEffectType type = effect(parsed.effect);
        if (type == null) {
            report.accept("[potion] " + arg + ": there is no effect '" + parsed.effect + "'; nothing given.");
            return NOTHING;
        }
        return new PotionEffect(type, parsed.ticks, parsed.amplifier);
    }

    /** The words of an argument: effect name, duration in ticks (at least 1) and amplifier (0 to 255). */
    static Parsed parse(String arg, Consumer<String> warn) {
        List<String> words = Builtins.words(arg);
        if (words.isEmpty()) {
            warn.accept("An action [potion] has no effect; nothing given.");
            return null;
        }
        double seconds = DEFAULT_SECONDS;
        if (words.size() > 1) {
            double value = RequirementEngine.number(words.get(1));
            if (Double.isNaN(value) || value <= 0) {
                warn.accept("[potion] " + arg + ": '" + words.get(1) + "' is not a number of seconds; using 10.");
            } else {
                seconds = value;
            }
        }
        int amplifier = 0;
        if (words.size() > 2) {
            try {
                amplifier = Math.max(0, Math.min(255, Integer.parseInt(words.get(2))));
            } catch (NumberFormatException notANumber) {
                warn.accept("[potion] " + arg + ": '" + words.get(2) + "' is not an amplifier; using 0.");
            }
        }
        long ticks = Math.round(seconds * 20);
        return new Parsed(words.get(0), (int) Math.max(1, Math.min(Integer.MAX_VALUE, ticks)), amplifier);
    }

    /**
     * The effect {@code name} stands for, or null. {@code getByKey} and {@code getByName} are
     * the lookups every version from 1.20 to 1.21.x has; newer servers mark them deprecated.
     */
    @SuppressWarnings({"deprecation", "removal"})
    static PotionEffectType effect(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        PotionEffectType type = key == null ? null : PotionEffectType.getByKey(key);
        return type != null ? type : PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
    }

    /** An argument read into words. */
    record Parsed(String effect, int ticks, int amplifier) {
    }

    /** A resolved argument; {@code effect} is null when nothing is given. */
    private record Ready(PotionEffect effect) {
    }
}
