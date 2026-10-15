package com.rs.lib.region;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Rs;
import com.rs.lib.item.RsItem;
import com.rs.lib.yml.RsYml;

/**
 * How one kind of selection behaves: its wand, its particles and its callbacks. Immutable;
 * made with {@link #builder(String)} or read from a config with {@link #fromConfig}.
 *
 * <pre>
 * SelectionSpec arena = SelectionSpec.builder("arena")
 *         .maxVolume(500_000)
 *         .onSelect(box -&gt; saveArena(box))
 *         .build();
 * rs().selections().giveWand(player, arena);
 * </pre>
 *
 * <p>The config form (every key optional):</p>
 * <pre>
 * selection-wand:
 *   item: {material: GOLDEN_AXE, display-name: "&amp;6&amp;lRegion Wand"}
 *   permission: ""           # empty = no permission needed
 *   particle: {type: DUST, color: "255, 140, 0", size: 1.2}   # color also "#RRGGBB"
 *   step: 0.5                # at least 0.1
 *   interval-ticks: 5        # at least 1
 *   render-distance: 64
 *   visibility: OWNER_ONLY   # or WORLD
 *   particle-budget: 2000
 *   max-render-volume: 250000
 *   max-volume: 0            # 0 = no limit
 *   timeout-ticks: 0         # 0 = never expires
 *   silent: false
 * </pre>
 *
 * <p>A particle is named like a Bukkit constant, in any case; {@code REDSTONE} and
 * {@code DUST} name each other (renamed in 1.20.5). An unknown particle, or one that needs data
 * other than a dust colour, becomes {@code FLAME} with a warning.</p>
 */
public final class SelectionSpec {

    /** Who sees a selection's particles. */
    public enum Visibility {
        /** Only the player selecting. */
        OWNER_ONLY,
        /** Every player in the world within the render distance. */
        WORLD
    }

    static final double MIN_STEP = 0.1;
    private static final Logger RSLIB = Logger.getLogger("RsLib");
    private static final Map<String, String> ALIASES = Map.of("REDSTONE", "DUST", "DUST", "REDSTONE");

    private final String id;
    private final ItemStack wandStack;
    private final RsItem wandBuilder;
    private final String permission;
    private final Particle particle;
    private final int dustRed;
    private final int dustGreen;
    private final int dustBlue;
    private final float dustSize;
    private final Object particleData;
    private final double step;
    private final long refreshIntervalTicks;
    private final double renderDistance;
    private final Visibility visibility;
    private final int particleBudget;
    private final long maxRenderVolume;
    private final long maxVolume;
    private final long timeoutTicks;
    private final boolean silent;
    private final boolean completeEnds;
    private final Consumer<Cuboid> onSelect;
    private final Consumer<SelectionSession> onUpdate;
    private final Consumer<UUID> onCancel;
    private final Builder source;

    private SelectionSpec(Builder b) {
        this.id = b.id;
        this.wandStack = b.wandStack == null ? null : b.wandStack.clone();
        this.wandBuilder = b.wandBuilder;
        this.permission = b.permission;
        this.particle = resolveParticle(b.particleName, b.warn);
        this.dustRed = b.dustRed;
        this.dustGreen = b.dustGreen;
        this.dustBlue = b.dustBlue;
        this.dustSize = b.dustSize;
        this.particleData = particle.getDataType() == Particle.DustOptions.class
                ? new Particle.DustOptions(Color.fromRGB(dustRed, dustGreen, dustBlue), dustSize) : null;
        this.step = b.step;
        this.refreshIntervalTicks = b.refreshIntervalTicks;
        this.renderDistance = b.renderDistance;
        this.visibility = b.visibility;
        this.particleBudget = b.particleBudget;
        this.maxRenderVolume = b.maxRenderVolume;
        this.maxVolume = b.maxVolume;
        this.timeoutTicks = b.timeoutTicks;
        this.silent = b.silent;
        this.completeEnds = b.completeEnds;
        this.onSelect = b.onSelect;
        this.onUpdate = b.onUpdate;
        this.onCancel = b.onCancel;
        this.source = b.copy();
    }

    /** A builder for the spec named {@code id}; the id is what a wand stores. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * The spec {@code id} from the section at {@code path} of {@code yml} (see the class notes);
     * keys left out keep the builder's defaults, and a missing section gives a spec with only
     * defaults. Callbacks are set in code afterwards: {@code fromConfig(...).toBuilder().onSelect(...)}.
     */
    public static SelectionSpec fromConfig(Rs rs, RsYml yml, String path, String id) {
        Objects.requireNonNull(yml, "yml");
        Builder b = builder(id);
        Logger logger = rs == null ? RSLIB : rs.plugin().getLogger();
        String where = yml.name() + " -> " + path;
        b.warn = message -> logger.warning("[" + where + "] " + message);
        if (path == null || yml.getSection(path) == null) {
            return b.build();
        }
        String p = path + ".";
        if (yml.getSection(p + "item") != null) {
            b.wandItem(RsItem.fromConfig(yml, p + "item", null));
        }
        b.permission(yml.getString(p + "permission", ""));
        ConfigurationSection particle = yml.getSection(p + "particle");
        if (particle != null) {
            if (particle.isSet("type")) {
                b.particle(yml.getString(p + "particle.type", b.particleName));
            }
            if (particle.isSet("color")) {
                b.dustColor(yml.getString(p + "particle.color", ""));
            }
            b.dustSize((float) yml.getDouble(p + "particle.size", b.dustSize));
        } else if (yml.isSet(p + "particle")) {
            b.particle(yml.getString(p + "particle", b.particleName));
        }
        b.step(yml.getDouble(p + "step", b.step));
        b.refreshIntervalTicks(yml.getLong(p + "interval-ticks", b.refreshIntervalTicks));
        b.renderDistance(yml.getDouble(p + "render-distance", b.renderDistance));
        b.visibility(yml.getEnum(p + "visibility", Visibility.class, b.visibility));
        b.particleBudget(yml.getInt(p + "particle-budget", b.particleBudget));
        b.maxRenderVolume(yml.getLong(p + "max-render-volume", b.maxRenderVolume));
        b.maxVolume(yml.getLong(p + "max-volume", b.maxVolume));
        b.timeoutTicks(yml.getLong(p + "timeout-ticks", b.timeoutTicks));
        b.silent(yml.getBoolean(p + "silent", b.silent));
        return b.build();
    }

    /** A builder holding this spec's values, to change a few of them. */
    public Builder toBuilder() {
        return source.copy();
    }

    // ------------------------------------------------------------------ getters

    public String id() {
        return id;
    }

    /** The permission needed to use the wand; empty when none is. */
    public String permission() {
        return permission;
    }

    /** The particle drawn, after aliases and the FLAME fallback ({@code DUST}, or {@code REDSTONE} before 1.20.5). */
    public String particleName() {
        return particle.name();
    }

    Particle particle() {
        return particle;
    }

    /** What the particle is spawned with: the dust colour and size, or null for a particle without data. */
    Object particleData() {
        return particleData;
    }

    public int dustRed() {
        return dustRed;
    }

    public int dustGreen() {
        return dustGreen;
    }

    public int dustBlue() {
        return dustBlue;
    }

    public float dustSize() {
        return dustSize;
    }

    /** Blocks between two particles along an edge (at least 0.1). */
    public double step() {
        return step;
    }

    /** Ticks between two drawings (at least 1). */
    public long refreshIntervalTicks() {
        return refreshIntervalTicks;
    }

    /** Viewers farther than this from the nearest point of the box see nothing. */
    public double renderDistance() {
        return renderDistance;
    }

    public Visibility visibility() {
        return visibility;
    }

    /** Most particles one viewer gets per drawing; a bigger box spaces them out instead of losing edges. */
    public int particleBudget() {
        return particleBudget;
    }

    /** Above this many blocks only the eight corners are marked; 0 for no limit. */
    public long maxRenderVolume() {
        return maxRenderVolume;
    }

    /** The largest selection accepted, in blocks; 0 for no limit. */
    public long maxVolume() {
        return maxVolume;
    }

    /** Ticks a session lives after it starts; 0 for no limit. */
    public long timeoutTicks() {
        return timeoutTicks;
    }

    /** True when the selection sends no messages besides the missing-permission one. */
    public boolean silent() {
        return silent;
    }

    /** True when a completed selection ends the session (and its particles). */
    public boolean completeEnds() {
        return completeEnds;
    }

    Consumer<Cuboid> onSelect() {
        return onSelect;
    }

    Consumer<SelectionSession> onUpdate() {
        return onUpdate;
    }

    Consumer<UUID> onCancel() {
        return onCancel;
    }

    /** The wand item set on the builder (a copy), or null for the default look. */
    ItemStack wandItem() {
        if (wandStack != null) {
            return wandStack.clone();
        }
        return wandBuilder == null ? null : wandBuilder.build();
    }

    // ------------------------------------------------------------------ particles

    /**
     * The particle {@code name} stands for ({@code minecraft:} allowed, aliases applied), or
     * FLAME with a warning when it is unknown or needs data other than a dust colour.
     */
    static Particle resolveParticle(String name, Consumer<String> warn) {
        Particle found = name == null ? null : byName(name);
        if (found == null) {
            warn.accept("There is no particle '" + name + "'; using FLAME.");
            return Particle.FLAME;
        }
        Class<?> data = found.getDataType();
        if (data != Void.class && data != Particle.DustOptions.class) {
            warn.accept("The particle " + found.name() + " needs data a selection cannot give; using FLAME.");
            return Particle.FLAME;
        }
        return found;
    }

    private static Particle byName(String name) {
        String bare = name.trim();
        if (bare.regionMatches(true, 0, "minecraft:", 0, 10)) {
            bare = bare.substring(10);
        }
        String constant = bare.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
        Particle found = constant(constant);
        if (found == null && ALIASES.containsKey(constant)) {
            found = constant(ALIASES.get(constant));
        }
        return found;
    }

    private static Particle constant(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** {@code R, G, B} or {@code #RRGGBB} (or {@code RRGGBB}); null when it is neither. */
    static int[] parseColor(String text) {
        if (text == null) {
            return null;
        }
        String v = text.trim();
        if (v.indexOf(',') >= 0) {
            int first = v.indexOf(',');
            int second = v.indexOf(',', first + 1);
            if (second < 0 || v.indexOf(',', second + 1) >= 0) {
                return null;
            }
            try {
                int[] rgb = {Integer.parseInt(v.substring(0, first).trim()),
                        Integer.parseInt(v.substring(first + 1, second).trim()),
                        Integer.parseInt(v.substring(second + 1).trim())};
                for (int c : rgb) {
                    if (c < 0 || c > 255) {
                        return null;
                    }
                }
                return rgb;
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        String hex = v.startsWith("#") ? v.substring(1) : v;
        if (hex.length() != 6) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(hex, 16);
            return new int[] {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
        } catch (NumberFormatException notHex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ builder

    /** Builds a {@link SelectionSpec}; every setter returns the builder. Not thread-safe. */
    public static final class Builder {

        private final String id;
        private ItemStack wandStack;
        private RsItem wandBuilder;
        private String permission = "";
        private String particleName = "DUST";
        private int dustRed = 255;
        private int dustGreen = 140;
        private int dustBlue = 0;
        private float dustSize = 1.2f;
        private double step = 0.5;
        private long refreshIntervalTicks = 5;
        private double renderDistance = 64;
        private Visibility visibility = Visibility.OWNER_ONLY;
        private int particleBudget = 2000;
        private long maxRenderVolume = 250_000;
        private long maxVolume;
        private long timeoutTicks;
        private boolean silent;
        private boolean completeEnds = true;
        private Consumer<Cuboid> onSelect;
        private Consumer<SelectionSession> onUpdate;
        private Consumer<UUID> onCancel;
        private Consumer<String> warn = message -> RSLIB.warning("[RsLib] " + message);

        private Builder(String id) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A selection spec needs an id");
            }
            this.id = id.trim();
        }

        Builder copy() {
            Builder b = new Builder(id);
            b.wandStack = wandStack == null ? null : wandStack.clone();
            b.wandBuilder = wandBuilder;
            b.permission = permission;
            b.particleName = particleName;
            b.dustRed = dustRed;
            b.dustGreen = dustGreen;
            b.dustBlue = dustBlue;
            b.dustSize = dustSize;
            b.step = step;
            b.refreshIntervalTicks = refreshIntervalTicks;
            b.renderDistance = renderDistance;
            b.visibility = visibility;
            b.particleBudget = particleBudget;
            b.maxRenderVolume = maxRenderVolume;
            b.maxVolume = maxVolume;
            b.timeoutTicks = timeoutTicks;
            b.silent = silent;
            b.completeEnds = completeEnds;
            b.onSelect = onSelect;
            b.onUpdate = onUpdate;
            b.onCancel = onCancel;
            b.warn = warn;
            return b;
        }

        /** The wand's look (a copy is kept); the wand mark is added on top. Default: BLAZE_ROD {@code "&6&lRegion Wand"}. */
        public Builder wandItem(ItemStack item) {
            this.wandStack = item == null ? null : item.clone();
            this.wandBuilder = null;
            return this;
        }

        /** The wand's look from an item builder, built each time a wand is made. */
        public Builder wandItem(RsItem item) {
            this.wandBuilder = item;
            this.wandStack = null;
            return this;
        }

        /** The permission needed to use the wand; null or empty for none. */
        public Builder permission(String permission) {
            this.permission = permission == null ? "" : permission.trim();
            return this;
        }

        public Builder particle(Particle particle) {
            this.particleName = particle == null ? null : particle.name();
            return this;
        }

        /** A particle by name; see the class notes. */
        public Builder particle(String name) {
            this.particleName = name;
            return this;
        }

        /** The dust colour, {@code "R, G, B"} or {@code "#RRGGBB"}; anything else is reported and ignored. */
        public Builder dustColor(String color) {
            int[] rgb = parseColor(color);
            if (rgb == null) {
                warn.accept("'" + color + "' is not a colour (write \"R, G, B\" or \"#RRGGBB\"); keeping "
                        + dustRed + ", " + dustGreen + ", " + dustBlue + ".");
                return this;
            }
            return dustColor(rgb[0], rgb[1], rgb[2]);
        }

        /** The dust colour; each part is kept between 0 and 255. */
        public Builder dustColor(int red, int green, int blue) {
            this.dustRed = channel(red);
            this.dustGreen = channel(green);
            this.dustBlue = channel(blue);
            return this;
        }

        /** The dust size; values of 0 or less keep the current one. */
        public Builder dustSize(float size) {
            if (size > 0 && Float.isFinite(size)) {
                this.dustSize = size;
            }
            return this;
        }

        /** Blocks between two particles; raised to 0.1 when smaller. */
        public Builder step(double step) {
            this.step = Double.isNaN(step) ? MIN_STEP : Math.max(MIN_STEP, step);
            return this;
        }

        /** Ticks between two drawings; raised to 1 when smaller. */
        public Builder refreshIntervalTicks(long ticks) {
            this.refreshIntervalTicks = Math.max(1L, ticks);
            return this;
        }

        public Builder renderDistance(double blocks) {
            this.renderDistance = Double.isNaN(blocks) ? 0 : Math.max(0, blocks);
            return this;
        }

        public Builder visibility(Visibility visibility) {
            this.visibility = visibility == null ? Visibility.OWNER_ONLY : visibility;
            return this;
        }

        /** Most particles per viewer and drawing; raised to 1 when smaller. */
        public Builder particleBudget(int particles) {
            this.particleBudget = Math.max(1, particles);
            return this;
        }

        /** Above this many blocks only the corners are marked; 0 or less for no limit. */
        public Builder maxRenderVolume(long blocks) {
            this.maxRenderVolume = Math.max(0L, blocks);
            return this;
        }

        /** The largest selection accepted; 0 or less for no limit. */
        public Builder maxVolume(long blocks) {
            this.maxVolume = Math.max(0L, blocks);
            return this;
        }

        /** Ticks a session lives; 0 or less for no limit. */
        public Builder timeoutTicks(long ticks) {
            this.timeoutTicks = Math.max(0L, ticks);
            return this;
        }

        /** No messages besides the missing-permission one. */
        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        /** Whether a completed selection ends the session (default true). */
        public Builder completeEnds(boolean completeEnds) {
            this.completeEnds = completeEnds;
            return this;
        }

        /** Runs with the box when a selection completes and its event was not cancelled. */
        public Builder onSelect(Consumer<Cuboid> onSelect) {
            this.onSelect = onSelect;
            return this;
        }

        /** Runs after every position the wand sets. */
        public Builder onUpdate(Consumer<SelectionSession> onUpdate) {
            this.onUpdate = onUpdate;
            return this;
        }

        /** Runs with the player's id when a session ends without completing. */
        public Builder onCancel(Consumer<UUID> onCancel) {
            this.onCancel = onCancel;
            return this;
        }

        public SelectionSpec build() {
            return new SelectionSpec(this);
        }

        private static int channel(int value) {
            return Math.max(0, Math.min(255, value));
        }
    }
}
