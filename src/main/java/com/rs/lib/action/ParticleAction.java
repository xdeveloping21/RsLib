package com.rs.lib.action;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>The offsets are read when four or more numbers follow the type, the extra (speed) when
 * five or more do. Options: {@code color=#RRGGBB} or {@code color=R,G,B}, {@code size=},
 * {@code to=} (the second color of a dust transition), {@code block=MATERIAL},
 * {@code item=MATERIAL}. {@code REDSTONE} and {@code DUST} name each other (the constant was
 * renamed in 1.20.5), and so do {@code BLOCK_CRACK}/{@code BLOCK} and {@code ITEM_CRACK}/
 * {@code ITEM}. Each argument is parsed and resolved once; problems are reported once.</p>
 */
final class ParticleAction implements ActionHandler {

    private static final int MAX_CACHED = 512;
    private static final Map<String, String> ALIASES = Map.of(
            "REDSTONE", "DUST", "DUST", "REDSTONE",
            "BLOCK_CRACK", "BLOCK", "BLOCK", "BLOCK_CRACK",
            "ITEM_CRACK", "ITEM", "ITEM", "ITEM_CRACK");
    private static final Ready NOTHING = new Ready(null, null, null);

    private final BiConsumer<String, Supplier<String>> warn;
    private final Map<String, Ready> cache = new ConcurrentHashMap<>();

    ParticleAction(BiConsumer<String, Supplier<String>> warn) {
        this.warn = warn;
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
        Object data;
        try {
            data = data(particle, parsed.options, report);
        } catch (IllegalArgumentException wrongData) {
            report.accept("[particle] " + arg + ": " + wrongData.getMessage() + "; nothing shows.");
            return NOTHING;
        }
        return data == NOTHING ? NOTHING : new Ready(particle, parsed, data);
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
        return new Parsed(words.get(0), count,
                offsets ? numbers[1] : 0, offsets ? numbers[2] : 0, offsets ? numbers[3] : 0,
                positional >= 5 ? numbers[4] : 0, Map.copyOf(options));
    }

    /** The particle a name stands for (any case, {@code minecraft:} allowed, old and new names), or null. */
    static Particle particle(String name) {
        String bare = name.regionMatches(true, 0, "minecraft:", 0, 10) ? name.substring(10) : name;
        String constant = bare.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        Particle found = byName(constant);
        if (found == null && ALIASES.containsKey(constant)) {
            found = byName(ALIASES.get(constant));
        }
        return found;
    }

    private static Particle byName(String constant) {
        try {
            return Particle.valueOf(constant);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** The data {@code particle} needs, built from the options; {@link #NOTHING} when it cannot be built. */
    private static Object data(Particle particle, Map<String, String> options, Consumer<String> warn) {
        Class<?> type = particle.getDataType();
        if (type == Void.class) {
            return null;
        }
        float size = options.containsKey("size") ? size(options.get("size")) : 1f;
        if (type == Particle.DustOptions.class) {
            return new Particle.DustOptions(color(options.get("color"), Color.RED), size);
        }
        if (type == Particle.DustTransition.class) {
            Color from = color(options.get("color"), Color.RED);
            return new Particle.DustTransition(from, color(options.get("to"), from), size);
        }
        if (type == BlockData.class) {
            return material(options.get("block"), true).createBlockData();
        }
        if (type == ItemStack.class) {
            return new ItemStack(material(options.get("item"), false));
        }
        if (type == Color.class) {
            return color(options.get("color"), Color.WHITE);
        }
        if (type == Float.class) {
            return options.containsKey("size") ? size : 0f;
        }
        if (type == Integer.class) {
            return 0;
        }
        warn.accept("[particle] " + particle.name() + " needs data of type " + type.getSimpleName()
                + ", which an action line cannot give; nothing shows.");
        return NOTHING;
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

    private static float size(String value) {
        double size = RequirementEngine.number(value);
        if (Double.isNaN(size) || size <= 0) {
            throw new IllegalArgumentException("size '" + value + "' is not a positive number");
        }
        return (float) size;
    }

    private static Material material(String value, boolean block) {
        if (value == null || value.isBlank()) {
            return Material.STONE;
        }
        Material material = Material.matchMaterial(value.trim());
        if (material == null || (block ? !material.isBlock() : !material.isItem())) {
            throw new IllegalArgumentException("'" + value + "' is not " + (block ? "a block" : "an item"));
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
