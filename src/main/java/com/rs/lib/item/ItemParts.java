package com.rs.lib.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

import com.rs.lib.util.HeadUtil;

/**
 * The pure parsing behind the appearance keys: token lists, colors, attribute lines, flag and
 * slot names, legacy enchantment and effect names, material specs. Nothing here touches the
 * server, so every rule is tested without one.
 */
final class ItemParts {

    /** An enchantment token pair: the id as written and its level. */
    record Leveled(String id, int level) {
    }

    /** A potion effect: the id as written, its level (1 = amplifier 0) and its duration in ticks. */
    record Effect(String id, int level, int ticks) {
    }

    /** One {@code attributes} line, as written but split. */
    record AttributeLine(String attribute, String operation, double amount, String group) {
    }

    /** A material spec: a head texture (then {@code material} is PLAYER_HEAD) or a material. */
    record MaterialSpec(Material material, String texture) {
    }

    static final int DEFAULT_EFFECT_TICKS = 200;
    static final String HIDE_ALL = "HIDE_ALL";
    /** Reported when an item sets both a skull owner and a texture head. */
    static final String SKULL_AND_TEXTURE = "skull-owner and base64 texture defined at the same time for PLAYER_HEAD;"
            + " skull-owner wins and the base64 texture is ignored";
    static final String HIDE_TOOLTIP = "HIDE_TOOLTIP";
    /** How a head texture starts ({@link HeadUtil}'s prefixes and a skin URL). */
    private static final String[] TEXTURE_PREFIXES = {"texture-", "texture:", "base64-", "base64:", "basehead-",
            "http://", "https://"};

    private static final String[] ATTRIBUTE_PREFIXES = {"generic", "player", "zombie", "horse"};
    private static final Set<String> OPERATIONS = Set.of("ADD_NUMBER", "ADD_SCALAR", "MULTIPLY_SCALAR_1");
    private static final Map<String, String> OPERATION_ALIASES = Map.of(
            "ADD_VALUE", "ADD_NUMBER",
            "ADD_MULTIPLIED_BASE", "ADD_SCALAR",
            "ADD_MULTIPLIED_TOTAL", "MULTIPLY_SCALAR_1");
    private static final Set<String> SLOT_GROUPS = Set.of("ANY", "HAND", "ARMOR", "MAINHAND", "OFFHAND",
            "HEAD", "CHEST", "LEGS", "FEET", "BODY");

    /** Bukkit's old enchantment constant names and the keys they stand for. */
    private static final Map<String, String> LEGACY_ENCHANTMENTS = Map.ofEntries(
            Map.entry("PROTECTION_ENVIRONMENTAL", "protection"),
            Map.entry("PROTECTION_FIRE", "fire_protection"),
            Map.entry("PROTECTION_FALL", "feather_falling"),
            Map.entry("PROTECTION_EXPLOSIONS", "blast_protection"),
            Map.entry("PROTECTION_PROJECTILE", "projectile_protection"),
            Map.entry("OXYGEN", "respiration"),
            Map.entry("WATER_WORKER", "aqua_affinity"),
            Map.entry("DAMAGE_ALL", "sharpness"),
            Map.entry("DAMAGE_UNDEAD", "smite"),
            Map.entry("DAMAGE_ARTHROPODS", "bane_of_arthropods"),
            Map.entry("LOOT_BONUS_MOBS", "looting"),
            Map.entry("DIG_SPEED", "efficiency"),
            Map.entry("DURABILITY", "unbreaking"),
            Map.entry("LOOT_BONUS_BLOCKS", "fortune"),
            Map.entry("ARROW_DAMAGE", "power"),
            Map.entry("ARROW_KNOCKBACK", "punch"),
            Map.entry("ARROW_FIRE", "flame"),
            Map.entry("ARROW_INFINITE", "infinity"),
            Map.entry("LUCK", "luck_of_the_sea"));

    /** Keys renamed in 1.20.5; both spellings are tried. */
    private static final Map<String, String> RENAMED_ENCHANTMENTS = Map.of(
            "sweeping_edge", "sweeping", "sweeping", "sweeping_edge");

    /** Bukkit's old effect constant names and the keys they stand for. */
    private static final Map<String, String> LEGACY_EFFECTS = Map.ofEntries(
            Map.entry("SLOW", "slowness"),
            Map.entry("FAST_DIGGING", "haste"),
            Map.entry("SLOW_DIGGING", "mining_fatigue"),
            Map.entry("INCREASE_DAMAGE", "strength"),
            Map.entry("HEAL", "instant_health"),
            Map.entry("HARM", "instant_damage"),
            Map.entry("JUMP", "jump_boost"),
            Map.entry("CONFUSION", "nausea"),
            Map.entry("DAMAGE_RESISTANCE", "resistance"));

    private ItemParts() {
    }

    // ------------------------------------------------------------------ token lists

    /**
     * Splits every entry on spaces, commas and semicolons, dropping empty pieces:
     * {@code ["sharpness 5", "unbreaking;3"]} gives four tokens.
     */
    static List<String> tokens(List<String> entries) {
        List<String> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            int start = -1;
            for (int i = 0; i <= entry.length(); i++) {
                boolean separator = i == entry.length() || isSeparator(entry.charAt(i));
                if (separator) {
                    if (start >= 0) {
                        out.add(entry.substring(start, i));
                        start = -1;
                    }
                } else if (start < 0) {
                    start = i;
                }
            }
        }
        return out;
    }

    private static boolean isSeparator(char c) {
        return c == ',' || c == ';' || Character.isWhitespace(c);
    }

    /** The whole token as an int, or null when it is not one. */
    static Integer intOrNull(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        int i = token.charAt(0) == '-' || token.charAt(0) == '+' ? 1 : 0;
        if (i == token.length()) {
            return null;
        }
        for (int k = i; k < token.length(); k++) {
            if (token.charAt(k) < '0' || token.charAt(k) > '9') {
                return null;
            }
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    /**
     * Enchantments written as a flat list {@code [id, level, id, level]}: an id takes the number
     * after it as its level, or 1 when another id or the end follows. A number with no id before
     * it is reported and skipped.
     */
    static List<Leveled> enchantments(List<String> entries, Consumer<String> warn) {
        List<Leveled> out = new ArrayList<>();
        String pending = null;
        for (String token : tokens(entries)) {
            Integer number = intOrNull(token);
            if (number == null) {
                if (pending != null) {
                    out.add(new Leveled(pending, 1));
                }
                pending = token;
            } else if (pending != null) {
                out.add(new Leveled(pending, number));
                pending = null;
            } else {
                warn.accept("Enchantment level " + token + " has no enchantment before it; skipped.");
            }
        }
        if (pending != null) {
            out.add(new Leveled(pending, 1));
        }
        return out;
    }

    /**
     * Potion effects written as a flat list {@code [effect, level, ticks, ...]}: the first number
     * after an id is its level (1 when absent, amplifier = level - 1), the second its duration in
     * ticks (200 when absent). A number with no id before it is reported and skipped.
     */
    static List<Effect> effects(List<String> entries, Consumer<String> warn) {
        List<Effect> out = new ArrayList<>();
        String id = null;
        int level = 1;
        int ticks = DEFAULT_EFFECT_TICKS;
        int numbers = 0;
        for (String token : tokens(entries)) {
            Integer number = intOrNull(token);
            if (number == null) {
                if (id != null) {
                    out.add(new Effect(id, level, ticks));
                }
                id = token;
                level = 1;
                ticks = DEFAULT_EFFECT_TICKS;
                numbers = 0;
            } else if (id == null || numbers >= 2) {
                warn.accept("Potion effect number " + token + " has no effect before it; skipped.");
            } else if (numbers++ == 0) {
                level = number;
            } else {
                ticks = number;
            }
        }
        if (id != null) {
            out.add(new Effect(id, level, ticks));
        }
        return out;
    }

    /**
     * The vanilla damage to write for {@code damage} on a material whose durability is
     * {@code maxDurability}: kept between 0 and that durability. Null, with a report, when the
     * material has no durability.
     */
    static Integer damage(int damage, int maxDurability, String material, Consumer<String> warn) {
        if (maxDurability <= 0) {
            warn.accept("damage needs a material with durability; " + material + " has none, so it is ignored.");
            return null;
        }
        return Math.max(0, Math.min(damage, maxDurability));
    }

    // ------------------------------------------------------------------ colors

    /**
     * {@code "R, G, B"} (commas and/or spaces, each 0 to 255), {@code "RRGGBB"} or
     * {@code "#RRGGBB"} as {@code 0xRRGGBB}; -1 when the text is none of those.
     */
    static int rgb(String text) {
        if (text == null) {
            return -1;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        String hex = trimmed.charAt(0) == '#' ? trimmed.substring(1) : trimmed;
        if (hex.length() == 6 && isHex(hex)) {
            return Integer.parseInt(hex, 16);
        }
        List<String> parts = tokens(List.of(trimmed));
        if (parts.size() != 3) {
            return -1;
        }
        int rgb = 0;
        for (String part : parts) {
            Integer channel = intOrNull(part);
            if (channel == null || channel < 0 || channel > 255) {
                return -1;
            }
            rgb = (rgb << 8) | channel;
        }
        return rgb;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ attributes

    /**
     * {@code "ATTRIBUTE OPERATION amount [group]"}: operation {@code ADD_NUMBER},
     * {@code ADD_SCALAR} or {@code MULTIPLY_SCALAR_1} (the 1.21 names {@code add_value},
     * {@code add_multiplied_base} and {@code add_multiplied_total} too), group {@code ANY} by
     * default. Null, with a report, when the line does not have that shape.
     */
    static AttributeLine attribute(String line, Consumer<String> warn) {
        List<String> parts = new ArrayList<>();
        for (String piece : (line == null ? "" : line.trim()).split("\\s+")) {
            if (!piece.isEmpty()) {
                parts.add(piece);
            }
        }
        if (parts.size() < 3 || parts.size() > 4) {
            warn.accept("Attribute line '" + line + "' must be 'ATTRIBUTE OPERATION amount [slot-group]'; skipped.");
            return null;
        }
        String operation = parts.get(1).toUpperCase(Locale.ROOT).replace('-', '_');
        operation = OPERATION_ALIASES.getOrDefault(operation, operation);
        if (!OPERATIONS.contains(operation)) {
            warn.accept("Unknown attribute operation '" + parts.get(1) + "' in '" + line
                    + "' (ADD_NUMBER, ADD_SCALAR or MULTIPLY_SCALAR_1); skipped.");
            return null;
        }
        double amount;
        try {
            amount = Double.parseDouble(parts.get(2));
        } catch (NumberFormatException e) {
            amount = Double.NaN;
        }
        if (!Double.isFinite(amount)) {
            warn.accept("Attribute amount '" + parts.get(2) + "' in '" + line + "' is not a number; skipped.");
            return null;
        }
        String group = parts.size() == 4 ? parts.get(3).toUpperCase(Locale.ROOT).replace('-', '_') : "ANY";
        if (!SLOT_GROUPS.contains(group)) {
            warn.accept("Unknown slot group '" + parts.get(3) + "' in '" + line
                    + "' (ANY, HAND, ARMOR, MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET); using ANY.");
            group = "ANY";
        }
        return new AttributeLine(parts.get(0), operation, amount, group);
    }

    /** The attribute name without namespace, prefix or dots: {@code GENERIC_ATTACK_DAMAGE -> attack_damage}. */
    static String attributeBase(String name) {
        String s = stripMinecraft(name.trim().toLowerCase(Locale.ROOT));
        for (String prefix : ATTRIBUTE_PREFIXES) {
            if (s.length() > prefix.length() + 1 && s.startsWith(prefix)
                    && (s.charAt(prefix.length()) == '.' || s.charAt(prefix.length()) == '_')) {
                return s.substring(prefix.length() + 1);
            }
        }
        return s.replace('.', '_');
    }

    /**
     * The registry keys an attribute name may have across versions, most likely first. Keys
     * lost their {@code generic.}/{@code player.}/{@code zombie.}/{@code horse.} prefix in
     * 1.21.2, and jump strength moved from {@code horse.} to {@code generic.} in 1.20.5, so every
     * spelling is tried: {@code GENERIC_ATTACK_DAMAGE}, {@code generic.attack_damage},
     * {@code ATTACK_DAMAGE} and {@code minecraft:attack_damage} all find the attribute.
     */
    static List<String> attributeKeys(String name) {
        Set<String> out = new LinkedHashSet<>();
        String s = stripMinecraft(name.trim().toLowerCase(Locale.ROOT));
        String base = attributeBase(name);
        for (String prefix : ATTRIBUTE_PREFIXES) {
            if (s.startsWith(prefix) && s.length() > prefix.length() + 1
                    && (s.charAt(prefix.length()) == '.' || s.charAt(prefix.length()) == '_')) {
                out.add(prefix + "." + base);
            }
        }
        out.add(base);
        for (String prefix : ATTRIBUTE_PREFIXES) {
            out.add(prefix + "." + base);
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ names

    /** Upper case, {@code -} and spaces as {@code _}. */
    static String constant(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /**
     * The flag names to try for one written flag: the name itself, then its alias across the
     * 1.20.5 rename ({@code HIDE_POTION_EFFECTS} and {@code HIDE_ADDITIONAL_TOOLTIP}).
     */
    static List<String> flagNames(String written) {
        String name = constant(written);
        return switch (name) {
            case "HIDE_POTION_EFFECTS" -> List.of(name, "HIDE_ADDITIONAL_TOOLTIP");
            case "HIDE_ADDITIONAL_TOOLTIP" -> List.of(name, "HIDE_POTION_EFFECTS");
            default -> List.of(name);
        };
    }

    /**
     * The registry keys to try for an enchantment written as a key ({@code minecraft:sharpness},
     * {@code sharpness}) or a Bukkit constant name ({@code DAMAGE_ALL}).
     */
    static List<String> enchantmentKeys(String written) {
        return registryKeys(written, LEGACY_ENCHANTMENTS, RENAMED_ENCHANTMENTS);
    }

    /** Like {@link #enchantmentKeys} for potion effects ({@code INCREASE_DAMAGE -> strength}). */
    static List<String> effectKeys(String written) {
        return registryKeys(written, LEGACY_EFFECTS, Map.of());
    }

    /**
     * The key of a namespaced id as written, or {@code minecraft:} keys for the legacy constant
     * (when it is one), the name itself and its other spelling across a rename.
     */
    private static List<String> registryKeys(String written, Map<String, String> legacy,
                                             Map<String, String> renamed) {
        String lower = written.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (lower.indexOf(':') > 0 && !lower.startsWith("minecraft:")) {
            return List.of(lower);
        }
        String bare = stripMinecraft(lower);
        Set<String> names = new LinkedHashSet<>();
        String mapped = legacy.get(bare.toUpperCase(Locale.ROOT));
        if (mapped != null) {
            names.add(mapped);
        }
        names.add(bare);
        List<String> out = new ArrayList<>();
        for (String name : names) {
            out.add("minecraft:" + name);
            String other = renamed.get(name);
            if (other != null) {
                out.add("minecraft:" + other);
            }
        }
        return out;
    }

    private static String stripMinecraft(String lower) {
        return lower.startsWith("minecraft:") ? lower.substring("minecraft:".length()) : lower;
    }

    /**
     * An equipment slot name: {@code MAINHAND}/{@code MAIN_HAND}/{@code HAND},
     * {@code OFFHAND}/{@code OFF_HAND}, {@code HEAD}, {@code CHEST}, {@code LEGS}, {@code FEET};
     * null for anything else.
     */
    static EquipmentSlot equipmentSlot(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return switch (constant(name)) {
            case "MAINHAND", "MAIN_HAND", "HAND" -> EquipmentSlot.HAND;
            case "OFFHAND", "OFF_HAND" -> EquipmentSlot.OFF_HAND;
            case "HEAD" -> EquipmentSlot.HEAD;
            case "CHEST" -> EquipmentSlot.CHEST;
            case "LEGS" -> EquipmentSlot.LEGS;
            case "FEET" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ text and materials

    /**
     * Splits a text on line breaks ({@code \r\n} or {@code \n}): a value injected by one
     * placeholder can then fill several lore lines. A trailing line break adds no empty line
     * ({@code "a\nb\n"} gives two lines). Never empty: {@code ""} gives one empty line.
     */
    static List<String> lines(String text) {
        if (text == null) {
            return List.of("");
        }
        if (text.indexOf('\n') < 0) {
            return List.of(text.indexOf('\r') < 0 ? text : text.replace("\r", ""));
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        int length = text.length();
        for (int i = 0; i <= length; i++) {
            if (i == length && start == length && !out.isEmpty()) {
                break; // the text ended with a line break: no empty last line
            }
            if (i == length || text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                out.add(text.substring(start, end));
                start = i + 1;
            }
        }
        return out;
    }

    /**
     * Reads a material spec. A head texture (any form {@link HeadUtil} accepts) gives a player
     * head with that texture, and a texture form with a broken payload a plain player head,
     * silently; otherwise the material is looked up by name or key, ignoring case.
     * An unknown name, and a block that has no item form ({@code WATER}, {@code FIRE}), is
     * reported and gives STONE, so one typo never empties a whole menu.
     *
     * @param isItem whether a material has an item form (the server's answer; tests pass their own)
     */
    static MaterialSpec material(String raw, Predicate<Material> isItem, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) {
            return new MaterialSpec(Material.STONE, null);
        }
        String texture = HeadUtil.extractTextureValue(raw);
        if (texture != null) {
            return new MaterialSpec(Material.PLAYER_HEAD, texture);
        }
        if (looksLikeTexture(raw)) {
            // A texture form whose payload is broken: a plain head, silently.
            return new MaterialSpec(Material.PLAYER_HEAD, null);
        }
        Material material = lookupMaterial(raw);
        if (material == null) {
            warn.accept("Unknown material '" + raw.trim() + "'; using STONE.");
            return new MaterialSpec(Material.STONE, null);
        }
        if (!isItem.test(material)) {
            warn.accept("Material " + material.name() + " is a block with no item form; using STONE.");
            return new MaterialSpec(Material.STONE, null);
        }
        return new MaterialSpec(material, null);
    }

    /** True when {@code raw} starts like a head texture: one of its prefixes or a skin URL. */
    static boolean looksLikeTexture(String raw) {
        String text = raw.trim();
        for (String prefix : TEXTURE_PREFIXES) {
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }

    /** A material by constant name or key ({@code diamond_sword}, {@code minecraft:diamond_sword}); null when none. */
    static Material lookupMaterial(String raw) {
        String trimmed = raw.trim();
        Material exact = Material.getMaterial(constant(stripMinecraft(trimmed.toLowerCase(Locale.ROOT))));
        if (exact != null && !exact.name().startsWith("LEGACY_")) {
            return exact;
        }
        try {
            Material matched = Material.matchMaterial(trimmed);
            return matched == null || matched.name().startsWith("LEGACY_") ? null : matched;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Whether the server gives {@code material} an item form; true when it cannot tell. */
    static boolean hasItemForm(Material material) {
        try {
            return material.isItem();
        } catch (RuntimeException | LinkageError noServer) {
            return true;
        }
    }
}
