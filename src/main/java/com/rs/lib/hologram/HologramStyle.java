package com.rs.lib.hologram;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/**
 * How a hologram's text looks: which way it turns, shadow, see-through, background, alignment,
 * line width, text opacity, scale and view range. Immutable; change it with {@link #toBuilder()}.
 *
 * <pre>
 * HologramStyle style = HologramStyle.read(config.getConfigurationSection("hologram"), HologramStyle.DEFAULT, getLogger()::warning);
 * rs().holograms().spawn("crate-1", location, lines, style);
 * </pre>
 *
 * <p>{@link #DEFAULT} is how RsLib holograms always looked ({@code CENTER} billboard and the
 * entity's own values for everything else), so a hologram spawned without a style is unchanged.</p>
 *
 * <p>In yml every key is optional; a missing key keeps the fallback's value, and an invalid one
 * keeps it too with one warning naming the key:</p>
 *
 * <pre>
 * billboard: CENTER        # FIXED, VERTICAL, HORIZONTAL or CENTER
 * shadow: false
 * see-through: false       # seen through blocks
 * background: default      # default, none, #RRGGBB, #AARRGGBB or "R, G, B[, A]"
 * alignment: CENTER        # CENTER, LEFT or RIGHT
 * line-width: 200          # pixels before the text wraps
 * text-opacity: 255        # 0 to 255
 * scale: 1.0
 * view-range: 1.0          # multiples of 64 blocks
 * </pre>
 */
public final class HologramStyle {

    /** The background a text display has when nothing sets it. */
    public static final int DEFAULT_BACKGROUND = 0x40000000;
    /** The line width a text display has when nothing sets it. */
    public static final int DEFAULT_LINE_WIDTH = 200;

    /** The look RsLib holograms have without a style. */
    public static final HologramStyle DEFAULT = new Builder().build();

    private final Display.Billboard billboard;
    private final boolean shadow;
    private final boolean seeThrough;
    /** Null is the entity's default background; otherwise an ARGB color (0 is no background). */
    private final Integer background;
    private final TextDisplay.TextAlignment alignment;
    private final int lineWidth;
    private final int textOpacity;
    private final float scale;
    /** 0 is the entity's own default (1). */
    private final float viewRange;

    private HologramStyle(Builder b) {
        this.billboard = b.billboard;
        this.shadow = b.shadow;
        this.seeThrough = b.seeThrough;
        this.background = b.background;
        this.alignment = b.alignment;
        this.lineWidth = b.lineWidth;
        this.textOpacity = b.textOpacity;
        this.scale = b.scale;
        this.viewRange = b.viewRange;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public Display.Billboard billboard() {
        return billboard;
    }

    public boolean shadow() {
        return shadow;
    }

    public boolean seeThrough() {
        return seeThrough;
    }

    /** The background as ARGB, or null for the entity's default one. */
    public Integer background() {
        return background;
    }

    public TextDisplay.TextAlignment alignment() {
        return alignment;
    }

    public int lineWidth() {
        return lineWidth;
    }

    /** 0 to 255. */
    public int textOpacity() {
        return textOpacity;
    }

    public float scale() {
        return scale;
    }

    /** The view range, or 0 for the entity's own default (1). */
    public float viewRange() {
        return viewRange;
    }

    /**
     * Reads a style from {@code section}; null gives {@code fallback}. Each key missing or
     * invalid keeps the fallback's value; an invalid one is reported once to {@code warn} (may be
     * null), naming the key.
     */
    public static HologramStyle read(ConfigurationSection section, HologramStyle fallback, Consumer<String> warn) {
        HologramStyle base = fallback == null ? DEFAULT : fallback;
        if (section == null) {
            return base;
        }
        Consumer<String> out = warn == null ? message -> { } : warn;
        Builder b = base.toBuilder();
        String billboardText = text(section, "billboard");
        if (billboardText != null) {
            Display.Billboard value = enumValue(Display.Billboard.class, billboardText);
            if (value == null) {
                invalid(out, section, "billboard", billboardText, "FIXED, VERTICAL, HORIZONTAL or CENTER");
            } else {
                b.billboard(value);
            }
        }
        Boolean shadow = bool(section, "shadow", out);
        if (shadow != null) {
            b.shadow(shadow);
        }
        Boolean seeThrough = bool(section, "see-through", out);
        if (seeThrough != null) {
            b.seeThrough(seeThrough);
        }
        String backgroundText = text(section, "background");
        if (backgroundText != null) {
            Integer argb = parseBackground(backgroundText);
            if (argb == null && !isDefaultWord(backgroundText)) {
                invalid(out, section, "background", backgroundText, "default, none, #RRGGBB, #AARRGGBB or R, G, B[, A]");
            } else {
                b.background(argb);
            }
        }
        String alignmentText = text(section, "alignment");
        if (alignmentText != null) {
            TextDisplay.TextAlignment value = enumValue(TextDisplay.TextAlignment.class, alignmentText);
            if (value == null) {
                invalid(out, section, "alignment", alignmentText, "CENTER, LEFT or RIGHT");
            } else {
                b.alignment(value);
            }
        }
        Double lineWidth = number(section, "line-width", out);
        if (lineWidth != null) {
            if (lineWidth < 1 || lineWidth != Math.rint(lineWidth) || lineWidth > Integer.MAX_VALUE) {
                invalid(out, section, "line-width", section.get("line-width"), "a whole number above 0");
            } else {
                b.lineWidth(lineWidth.intValue());
            }
        }
        Double opacity = number(section, "text-opacity", out);
        if (opacity != null) {
            if (opacity < 0 || opacity > 255 || opacity != Math.rint(opacity)) {
                invalid(out, section, "text-opacity", section.get("text-opacity"), "a whole number from 0 to 255");
            } else {
                b.textOpacity(opacity.intValue());
            }
        }
        Double scale = number(section, "scale", out);
        if (scale != null) {
            if (!(scale > 0) || scale.isInfinite()) {
                invalid(out, section, "scale", section.get("scale"), "a number above 0");
            } else {
                b.scale(scale.floatValue());
            }
        }
        Double viewRange = number(section, "view-range", out);
        if (viewRange != null) {
            if (!(viewRange > 0) || viewRange.isInfinite()) {
                invalid(out, section, "view-range", section.get("view-range"), "a number above 0");
            } else {
                b.viewRange(viewRange.floatValue());
            }
        }
        return b.build();
    }

    /**
     * {@code #RRGGBB}, {@code #AARRGGBB}, {@code R, G, B} or {@code R, G, B, A} as ARGB; {@code none}
     * is 0 (no background). Null for {@code default} and for anything it cannot read.
     */
    static Integer parseBackground(String raw) {
        String text = raw.trim();
        if (text.equalsIgnoreCase("none")) {
            return 0;
        }
        if (text.startsWith("#")) {
            String hex = text.substring(1);
            if ((hex.length() != 6 && hex.length() != 8) || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return null;
            }
            long value = Long.parseLong(hex, 16);
            return hex.length() == 6 ? (int) (0xFF000000L | value) : (int) value;
        }
        String[] parts = text.split(",");
        if (parts.length != 3 && parts.length != 4) {
            return null;
        }
        int[] channels = new int[4];
        channels[3] = 255;
        for (int i = 0; i < parts.length; i++) {
            try {
                int channel = Integer.parseInt(parts[i].trim());
                if (channel < 0 || channel > 255) {
                    return null;
                }
                channels[i] = channel;
            } catch (NumberFormatException notNumber) {
                return null;
            }
        }
        return (channels[3] << 24) | (channels[0] << 16) | (channels[1] << 8) | channels[2];
    }

    private static boolean isDefaultWord(String raw) {
        String text = raw.trim();
        return text.equalsIgnoreCase("default") || text.equalsIgnoreCase("vanilla");
    }

    /**
     * Applies this style to {@code display}. With {@code all} false only the values that differ
     * from a new entity's are set (a fresh entity); with true every value is set, so a style that
     * went back to the defaults also resets them.
     */
    void apply(TextDisplay display, boolean all) {
        display.setBillboard(billboard);
        if (all || viewRange > 0) {
            display.setViewRange(viewRange > 0 ? viewRange : 1f);
        }
        if (all || shadow) {
            display.setShadowed(shadow);
        }
        if (all || seeThrough) {
            display.setSeeThrough(seeThrough);
        }
        if (all || background != null) {
            display.setBackgroundColor(Color.fromARGB(background == null ? DEFAULT_BACKGROUND : background));
        }
        if (all || alignment != TextDisplay.TextAlignment.CENTER) {
            display.setAlignment(alignment);
        }
        if (all || lineWidth != DEFAULT_LINE_WIDTH) {
            display.setLineWidth(lineWidth);
        }
        if (all || textOpacity != 255) {
            display.setTextOpacity((byte) textOpacity);
        }
        if (all || scale != 1f) {
            Transformation now = display.getTransformation();
            display.setTransformation(new Transformation(now.getTranslation(), now.getLeftRotation(),
                    new Vector3f(scale, scale, scale), now.getRightRotation()));
        }
    }

    private static String text(ConfigurationSection section, String key) {
        if (!section.contains(key)) {
            return null;
        }
        Object value = section.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(ConfigurationSection section, String key, Consumer<String> warn) {
        if (!section.contains(key)) {
            return null;
        }
        Object value = section.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        invalid(warn, section, key, value, "true or false");
        return null;
    }

    private static Double number(ConfigurationSection section, String key, Consumer<String> warn) {
        if (!section.contains(key)) {
            return null;
        }
        Object value = section.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException notNumber) {
            invalid(warn, section, key, value, "a number");
            return null;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static void invalid(Consumer<String> warn, ConfigurationSection section, String key, Object value, String expected) {
        String path = section.getCurrentPath();
        String full = path == null || path.isEmpty() ? key : path + "." + key;
        warn.accept("Hologram style '" + full + "' has an invalid value '" + value + "' (expected " + expected
                + "); keeping the previous value.");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof HologramStyle s)) {
            return false;
        }
        return billboard == s.billboard && shadow == s.shadow && seeThrough == s.seeThrough
                && Objects.equals(background, s.background) && alignment == s.alignment
                && lineWidth == s.lineWidth && textOpacity == s.textOpacity
                && Float.compare(scale, s.scale) == 0 && Float.compare(viewRange, s.viewRange) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(billboard, shadow, seeThrough, background, alignment, lineWidth, textOpacity, scale, viewRange);
    }

    @Override
    public String toString() {
        return "HologramStyle[billboard=" + billboard + ", shadow=" + shadow + ", seeThrough=" + seeThrough
                + ", background=" + (background == null ? "default" : String.format("#%08X", background))
                + ", alignment=" + alignment + ", lineWidth=" + lineWidth + ", textOpacity=" + textOpacity
                + ", scale=" + scale + ", viewRange=" + viewRange + "]";
    }

    /** Builds a {@link HologramStyle}; starts from {@link #DEFAULT}'s values. */
    public static final class Builder {

        private Display.Billboard billboard = Display.Billboard.CENTER;
        private boolean shadow;
        private boolean seeThrough;
        private Integer background;
        private TextDisplay.TextAlignment alignment = TextDisplay.TextAlignment.CENTER;
        private int lineWidth = DEFAULT_LINE_WIDTH;
        private int textOpacity = 255;
        private float scale = 1f;
        private float viewRange;

        private Builder() {
        }

        private Builder(HologramStyle from) {
            this.billboard = from.billboard;
            this.shadow = from.shadow;
            this.seeThrough = from.seeThrough;
            this.background = from.background;
            this.alignment = from.alignment;
            this.lineWidth = from.lineWidth;
            this.textOpacity = from.textOpacity;
            this.scale = from.scale;
            this.viewRange = from.viewRange;
        }

        /** Null is {@code CENTER}. */
        public Builder billboard(Display.Billboard billboard) {
            this.billboard = billboard == null ? Display.Billboard.CENTER : billboard;
            return this;
        }

        public Builder shadow(boolean shadow) {
            this.shadow = shadow;
            return this;
        }

        public Builder seeThrough(boolean seeThrough) {
            this.seeThrough = seeThrough;
            return this;
        }

        /** ARGB color; 0 is no background and null the entity's default one. */
        public Builder background(Integer argb) {
            this.background = argb;
            return this;
        }

        /** Null is {@code CENTER}. */
        public Builder alignment(TextDisplay.TextAlignment alignment) {
            this.alignment = alignment == null ? TextDisplay.TextAlignment.CENTER : alignment;
            return this;
        }

        /** Pixels before the text wraps; below 1 is the default (200). */
        public Builder lineWidth(int lineWidth) {
            this.lineWidth = lineWidth < 1 ? DEFAULT_LINE_WIDTH : lineWidth;
            return this;
        }

        /** 0 to 255; clamped. */
        public Builder textOpacity(int textOpacity) {
            this.textOpacity = Math.max(0, Math.min(255, textOpacity));
            return this;
        }

        /** Size multiplier; zero, a negative or a non-finite value is 1. */
        public Builder scale(float scale) {
            this.scale = scale > 0 && Float.isFinite(scale) ? scale : 1f;
            return this;
        }

        /** Multiples of 64 blocks; zero, a negative or a non-finite value is the default (1). */
        public Builder viewRange(float viewRange) {
            this.viewRange = viewRange > 0 && Float.isFinite(viewRange) ? viewRange : 0f;
            return this;
        }

        public HologramStyle build() {
            return new HologramStyle(this);
        }
    }
}
