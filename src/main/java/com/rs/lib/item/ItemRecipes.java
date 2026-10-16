package com.rs.lib.item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The crafting recipes of one plugin's items, added to the server under
 * {@code <plugin>:snlib_recipe_<id>} and removed again on reload and shutdown. A recipe
 * written wrong is reported and not added.
 */
final class ItemRecipes {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Set<NamespacedKey> added = new LinkedHashSet<>();

    ItemRecipes(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /** Adds (or replaces) the recipe of item {@code id}; {@code result} builds the stack it makes. */
    void add(String id, ItemDef.Recipe recipe, Supplier<ItemStack> result) {
        NamespacedKey key = new NamespacedKey(plugin,
                ItemRegistry.RECIPE_PREFIX + ItemCompat.keyPart(id.toLowerCase(Locale.ROOT)));
        Consumer<String> warn = ItemWarnings.to(logger, "item " + id + " -> recipe");
        try {
            if (Bukkit.getRecipe(key) != null) {
                Bukkit.removeRecipe(key);
            }
            org.bukkit.inventory.Recipe bukkit = toBukkit(key, result.get(), recipe, warn);
            if (bukkit != null && Bukkit.addRecipe(bukkit)) {
                synchronized (added) {
                    added.add(key);
                }
            }
        } catch (RuntimeException refused) {
            warn.accept("The recipe could not be added: " + refused.getMessage());
        }
    }

    /** Removes every recipe added so far. */
    void removeAll() {
        List<NamespacedKey> keys;
        synchronized (added) {
            keys = new ArrayList<>(added);
            added.clear();
        }
        for (NamespacedKey key : keys) {
            try {
                Bukkit.removeRecipe(key);
            } catch (RuntimeException ignored) {
                // The server is stopping or the recipe is already gone.
            }
        }
    }

    private static org.bukkit.inventory.Recipe toBukkit(NamespacedKey key, ItemStack result, ItemDef.Recipe recipe,
                                                      Consumer<String> warn) {
        switch (recipe.type()) {
            case "SHAPED" -> {
                ShapedRecipe shaped = new ShapedRecipe(key, result);
                shaped.shape(recipe.shape().toArray(new String[0]));
                for (Map.Entry<Character, String> entry : recipe.ingredients().entrySet()) {
                    Material material = material(entry.getValue(), warn);
                    if (material == null) {
                        return null;
                    }
                    shaped.setIngredient(entry.getKey(), material);
                }
                return shaped;
            }
            case "SHAPELESS" -> {
                ShapelessRecipe shapeless = new ShapelessRecipe(key, result);
                for (String name : recipe.shapelessIngredients()) {
                    Material material = material(name, warn);
                    if (material == null) {
                        return null;
                    }
                    shapeless.addIngredient(material);
                }
                return shapeless;
            }
            case "STONECUTTING" -> {
                Material input = material(recipe.input(), warn);
                return input == null ? null : new StonecuttingRecipe(key, result, input);
            }
            default -> {
                Material input = material(recipe.input(), warn);
                if (input == null) {
                    return null;
                }
                RecipeChoice choice = new RecipeChoice.MaterialChoice(input);
                float xp = (float) recipe.experience();
                int ticks = recipe.cookingTime();
                return switch (recipe.type()) {
                    case "SMOKING" -> new SmokingRecipe(key, result, choice, xp, ticks);
                    case "BLASTING" -> new BlastingRecipe(key, result, choice, xp, ticks);
                    case "CAMPFIRE" -> new CampfireRecipe(key, result, choice, xp, ticks);
                    default -> new FurnaceRecipe(key, result, choice, xp, ticks);
                };
            }
        }
    }

    private static Material material(String name, Consumer<String> warn) {
        Material material = name == null ? null : ItemParts.lookupMaterial(name);
        if (material == null) {
            warn.accept("Unknown recipe material '" + name + "'; the recipe is not added.");
        }
        return material;
    }
}
