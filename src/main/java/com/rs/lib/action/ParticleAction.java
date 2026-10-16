package com.rs.lib.action;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code [particle] TYPE [count] [offX offY offZ] [extra] [key=value...]}: particles at the
 * player, one block up, seen by everyone nearby.
 *
 * <p>The offsets are read when four or more numbers follow the type (two or three are
 * reported and ignored: all three or none), the extra (speed) when five or more do. Options:
 * {@code color=#RRGGBB} or {@code color=R,G,B} (red when absent or invalid), {@code size=}
 * (1.0), {@code to=} (the second color of a dust transition, the {@code color=} one by default),
 * {@code block=MATERIAL} and {@code item=MATERIAL}, required by the particles that show a block
 * or an item (the line is skipped without a valid one). An unknown option, or one the particle
 * cannot use, is reported and ignored. {@code REDSTONE} and {@code DUST} name each other (the
 * constant was renamed in 1.20.5), and so do {@code BLOCK_CRACK}/{@code BLOCK} and
 * {@code ITEM_CRACK}/{@code ITEM}; using the other name logs one notice. Each argument is
 * parsed and resolved once; its first problem is reported once.</p>
 */
final class ParticleAction implements ActionHandler {

    private static final int MAX_CACHED = 512;
    private static final Map<String, String> ALIASES = Map.of(
            "REDSTONE", "DUST", "DUST", "REDSTONE",
            "BLOCK_CRACK", "BLOCK", "BLOCK", "BLOCK_CRACK",
            "ITEM_CRACK", "ITEM", "ITEM", "ITEM_CRACK");
    private static final Ready NOTHING = new Ready(null, null, null);
    /** What {@link #data} gives when the line must be skipped. */
    static final Object SKIP = new Object();
    private static final Set<String> OPTIONS = Set.of("color", "size", "to", "block", "item");

    private final BiConsumer<String, Supplier<String>> warn;
    private final BiConsumer<String, Supplier<String>> notice;
    private final Map<String, Ready> cache = new ConcurrentHashMap<>();

    ParticleAction(BiConsumer<String, Supplier<String>> warn, BiConsumer<String, Supplier<String>> notice) {
        this.warn = warn;
        this.notice = notice;
    }

    @Override
    public void run(Player player, String arg, ActionContext context) {
        if (player == null) {
            return;
        }
        Ready ready = cache.get(arg);
        if (ready == null) {
            ready = resolve(arg);
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(arg, ready);
        }
        if (ready == NOTHING) {
            return;
        }
        Parsed p = ready.parsed;
        Location at = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(ready.particle, at, p.count, p.offX, p.offY, p.offZ, p.extra, ready.data);
    }

    private Ready resolve(String arg) {
        Consumer<String> report = message -> warn.accept("particle:" + arg, () -> message);
        Parsed parsed = parse(arg, report);
        if (parsed == null) {
            return NOTHING;
        }
        Particle particle = particle(parsed.type);
        if (particle == null) {
            report.accept("[particle] " + arg + ": there is no particle '" + parsed.type + "'; nothing shows.");
            return NOTHING;
        }
        String written = constant(parsed.type);
        if (!written.equals(particle.name())) {
            notice.accept("particle-alias:" + written, () -> "[particle] " + written + " is called " + particle.name()
                    + " on this server; using " + particle.name() + ".");
        }
        Object data = data(particle, parsed.options, message -> report.accept("[particle] " + arg + ": " + message));
        return data == SKIP ? NOTHING : new Ready(particle, parsed, data);
    }

    /** The words of an argument, read without any lookup; null (reported) when there is no type. */
    static Parsed parse(String arg, Consumer<String> warn) {
        List<String> words = Builtins.words(arg);
        if (words.isEmpty()) {
            warn.accept("An action [particle] has no particle type; nothing shows.");
            return null;
        }
        double[] numbers = new double[5];
        int positional = 0;
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            int eq = word.indexOf('=');
            if (eq > 0) {
                options.put(word.substring(0, eq).toLowerCase(Locale.ROOT), word.substring(eq + 1));
                continue;
            }
            double value = RequirementEngine.number(word);
            if (Double.isNaN(value)) {
                warn.accept("'" + word + "' in [particle] " + arg + " is not a number; it is ignored.");
                continue;
            }
            if (positional < numbers.length) {
                numbers[positional] = value;
            }
            positional++;
        }
        int count = positional >= 1 ? (int) Math.max(0, Math.min(Integer.MAX_VALUE, numbers[0])) : 1;
        boolean offsets = positional >= 4;
        if (positional == 2 || positional == 3) {
            warn.accept("[particle] " + arg + ": the offsets need all three numbers (offX offY offZ); they are"
                    + " ignored.");
        }
        return new Parsed(words.get(0), count,
                offsets ? numbers[1] : 0, offsets ? numbers[2] : 0, offsets ? numbers[3] : 0,
                positional >= 5 ? numbers[4] : 0, Map.copyOf(options));
    }

    /** The particle a name stands for (any case, {@code minecraft:} allowed, old and new names), or null. */
    static Particle particle(String name) {
        String constant = constant(name);
        Particle found = byName(constant);
        if (found == null && ALIASES.containsKey(constant)) {
            found = byName(ALIASES.get(constant));
        }
        return found;
    }

    /** The constant name a particle is written as: upper case, without {@code minecraft:}. */
    private static String constant(String name) {
        String bare = name.regionMatches(true, 0, "minecraft:", 0, 10) ? name.substring(10) : name;
        return bare.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static Particle byName(String constant) {
        try {
            return Particle.valueOf(constant);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * The data {@code particle} needs, built from the options, or {@link #SKIP} when the line
     * cannot show (a block or item particle without a valid material). Problems go to
     * {@code warn}; a bad color or size uses its default.
     */
    static Object data(Particle particle, Map<String, String> options, Consumer<String> warn) {
        Class<?> type = particle.getDataType();
        boolean dust = type == Particle.DustOptions.class;
        boolean transition = type == Particle.DustTransition.class;
        for (String option : options.keySet()) {
            boolean usable = switch (option) {
                case "color" -> dust || transition || type == Color.class;
                case "size" -> dust || transition || type == Float.class;
                case "to" -> transition;
                case "block" -> type == BlockData.class;
                case "item" -> type == ItemStack.class;
                default -> false;
            };
            if (!usable) {
                warn.accept("option '" + option + "=' " + (OPTIONS.contains(option)
                        ? "does not apply to " + particle.name() : "is unknown") + "; it is ignored.");
            }
        }
        if (type == Void.class) {
            return null;
        }
        if (dust) {
            return new Particle.DustOptions(color(options, "color", Color.RED, warn), size(options, 1f, warn));
        }
        if (transition) {
            Color from = color(options, "color", Color.RED, warn);
            return new Particle.DustTransition(from, color(options, "to", from, warn), size(options, 1f, warn));
        }
        if (type == BlockData.class) {
            Material material = material(particle, options.get("block"), true, warn);
            return material == null ? SKIP : material.createBlockData();
        }
        if (type == ItemStack.class) {
            Material material = material(particle, options.get("item"), false, warn);
            return material == null ? SKIP : new ItemStack(material);
        }
        if (type == Color.class) {
            return color(options, "color", Color.RED, warn);
        }
        if (type == Float.class) {
            return size(options, 0f, warn);
        }
        if (type == Integer.class) {
            return 0;
        }
        warn.accept(particle.name() + " needs data of type " + type.getSimpleName()
                + ", which an action line cannot give; nothing shows.");
        return SKIP;
    }

    /** The color option {@code key}; {@code fallback} when absent or invalid (reported). */
    private static Color color(Map<String, String> options, String key, Color fallback, Consumer<String> warn) {
        try {
            return color(options.get(key), fallback);
        } catch (IllegalArgumentException invalid) {
            warn.accept(invalid.getMessage() + "; using " + (fallback.equals(Color.RED) ? "red" : "the color= one") + ".");
            return fallback;
        }
    }

    /** {@code #RRGGBB}, {@code RRGGBB} or {@code R,G,B}; {@code fallback} when absent. */
    static Color color(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.trim();
        if (v.indexOf(',') >= 0) {
            List<String> parts = Builtins.split(v, ',');
            if (parts.size() != 3) {
                throw new IllegalArgumentException("color '" + value + "' needs three numbers R,G,B");
            }
            try {
                return Color.fromRGB(Integer.parseInt(parts.get(0).trim()), Integer.parseInt(parts.get(1).trim()),
                        Integer.parseInt(parts.get(2).trim()));
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("color '" + value + "' is not three whole numbers");
            }
        }
        String hex = v.startsWith("#") ? v.substring(1) : v;
        if (hex.length() != 6) {
            throw new IllegalArgumentException("color '" + value + "' is not #RRGGBB");
        }
        try {
            return Color.fromRGB(Integer.parseInt(hex, 16));
        } catch (NumberFormatException notHex) {
            throw new IllegalArgumentException("color '" + value + "' is not #RRGGBB");
        }
    }

    /** The {@code size=} option; {@code fallback} when absent, 1.0 when not a positive number (reported). */
    private static float size(Map<String, String> options, float fallback, Consumer<String> warn) {
        String value = options.get("size");
        if (value == null) {
            return fallback;
        }
        double size = RequirementEngine.number(value);
        if (Double.isNaN(size) || size <= 0) {
            warn.accept("size '" + value + "' is not a positive number; using 1.0.");
            return 1f;
        }
        return (float) size;
    }

    /** The material of {@code block=} or {@code item=}; null (reported) when missing or wrong. */
    private static Material material(Particle particle, String value, boolean block, Consumer<String> warn) {
        String option = block ? "block=" : "item=";
        if (value == null || value.isBlank()) {
            warn.accept(particle.name() + " needs " + option + "MATERIAL; nothing shows.");
            return null;
        }
        Material material = Material.matchMaterial(value.trim());
        if (material == null || (block ? !material.isBlock() : !material.isItem())) {
            warn.accept(option + value + " is not " + (block ? "a block" : "an item") + "; nothing shows.");
            return null;
        }
        return material;
    }

    /** An argument read into words: type, count, offsets, extra and options. */
    record Parsed(String type, int count, double offX, double offY, double offZ, double extra,
                  Map<String, String> options) {
    }

    /** A parsed argument with its particle and data resolved. */
    private record Ready(Particle particle, Parsed parsed, Object data) {
    }
}
