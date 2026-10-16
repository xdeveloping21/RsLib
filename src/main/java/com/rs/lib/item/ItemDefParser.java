package com.rs.lib.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Reads an {@link ItemDef} from its YAML section (the keys its class comment lists). Bad values
 * are reported through the warning sink and keep their defaults; nothing here throws for
 * content.
 */
final class ItemDefParser {

    private ItemDefParser() {
    }

    /**
     * The item written in {@code section}. Bad values are reported through {@code warn} and keep
     * their defaults; this never throws for content.
     */
    static ItemDef parse(ConfigurationSection section, ItemAppearance appearance, Consumer<String> warn) {
        ItemDef.Builder builder = ItemDef.builder();
        ItemValues b = builder.values;
        b.appearance = appearance;
        b.droppable = bool(section, "droppable", true, warn);
        b.moveable = bool(section, "moveable", true, warn);
        b.placeable = bool(section, "placeable", true, warn);
        b.tradeable = bool(section, "tradeable", true, warn);
        b.despawnable = bool(section, "despawnable", true, warn);
        b.keepOnDeath = bool(section, "keep-on-death", false, warn);
        b.locked = bool(section, "locked", false, warn);
        b.noDrop = bool(section, "no-drop", false, warn);
        b.noManualEquip = bool(section, "no-manual-equip", false, warn);
        b.cooldownTicks = integer(section, "cooldown", 0, warn);
        String obtain = section.getString("obtain-via", "");
        if (obtain != null && !obtain.isBlank()) {
            if (ItemParts.constant(obtain).equals("COMMAND_ONLY")) {
                b.obtainVia = ObtainMode.COMMAND_ONLY;
            } else if (!ItemParts.constant(obtain).equals("UNRESTRICTED")) {
                warn.accept("Unknown obtain-via '" + obtain + "' (\"\" or COMMAND_ONLY); using \"\".");
            }
        }
        ConfigurationSection durability = section.getConfigurationSection("custom-durability");
        if (durability != null) {
            b.durabilityMax = integer(durability, "max", 0, warn);
            b.durabilityDamagePerUse = Math.max(1, integer(durability, "damage-per-use", 1, warn));
            b.durabilityLoreFormat = durability.getString("lore-format", "");
            b.breakActions = list(durability, "break-actions", warn);
        } else if (section.contains("custom-durability")) {
            warn.accept("'custom-durability' must be a section (max, damage-per-use, lore-format, break-actions); ignored.");
        }
        for (ItemUse use : ItemUse.values()) {
            b.useActions.put(use, list(section, use.key(), warn));
        }
        b.shiftOverridesGeneric = bool(section, "shift-overrides-generic", true, warn);
        b.interactRequirements = list(section, "interact-requirements", warn);
        b.denyActions = list(section, "deny-actions", warn);
        b.pickupActions = list(section, "pickup-actions", warn);
        b.dropActions = list(section, "drop-actions", warn);
        ConfigurationSection held = section.getConfigurationSection("held-effects");
        if (held != null) {
            b.heldEffectsMainhand = list(held, "mainhand", warn);
            b.heldEffectsOffhand = list(held, "offhand", warn);
            b.heldEffectsArmor = list(held, "armor", warn);
        }
        ConfigurationSection recipe = section.getConfigurationSection("recipe");
        if (recipe != null) {
            b.recipe = recipe(recipe, warn);
        }
        return builder.build();
    }

    private static boolean bool(ConfigurationSection section, String key, boolean def, Consumer<String> warn) {
        Object value = section.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(text);
        }
        warn.accept("'" + key + "' must be true or false, got '" + value + "'; using " + def + ".");
        return def;
    }

    private static int integer(ConfigurationSection section, String key, int def, Consumer<String> warn) {
        Object value = section.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.intValue();
        }
        Integer parsed = ItemParts.intOrNull(String.valueOf(value).trim());
        if (parsed != null) {
            return parsed;
        }
        warn.accept("'" + key + "' must be a whole number, got '" + value + "'; using " + def + ".");
        return def;
    }

    /** A list of texts; nested lists or maps (an unquoted {@code - [close]}) are reported and skipped. */
    static List<String> list(ConfigurationSection section, String key, Consumer<String> warn) {
        Object value = section.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return List.of(String.valueOf(value));
        }
        if (!(value instanceof List<?> entries)) {
            warn.accept("'" + key + "' must be a list (one '- ' line per entry), got '" + value + "'; ignored.");
            return List.of();
        }
        List<String> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (entry instanceof List<?> || entry instanceof Map<?, ?> || entry instanceof ConfigurationSection) {
                warn.accept("'" + key + "' entry " + (i + 1) + " is not text (" + entry
                        + "); write it in quotes, for example - '[close]'; skipped.");
            } else if (entry != null) {
                out.add(String.valueOf(entry));
            }
        }
        return out;
    }

    /** The recipe written in {@code section}, or null (reported through {@code warn}) when it is written wrong. */
    static ItemDef.Recipe recipe(ConfigurationSection section, Consumer<String> warn) {
        String type = ItemParts.constant(section.getString("type", ""));
        switch (type) {
            case "SHAPED" -> {
                List<String> shape = list(section, "shape", warn);
                ConfigurationSection map = section.getConfigurationSection("ingredients");
                if (shape.isEmpty() || map == null) {
                    warn.accept("A SHAPED recipe needs 'shape' and an 'ingredients' section (letter: MATERIAL); ignored.");
                    return null;
                }
                Map<Character, String> ingredients = new LinkedHashMap<>();
                for (String key : map.getKeys(false)) {
                    if (key.length() != 1) {
                        warn.accept("Recipe ingredient key '" + key + "' must be one character; skipped.");
                        continue;
                    }
                    ingredients.put(key.charAt(0), map.getString(key, ""));
                }
                return ItemDef.Recipe.shaped(shape, ingredients);
            }
            case "SHAPELESS" -> {
                List<String> ingredients = list(section, "ingredients", warn);
                if (ingredients.isEmpty()) {
                    warn.accept("A SHAPELESS recipe needs an 'ingredients' list; ignored.");
                    return null;
                }
                return ItemDef.Recipe.shapeless(ingredients);
            }
            case "FURNACE", "SMOKING", "BLASTING", "CAMPFIRE" -> {
                String input = section.getString("input", "");
                if (input == null || input.isBlank()) {
                    warn.accept("A " + type + " recipe needs 'input'; ignored.");
                    return null;
                }
                return ItemDef.Recipe.cooking(type, input, section.getDouble("experience", 0),
                        integer(section, "cooking-time", ItemDef.Recipe.DEFAULT_COOKING_TICKS, warn));
            }
            case "STONECUTTING" -> {
                String input = section.getString("input", "");
                if (input == null || input.isBlank()) {
                    warn.accept("A STONECUTTING recipe needs 'input'; ignored.");
                    return null;
                }
                return ItemDef.Recipe.stonecutting(input);
            }
            default -> {
                warn.accept("Unknown recipe type '" + section.getString("type", "") + "' (SHAPED, SHAPELESS, FURNACE,"
                        + " SMOKING, BLASTING, CAMPFIRE, STONECUTTING); ignored.");
                return null;
            }
        }
    }
}
