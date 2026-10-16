package com.rs.lib.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Which style forms a text written by a player may use (clan names, tags, chat), and what to
 * do with the rest.
 *
 * <p>Detection runs after section signs are normalized and finds: legacy colors, hex colors
 * ({@code &#RRGGBB} and {@code &x&R&R...}), each legacy format, cosmetic MiniMessage tags
 * (color names, {@code #hex}, {@code color:}, decorations and their aliases, closing and
 * negated forms included), gradients ({@code [rgb]} anywhere, {@code <gradient>},
 * {@code <rainbow>}), resets (always allowed) and non-cosmetic tags such as click, hover or an
 * unknown {@code <VIP>}, which are always a {@link Capability#MINIMESSAGE} violation even when
 * MiniMessage is allowed. A disabled policy allows nothing.</p>
 *
 * <p>Config format (keys under the path the plugin chooses):</p>
 * <pre>
 * enabled: true              # false = plain text only
 * allow-legacy-colors: true  # every allow-* defaults to false
 * allow-hex: false
 * allow-bold: true
 * allow-italic: false
 * allow-underline: false
 * allow-strikethrough: false
 * allow-obfuscated: false
 * allow-minimessage: false
 * allow-gradient: false
 * on-disallowed: strip       # reject (default) or strip
 * </pre>
 *
 * <p>Immutable and safe from any thread.</p>
 */
public final class StylePolicy {

    /** A style form a policy can allow. */
    public enum Capability {
        LEGACY_COLOR, HEX, BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, OBFUSCATED, MINIMESSAGE, GRADIENT
    }

    /** What {@link #apply} does with a text that uses something not allowed. */
    public enum OnDisallowed {
        /** Return the visible plain text. */
        REJECT,
        /** Remove only what is not allowed. */
        STRIP
    }

    private static final Set<String> COLOR_NAMES = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey",
            "dark_gray", "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white");
    private static final Set<String> DECORATIONS = Set.of(
            "bold", "b", "italic", "i", "em", "underlined", "u", "strikethrough", "st", "obfuscated", "obf");

    private static final StylePolicy DISABLED = new StylePolicy(false, EnumSet.noneOf(Capability.class),
            OnDisallowed.REJECT);

    private final boolean enabled;
    private final Set<Capability> allowed;
    private final OnDisallowed onDisallowed;

    private StylePolicy(boolean enabled, EnumSet<Capability> allowed, OnDisallowed onDisallowed) {
        this.enabled = enabled;
        this.allowed = enabled ? Collections.unmodifiableSet(EnumSet.copyOf(allowed))
                : Collections.unmodifiableSet(EnumSet.noneOf(Capability.class));
        this.onDisallowed = onDisallowed;
    }

    /** A policy that allows nothing (plain text only) and rejects. */
    public static StylePolicy disabled() {
        return DISABLED;
    }

    /** A builder: enabled, nothing allowed, {@link OnDisallowed#REJECT}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Reads a policy from {@code section.path} (or from {@code section} itself when the path is
     * null or empty). A missing section gives the defaults: enabled, nothing allowed, reject.
     */
    public static StylePolicy fromConfig(ConfigurationSection section, String path) {
        ConfigurationSection s = section;
        if (s != null && path != null && !path.isEmpty()) {
            s = s.getConfigurationSection(path);
        }
        Builder b = builder();
        if (s == null) {
            return b.build();
        }
        b.enabled(s.getBoolean("enabled", true));
        allowIf(b, s, "allow-legacy-colors", Capability.LEGACY_COLOR);
        allowIf(b, s, "allow-hex", Capability.HEX);
        allowIf(b, s, "allow-bold", Capability.BOLD);
        allowIf(b, s, "allow-italic", Capability.ITALIC);
        allowIf(b, s, "allow-underline", Capability.UNDERLINE);
        allowIf(b, s, "allow-strikethrough", Capability.STRIKETHROUGH);
        allowIf(b, s, "allow-obfuscated", Capability.OBFUSCATED);
        allowIf(b, s, "allow-minimessage", Capability.MINIMESSAGE);
        allowIf(b, s, "allow-gradient", Capability.GRADIENT);
        String mode = s.getString("on-disallowed", "reject");
        b.onDisallowed(mode != null && mode.trim().equalsIgnoreCase("strip") ? OnDisallowed.STRIP : OnDisallowed.REJECT);
        return b.build();
    }

    private static void allowIf(Builder b, ConfigurationSection s, String key, Capability capability) {
        if (s.getBoolean(key, false)) {
            b.allow(capability);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** True when nothing is allowed (disabled or no capability). */
    public boolean isPlainOnly() {
        return allowed.isEmpty();
    }

    public boolean isAllowed(Capability capability) {
        return allowed.contains(capability);
    }

    /** The allowed capabilities (empty when disabled). */
    public Set<Capability> allowed() {
        return allowed;
    }

    public OnDisallowed onDisallowed() {
        return onDisallowed;
    }

    /** The capabilities {@code text} uses and this policy does not allow, in enum order. */
    public List<Capability> violations(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        EnumSet<Capability> used = EnumSet.noneOf(Capability.class);
        boolean[] nonCosmetic = new boolean[1];
        scan(LegacyConverter.normalizeSections(text), (piece, capability, cosmetic) -> {
            if (capability != null) {
                used.add(capability);
            }
            if (!cosmetic) {
                nonCosmetic[0] = true;
            }
        });
        List<Capability> out = new ArrayList<>(used.size() + 1);
        for (Capability c : Capability.values()) {
            boolean violates = used.contains(c) && !allowed.contains(c);
            if (c == Capability.MINIMESSAGE && nonCosmetic[0]) {
                violates = true;
            }
            if (violates) {
                out.add(c);
            }
        }
        return out;
    }

    /** True when {@code text} has no violation. */
    public boolean accepts(String text) {
        return violations(text).isEmpty();
    }

    /**
     * The text to keep: unchanged when it has no violation; otherwise {@link #strip} in STRIP
     * mode, or the visible plain text in REJECT mode. Null in, null out.
     */
    public String apply(String text) {
        if (text == null) {
            return null;
        }
        if (violations(text).isEmpty()) {
            return text;
        }
        return onDisallowed == OnDisallowed.STRIP ? strip(text) : RsText.plainUncached(text);
    }

    /**
     * Removes only what is not allowed: disallowed codes and tags and every non-cosmetic tag.
     * Resets ({@code &r}, {@code <reset>}), {@code [center]}, {@code [small]} and escaped
     * {@code \<} are kept. Codes come back in {@code &} form. Null in, null out.
     */
    public String strip(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(text.length());
        scan(LegacyConverter.normalizeSections(text), (piece, capability, cosmetic) -> {
            boolean keep = cosmetic && (capability == null || allowed.contains(capability));
            if (keep) {
                out.append(piece);
            }
        });
        return out.toString();
    }

    // ------------------------------------------------------------------ detection

    /** Receives each piece of a scanned text with the capability it needs (null for none). */
    @FunctionalInterface
    private interface PieceSink {
        void accept(String piece, Capability capability, boolean cosmetic);
    }

    /** A code or tag found by {@link #scan}: its length and what it needs. */
    private record Piece(int length, Capability capability, boolean cosmetic) {
    }

    /** The kind of a MiniMessage tag, with the capability it needs. */
    private enum TagKind {
        RESET(null, true),
        GRADIENT(Capability.GRADIENT, true),
        COSMETIC(Capability.MINIMESSAGE, true),
        /** Click, hover, an unknown tag...: never allowed. */
        OTHER(Capability.MINIMESSAGE, false);

        final Capability capability;
        final boolean cosmetic;

        TagKind(Capability capability, boolean cosmetic) {
            this.capability = capability;
            this.cosmetic = cosmetic;
        }
    }

    /** Splits {@code s} into plain pieces, codes and tags, reporting what each one needs. */
    private static void scan(String s, PieceSink sink) {
        int n = s.length();
        int plainStart = 0;
        int i = 0;
        while (i < n) {
            if (Markup.escapeAt(s, i)) {
                i += 2;
                continue;
            }
            Piece piece = pieceAt(s, i);
            if (piece == null) {
                i++;
                continue;
            }
            if (plainStart < i) {
                sink.accept(s.substring(plainStart, i), null, true);
            }
            sink.accept(s.substring(i, i + piece.length()), piece.capability(), piece.cosmetic());
            i += piece.length();
            plainStart = i;
        }
        if (plainStart < n) {
            sink.accept(s.substring(plainStart), null, true);
        }
    }

    /** The code, tag or {@code [rgb]} bracket at {@code i}, or null for plain text. */
    private static Piece pieceAt(String s, int i) {
        switch (s.charAt(i)) {
            case '&' -> {
                int len = Markup.codeLength(s, i, '&');
                return len == 0 ? null : new Piece(len, codeCapability(s, i, len), true);
            }
            case '<' -> {
                int end = Markup.tagEnd(s, i);
                if (end <= 0) {
                    return null;
                }
                TagKind kind = tagKind(s.substring(i + 1, end - 1));
                return new Piece(end - i, kind.capability, kind.cosmetic);
            }
            case '[' -> {
                int len = Markup.bracketAt(s, i, Markup.RGB);
                if (len == 0) {
                    len = Markup.bracketAt(s, i, Markup.RGB_CLOSE);
                }
                return len == 0 ? null : new Piece(len, Capability.GRADIENT, true);
            }
            default -> {
                return null;
            }
        }
    }

    private static Capability codeCapability(String s, int i, int len) {
        if (len != Markup.CODE_LENGTH) {
            return Capability.HEX;
        }
        char code = Character.toLowerCase(s.charAt(i + 1));
        return switch (code) {
            case 'l' -> Capability.BOLD;
            case 'o' -> Capability.ITALIC;
            case 'n' -> Capability.UNDERLINE;
            case 'm' -> Capability.STRIKETHROUGH;
            case 'k' -> Capability.OBFUSCATED;
            case 'r' -> null;
            default -> Capability.LEGACY_COLOR;
        };
    }

    /** The kind of tag for the inside of a tag. */
    private static TagKind tagKind(String body) {
        String b = body.toLowerCase(Locale.ROOT);
        if (b.startsWith("/") || b.startsWith("!")) {
            b = b.substring(1);
        }
        int colon = b.indexOf(':');
        String name = colon < 0 ? b : b.substring(0, colon);
        if (name.equals("reset")) {
            return TagKind.RESET;
        }
        if (name.equals("gradient") || name.equals("rainbow")) {
            return TagKind.GRADIENT;
        }
        if (COLOR_NAMES.contains(name) || DECORATIONS.contains(name) || isHexName(name)) {
            return TagKind.COSMETIC;
        }
        if ((name.equals("color") || name.equals("colour") || name.equals("c")) && colon > 0) {
            String arg = b.substring(colon + 1);
            return COLOR_NAMES.contains(arg) || isHexName(arg) ? TagKind.COSMETIC : TagKind.OTHER;
        }
        return TagKind.OTHER;
    }

    private static boolean isHexName(String name) {
        if (name.length() != 7 || name.charAt(0) != '#') {
            return false;
        }
        for (int k = 1; k < 7; k++) {
            if (!Markup.isHexDigit(name.charAt(k))) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ builder

    /** Builds a {@link StylePolicy}; starts enabled, with nothing allowed and REJECT. */
    public static final class Builder {
        private boolean enabled = true;
        private final EnumSet<Capability> allowed = EnumSet.noneOf(Capability.class);
        private OnDisallowed onDisallowed = OnDisallowed.REJECT;

        private Builder() {
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder allow(Capability... capabilities) {
            if (capabilities != null) {
                for (Capability c : capabilities) {
                    if (c != null) {
                        allowed.add(c);
                    }
                }
            }
            return this;
        }

        public Builder allowAll() {
            Collections.addAll(allowed, Capability.values());
            return this;
        }

        public Builder disallow(Capability... capabilities) {
            if (capabilities != null) {
                for (Capability c : capabilities) {
                    allowed.remove(c);
                }
            }
            return this;
        }

        public Builder onDisallowed(OnDisallowed value) {
            this.onDisallowed = value == null ? OnDisallowed.REJECT : value;
            return this;
        }

        public StylePolicy build() {
            return new StylePolicy(enabled, allowed, onDisallowed);
        }
    }
}
