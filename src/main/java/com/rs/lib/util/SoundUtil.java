package com.rs.lib.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Sounds written in configs: {@code "SOUND [volume] [pitch]"}.
 *
 * <p>The sound is a Bukkit constant name ({@code ENTITY_PLAYER_LEVELUP}) or a key
 * ({@code minecraft:entity.player.levelup}, {@code entity.player.levelup}); volume and pitch
 * default to 1. {@code null}, an empty text or {@code none} (any case) is silence on purpose.
 * The constant name is tried first (upper case, without {@code minecraft:}, dots read as
 * underscores), then the sound registry by key. An id with an explicit namespace that is not in
 * the registry ({@code mypack:ui.click}) is a resource pack sound and is sent to the client by
 * name. An id without a namespace that matches nothing is a typo: it does not play and is
 * reported once per id. A volume or pitch that is not a non-negative number reads as 1.0, with
 * one report for the spec.</p>
 *
 * <p>Works the same on servers where {@link Sound} is an enum (1.20) and where it is a
 * registry-backed interface (1.21.3+): the code never calls a method on a {@code Sound} value
 * through the {@code Sound} type, which would fail to link across that change. Resolved ids
 * are cached. {@code play} and {@code playAt} belong on the thread that owns the player or
 * location; {@link #resolves(String)} can run on any thread once the server is up.</p>
 */
public final class SoundUtil {

    private static final Logger LOG = Logger.getLogger("RsLib");
    private static final int MAX_CACHED = 2048;
    private static final String MINECRAFT = "minecraft:";

    /** Resolved ids, as written. */
    private static final Map<String, Target> RESOLVED = new ConcurrentHashMap<>();
    /** Parsed and resolved specs, as written, for the play methods. */
    private static final Map<String, Ready> READY = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SoundUtil() {
    }

    /** Plays {@code spec} to {@code player} alone, at the player's location; null player or silence does nothing. */
    public static void play(Player player, String spec) {
        if (player == null) {
            return;
        }
        Ready ready = ready(spec);
        if (ready.sound() != null) {
            player.playSound(player.getLocation(), ready.sound(), ready.volume(), ready.pitch());
        } else if (ready.custom() != null) {
            player.playSound(player.getLocation(), ready.custom(), ready.volume(), ready.pitch());
        }
    }

    /** Plays {@code spec} at {@code location} for everyone nearby; a location without a world does nothing. */
    public static void playAt(Location location, String spec) {
        if (location == null) {
            return;
        }
        Ready ready = ready(spec);
        if (ready == Ready.NOTHING) {
            return;
        }
        World world;
        try {
            world = location.getWorld();
        } catch (IllegalArgumentException unloaded) {
            return;
        }
        if (world == null) {
            return;
        }
        if (ready.sound() != null) {
            world.playSound(location, ready.sound(), ready.volume(), ready.pitch());
        } else {
            world.playSound(location, ready.custom(), ready.volume(), ready.pitch());
        }
    }

    /**
     * True when {@code spec} plays something: a known sound or a namespaced resource pack id.
     * Silence ({@code null}, empty, {@code none}) also answers true, because it is valid on
     * purpose. Reports nothing, so a plugin can validate its config with its own messages.
     */
    public static boolean resolves(String spec) {
        Spec parsed = parse(spec, null);
        return parsed == null || resolve(parsed.id(), false) != Target.UNKNOWN;
    }

    /** Lets every problem be reported again (after a reload) and drops the caches. */
    public static void forgetWarnings() {
        WARNED.clear();
        RESOLVED.clear();
        READY.clear();
    }

    /** What {@code spec} plays, parsed and resolved once (problems reported the first time). */
    private static Ready ready(String spec) {
        if (spec == null) {
            return Ready.NOTHING;
        }
        Ready known = READY.get(spec);
        if (known != null) {
            return known;
        }
        Spec parsed = parse(spec, SoundUtil::warnOnce);
        Target target = parsed == null ? Target.UNKNOWN : resolve(parsed.id(), true);
        Ready ready = target == Target.UNKNOWN ? Ready.NOTHING
                : new Ready(target.sound(), target.custom(), parsed.volume(), parsed.pitch());
        cache(READY, spec, ready);
        return ready;
    }

    /**
     * The id, volume and pitch in {@code spec}, or null for silence. Bad numbers go to {@code warn}
     * (when not null) and read as 1.
     */
    static Spec parse(String spec, Consumer<String> warn) {
        if (spec == null) {
            return null;
        }
        String[] words = spec.trim().split("\\s+");
        String id = words[0];
        if (id.isEmpty() || id.equalsIgnoreCase("none")) {
            return null;
        }
        float volume = words.length > 1 ? number(words[1]) : 1f;
        float pitch = words.length > 2 ? number(words[2]) : 1f;
        if ((Float.isNaN(volume) || Float.isNaN(pitch)) && warn != null) {
            warn.accept("Invalid volume/pitch in '" + spec.trim() + "'; using 1.0");
        }
        return new Spec(id, Float.isNaN(volume) ? 1f : volume, Float.isNaN(pitch) ? 1f : pitch);
    }

    /** A volume or pitch: a finite number from 0, else NaN. */
    private static float number(String word) {
        try {
            float value = Float.parseFloat(word);
            return Float.isFinite(value) && value >= 0 ? value : Float.NaN;
        } catch (NumberFormatException notANumber) {
            return Float.NaN;
        }
    }

    /**
     * The constant name an id stands for: upper case, without {@code minecraft:}, dots as
     * underscores ({@code minecraft:entity.player.levelup -> ENTITY_PLAYER_LEVELUP}).
     */
    static String constantName(String id) {
        String bare = id.regionMatches(true, 0, MINECRAFT, 0, MINECRAFT.length()) ? id.substring(MINECRAFT.length()) : id;
        return bare.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    /** The key an id stands for in lower case ({@code minecraft:} added when missing), or null when malformed. */
    static NamespacedKey keyOf(String id) {
        return NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
    }

    private static Target resolve(String id, boolean report) {
        Target known = RESOLVED.get(id);
        if (known == null) {
            known = lookup(id);
            cache(RESOLVED, id, known);
        }
        if (known == Target.UNKNOWN && report) {
            warnOnce(id.indexOf(':') >= 0
                    ? "Sound '" + id + "' is not a valid sound id; it will not play."
                    : unknownSound(id));
        }
        return known;
    }

    /** The report for an id without a namespace that is no sound of this server (after the {@code [RsLib]} tag). */
    static String unknownSound(String id) {
        return "Invalid sound '" + id + "': not resolved by enum nor by Registry.SOUNDS, and it carries no namespace,"
                + " so it cannot be a resource pack sound either; ignored. A custom pack sound must be written as"
                + " 'namespace:id'";
    }

    private static Target lookup(String id) {
        Sound byName = Names.INDEX.get(constantName(id));
        if (byName != null) {
            return new Target(byName, null);
        }
        NamespacedKey key = keyOf(id);
        if (key == null) {
            return Target.UNKNOWN;
        }
        Sound byKey = Registry.SOUNDS.get(key);
        if (byKey != null) {
            return new Target(byKey, null);
        }
        return id.indexOf(':') >= 0 ? new Target(null, key.toString()) : Target.UNKNOWN;
    }

    /** Caches are bounded: specs come from configs, but nothing stops a plugin from building them. */
    private static <V> void cache(Map<String, V> cache, String key, V value) {
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, value);
    }

    private static void warnOnce(String message) {
        if (WARNED.add(message)) {
            LOG.warning("[RsLib] " + message);
        }
    }

    /** What an id plays: a registered sound, a resource pack id sent by name, or nothing. */
    private record Target(Sound sound, String custom) {

        static final Target UNKNOWN = new Target(null, null);
    }

    /** A spec ready to play: a sound or a resource pack id, with volume and pitch. */
    private record Ready(Sound sound, String custom, float volume, float pitch) {

        static final Ready NOTHING = new Ready(null, null, 1f, 1f);
    }

    /** One parsed spec. */
    record Spec(String id, float volume, float pitch) {
    }

    /**
     * Constant name to sound for every vanilla sound in the registry, built on first use. The
     * names follow the rule Bukkit generates its constants with, so they match on every version.
     */
    private static final class Names {

        static final Map<String, Sound> INDEX = build();

        private static Map<String, Sound> build() {
            Map<String, Sound> index = new HashMap<>(4096);
            for (Sound sound : Registry.SOUNDS) {
                // Through Keyed: Sound#getKey is deprecated on 1.21 and Sound is an enum on 1.20.
                Keyed keyed = sound;
                NamespacedKey key;
                try {
                    key = keyed.getKey();
                } catch (RuntimeException unkeyed) {
                    continue;
                }
                if (NamespacedKey.MINECRAFT.equals(key.getNamespace())) {
                    index.put(constantName(key.getKey()), sound);
                }
            }
            return Map.copyOf(index);
        }
    }
}
