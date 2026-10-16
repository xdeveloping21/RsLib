package com.rs.lib.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * {@code [firework] [key=value...]}: a firework rocket launched at the player's feet, seen by
 * everyone nearby. It never hurts anyone: RsLib cancels the damage of its explosion.
 *
 * <p>Options, any order and case: {@code type=} one of {@code BALL}, {@code BALL_LARGE},
 * {@code STAR}, {@code BURST}, {@code CREEPER} ({@code BALL} when absent); {@code colors=}
 * (also {@code color=}) and {@code fade=}, colors written like {@code [particle]} ones
 * ({@code #RRGGBB} or {@code R,G,B}), several separated with {@code ;} or, when all are
 * {@code #RRGGBB}, with {@code ,} (white when absent, no fade when absent); {@code power=} a
 * whole number from 0 to 127, how high it flies before it explodes (1 when absent). A wrong
 * value uses its default; a word that is not an option, an unknown option or a wrong value is
 * reported once per argument and ignored. Each argument is parsed once.</p>
 */
final class FireworkAction implements ActionHandler {

    /** Marks the rockets of this action, whose explosion must not hurt. */
    static final NamespacedKey HARMLESS = new NamespacedKey("rslib", "harmless_firework");
    static final int DEFAULT_POWER = 1;
    static final int MAX_POWER = 127;
    private static final int MAX_CACHED = 512;

    private final BiConsumer<String, Supplier<String>> warn;
    private final Map<String, Parsed> cache = new ConcurrentHashMap<>();

    FireworkAction(BiConsumer<String, Supplier<String>> warn) {
        this.warn = warn;
    }

    @Override
    public void run(Player player, String arg, ActionContext context) {
        if (player == null) {
            return;
        }
        Parsed parsed = cache.get(arg);
        if (parsed == null) {
            parsed = parse(arg, message -> warn.accept("firework:" + arg, () -> message));
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(arg, parsed);
        }
        Firework rocket = player.getWorld().spawn(player.getLocation(), Firework.class);
        rocket.getPersistentDataContainer().set(HARMLESS, PersistentDataType.BYTE, (byte) 1);
        FireworkMeta meta = rocket.getFireworkMeta();
        meta.addEffect(parsed.effect());
        meta.setPower(parsed.power());
        rocket.setFireworkMeta(meta);
    }

    /** Reads an argument; problems go to {@code warn} and the value keeps its default. */
    static Parsed parse(String arg, Consumer<String> warn) {
        FireworkEffect.Type type = FireworkEffect.Type.BALL;
        List<Color> colors = List.of(Color.WHITE);
        List<Color> fades = List.of();
        int power = DEFAULT_POWER;
        for (String word : Builtins.words(arg)) {
            int eq = word.indexOf('=');
            if (eq <= 0) {
                warn.accept("'" + word + "' in [firework] " + arg + " is not an option (key=value); it is ignored.");
                continue;
            }
            String key = word.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = word.substring(eq + 1);
            switch (key) {
                case "type" -> {
                    FireworkEffect.Type read = type(value);
                    if (read == null) {
                        warn.accept("[firework] " + arg + ": type '" + value
                                + "' is not BALL, BALL_LARGE, STAR, BURST or CREEPER; using BALL.");
                    } else {
                        type = read;
                    }
                }
                case "color", "colors" -> colors = colors(value, List.of(Color.WHITE), "white", arg, warn);
                case "fade" -> fades = colors(value, List.of(), "no fade", arg, warn);
                case "power" -> {
                    double read = RequirementEngine.number(value);
                    if (Double.isNaN(read) || read != Math.rint(read) || read < 0 || read > MAX_POWER) {
                        warn.accept("[firework] " + arg + ": power '" + value + "' is not a whole number from 0 to "
                                + MAX_POWER + "; using " + DEFAULT_POWER + ".");
                    } else {
                        power = (int) read;
                    }
                }
                default -> warn.accept("[firework] " + arg + ": option '" + key + "=' is unknown; it is ignored.");
            }
        }
        FireworkEffect.Builder effect = FireworkEffect.builder().with(type).withColor(colors);
        if (!fades.isEmpty()) {
            effect.withFade(fades);
        }
        return new Parsed(effect.build(), power);
    }

    /** The type a name stands for (any case, {@code -} for {@code _}), or null. */
    static FireworkEffect.Type type(String name) {
        try {
            return FireworkEffect.Type.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** The colors of {@code value}; {@code fallback} (reported) when one cannot be read. */
    private static List<Color> colors(String value, List<Color> fallback, String what, String arg,
                                      Consumer<String> warn) {
        List<Color> out = new ArrayList<>(3);
        for (String part : colorParts(value)) {
            try {
                Color color = ParticleAction.color(part, null);
                if (color != null) {
                    out.add(color);
                }
            } catch (IllegalArgumentException invalid) {
                warn.accept("[firework] " + arg + ": " + invalid.getMessage() + "; using " + what + ".");
                return fallback;
            }
        }
        if (out.isEmpty()) {
            warn.accept("[firework] " + arg + ": '" + value + "' holds no color; using " + what + ".");
            return fallback;
        }
        return out;
    }

    /** {@code a;b;c}, or {@code #a,#b} when every piece is hex, or one {@code R,G,B} color. */
    static List<String> colorParts(String value) {
        if (value.indexOf(';') >= 0) {
            return Builtins.split(value, ';');
        }
        List<String> pieces = Builtins.split(value, ',');
        for (String piece : pieces) {
            if (!piece.trim().startsWith("#")) {
                return List.of(value);
            }
        }
        return pieces;
    }

    /** Cancels the damage of the rockets this action launched. Registered once by RsLib. */
    static final class Harmless implements Listener {

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onDamage(EntityDamageByEntityEvent event) {
            if (event.getDamager() instanceof Firework rocket
                    && rocket.getPersistentDataContainer().has(HARMLESS, PersistentDataType.BYTE)) {
                event.setCancelled(true);
            }
        }
    }

    /** A parsed argument: the effect and the power. */
    record Parsed(FireworkEffect effect, int power) {
    }
}
