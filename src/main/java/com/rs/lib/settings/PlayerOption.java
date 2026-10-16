package com.rs.lib.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * One player preference a plugin registers with {@code rs().settings().register(...)}: a
 * toggle, a choice among fixed values or a whole number between a minimum and a maximum.
 *
 * <pre>
 * PlayerOption.toggle("trade-requests", true).name("&amp;aTrade requests").icon(Material.EMERALD).build();
 * PlayerOption.choice("mentions", "all", "all", "friends", "none").name("Mentions").icon(Material.BELL).build();
 * PlayerOption.number("view-distance", 8, 2, 16).step(2).name("Distance").icon(Material.SPYGLASS)
 *         .permission("myplugin.distance").build();
 * </pre>
 *
 * <p>Values are kept as text: {@code "true"}/{@code "false"}, the choice itself, or the number.
 * The name is written by the plugin and goes through the text pipeline ({@code &}, hex,
 * MiniMessage). A player without the permission does not see the option in the menu and always
 * reads its default.</p>
 */
public final class PlayerOption {

    /** What an option holds. */
    public enum Kind {
        /** On or off; a click flips it. */
        TOGGLE,
        /** One of a fixed list; a left click moves forward, a right click back, wrapping around. */
        CHOICE,
        /** A whole number; a left click adds the step, a right click takes it, within min and max. */
        NUMBER
    }

    /** Keys and choice values: letters, digits, {@code _ - .}, 1 to 64 characters. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final String key;
    private final Kind kind;
    private final String name;
    private final Material icon;
    private final String permission;
    private final String defaultValue;
    private final List<String> choices;
    private final int min;
    private final int max;
    private final int step;

    private PlayerOption(Builder b) {
        this.key = b.key;
        this.kind = b.kind;
        this.name = b.name;
        this.icon = b.icon;
        this.permission = b.permission;
        this.defaultValue = b.defaultValue;
        this.choices = List.copyOf(b.choices);
        this.min = b.min;
        this.max = b.max;
        this.step = b.step;
    }

    /** A toggle that starts {@code defaultValue}. */
    public static Builder toggle(String key, boolean defaultValue) {
        return new Builder(key, Kind.TOGGLE, Boolean.toString(defaultValue));
    }

    /**
     * A choice among {@code values}, in the order a left click goes through them, starting at
     * {@code defaultValue}.
     *
     * @throws IllegalArgumentException with fewer than two values, a repeated or malformed value,
     *                                  or a default that is not one of them
     */
    public static Builder choice(String key, String defaultValue, String... values) {
        Builder builder = new Builder(key, Kind.CHOICE, Objects.requireNonNull(defaultValue, "defaultValue"));
        Objects.requireNonNull(values, "values");
        if (values.length < 2) {
            throw new IllegalArgumentException("A choice needs at least two values: " + key);
        }
        for (String value : values) {
            String id = id(value, "value");
            if (builder.choices.stream().anyMatch(id::equalsIgnoreCase)) {
                throw new IllegalArgumentException("Repeated value '" + id + "' in choice " + key);
            }
            builder.choices.add(id);
        }
        if (builder.choices.stream().noneMatch(defaultValue::equals)) {
            throw new IllegalArgumentException("The default '" + defaultValue + "' of " + key
                    + " is not one of " + builder.choices);
        }
        return builder;
    }

    /**
     * A whole number from {@code min} to {@code max} that starts at {@code defaultValue}; a click
     * moves it by 1 unless {@link Builder#step(int)} says otherwise.
     *
     * @throws IllegalArgumentException when {@code min > max} or the default is outside them
     */
    public static Builder number(String key, int defaultValue, int min, int max) {
        if (min > max || defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException("Number " + key + " needs min <= default <= max, got "
                    + min + " <= " + defaultValue + " <= " + max);
        }
        Builder builder = new Builder(key, Kind.NUMBER, Integer.toString(defaultValue));
        builder.min = min;
        builder.max = max;
        return builder;
    }

    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    /** The display name as the plugin wrote it. */
    public String name() {
        return name;
    }

    public Material icon() {
        return icon;
    }

    /** The permission needed to see and change it, or null when everyone may. */
    public String permission() {
        return permission;
    }

    /** The default as text: {@code "true"}, a choice or a number. */
    public String defaultValue() {
        return defaultValue;
    }

    /** The values of a choice, in click order; empty for other kinds. */
    public List<String> choices() {
        return choices;
    }

    /** The lowest number; 0 for other kinds. */
    public int min() {
        return min;
    }

    /** The highest number; 0 for other kinds. */
    public int max() {
        return max;
    }

    /** How much a click moves a number; 0 for other kinds. */
    public int step() {
        return step;
    }

    /** Whether {@code player} may see and change it. */
    public boolean allows(Player player) {
        return permission == null || player.hasPermission(permission);
    }

    /**
     * {@code raw} in the form this option keeps ({@code "TRUE"} becomes {@code "true"}, a choice
     * takes its registered case), or null when this option cannot hold it.
     */
    public String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return switch (kind) {
            case TOGGLE -> trimmed.equalsIgnoreCase("true") ? "true" : trimmed.equalsIgnoreCase("false") ? "false" : null;
            case CHOICE -> choices.stream().filter(trimmed::equalsIgnoreCase).findFirst().orElse(null);
            case NUMBER -> {
                try {
                    int value = Integer.parseInt(trimmed);
                    yield value < min || value > max ? null : Integer.toString(value);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    /**
     * The value one click leads to from {@code current} (the default when it is not valid):
     * a toggle flips, a choice moves forward or back wrapping around, a number moves by its step
     * and stops at min and max.
     */
    public String next(String current, boolean forward) {
        String value = normalize(current);
        if (value == null) {
            value = defaultValue;
        }
        return switch (kind) {
            case TOGGLE -> Boolean.toString(!Boolean.parseBoolean(value));
            case CHOICE -> {
                int size = choices.size();
                int at = choices.indexOf(value);
                yield choices.get(Math.floorMod(at + (forward ? 1 : -1), size));
            }
            case NUMBER -> {
                long moved = Long.parseLong(value) + (forward ? (long) step : -(long) step);
                yield Long.toString(Math.max(min, Math.min(max, moved)));
            }
        };
    }

    @Override
    public String toString() {
        return "PlayerOption[" + key + " " + kind.name().toLowerCase(Locale.ROOT) + " = " + defaultValue + "]";
    }

    private static String id(String value, String what) {
        Objects.requireNonNull(value, what);
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("A " + what + " uses letters, digits, '_', '-' or '.' (1 to 64): '"
                    + value + "'");
        }
        return value;
    }

    /** Builds a {@link PlayerOption}; {@link #name} and {@link #icon} are required. */
    public static final class Builder {

        private final String key;
        private final Kind kind;
        private final String defaultValue;
        private final List<String> choices = new ArrayList<>();
        private String name;
        private Material icon;
        private String permission;
        private int min;
        private int max;
        private int step;

        private Builder(String key, Kind kind, String defaultValue) {
            this.key = id(key, "key");
            this.kind = kind;
            this.defaultValue = defaultValue;
            this.step = kind == Kind.NUMBER ? 1 : 0;
        }

        /** The name shown in the menu (text pipeline: {@code &}, hex, MiniMessage). */
        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** The item shown in the menu. */
        public Builder icon(Material icon) {
            this.icon = Objects.requireNonNull(icon, "icon");
            return this;
        }

        /** Only players with {@code permission} see and change it; null or blank for everyone. */
        public Builder permission(String permission) {
            this.permission = permission == null || permission.isBlank() ? null : permission;
            return this;
        }

        /**
         * How much a click moves a number (1 by default).
         *
         * @throws IllegalStateException    when the option is not a number
         * @throws IllegalArgumentException when {@code step} is not positive
         */
        public Builder step(int step) {
            if (kind != Kind.NUMBER) {
                throw new IllegalStateException("Only a number option has a step: " + key);
            }
            if (step <= 0) {
                throw new IllegalArgumentException("The step of " + key + " must be positive: " + step);
            }
            this.step = step;
            return this;
        }

        /** @throws IllegalStateException without a name or an icon */
        public PlayerOption build() {
            if (name == null || icon == null) {
                throw new IllegalStateException("Option " + key + " needs a name and an icon");
            }
            return new PlayerOption(this);
        }
    }
}
