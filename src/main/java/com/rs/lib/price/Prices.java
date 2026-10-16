package com.rs.lib.price;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Rs;
import com.rs.lib.hook.SoftDependency;
import com.rs.lib.yml.RsYml;

/**
 * What an item sells for, reached with {@code rs().prices()}.
 *
 * <pre>
 * rs().prices().load(rs().yml().managed("prices.yml"), "prices");
 * rs().prices().sellPrice(stack).ifPresent(price -&gt; rs().economy().give(player, price.total()));
 * </pre>
 *
 * <ul>
 *   <li>Two sources, asked in this order, and the first that knows the item answers: the
 *   plugin's own prices ({@link #set}, {@link #load}), then EssentialsX's {@code worth.yml} when
 *   Essentials is installed (read through reflection, RsLib does not depend on it).</li>
 *   <li>Prices are per material. A price of 0 in the plugin's own list means the plugin does
 *   not buy that item: it is not sellable, and Essentials is not asked.</li>
 *   <li>Fails closed: an item nobody prices, a price Essentials cannot give (an error, an old
 *   or changed Essentials) or a price that is not a number above 0 means "not sellable".</li>
 *   <li>Lookups read memory only and may be made from any thread.</li>
 * </ul>
 */
public final class Prices {

    /** Source name of the plugin's own prices. */
    public static final String OWN = "own";
    /** Source name of EssentialsX's {@code worth.yml}. */
    public static final String ESSENTIALS = "essentials";

    /** A price list kept by another plugin. */
    @FunctionalInterface
    interface Lookup {

        /** The price of one item like {@code stack}; null when it has none. */
        BigDecimal unitPrice(ItemStack stack);
    }

    private final Logger logger;
    private final Supplier<Lookup> essentials;
    private final Map<Material, BigDecimal> own = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** Created by the context. */
    public Prices(Rs rs) {
        this(rs.plugin().getLogger(), essentialsOf(rs));
    }

    Prices(Logger logger, Supplier<Lookup> essentials) {
        this.logger = logger;
        this.essentials = essentials;
    }

    /** Essentials' worth list, hooked the first time a price is asked of it. */
    private static Supplier<Lookup> essentialsOf(Rs rs) {
        return new Supplier<>() {
            private volatile SoftDependency<Lookup> hook;

            @Override
            public Lookup get() {
                SoftDependency<Lookup> dep = hook;
                if (dep == null) {
                    synchronized (this) {
                        if (hook == null) {
                            hook = SoftDependency.of(rs.plugin(), "Essentials", EssentialsWorth::create);
                        }
                        dep = hook;
                    }
                }
                return dep.get().orElse(null);
            }
        };
    }

    // ---------------------------------------------------------------- the plugin's own prices

    /**
     * Sets the plugin's own price of one {@code material}. 0 means the plugin does not buy it;
     * a price below 0 or not finite is refused with an {@link IllegalArgumentException}.
     */
    public void set(Material material, double each) {
        if (!Double.isFinite(each)) {
            throw new IllegalArgumentException("price must be a finite number: " + each);
        }
        set(material, BigDecimal.valueOf(each));
    }

    /** {@link #set(Material, double)} with an exact price. */
    public void set(Material material, BigDecimal each) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(each, "each");
        if (each.signum() < 0) {
            throw new IllegalArgumentException("price must be 0 or above: " + each);
        }
        own.put(material, each);
    }

    /** Forgets the plugin's own price of {@code material}; Essentials answers for it again. */
    public void remove(Material material) {
        own.remove(material);
    }

    /** Forgets every price of the plugin's own list. */
    public void clear() {
        own.clear();
    }

    /** The plugin's own prices, as a copy. */
    public Map<Material, BigDecimal> ownPrices() {
        Map<Material, BigDecimal> copy = new EnumMap<>(Material.class);
        copy.putAll(own);
        return copy;
    }

    /**
     * Replaces the plugin's own prices with {@code section}: one line per material
     * ({@code diamond: 100}, {@code STONE_BRICKS: 0.5}, {@code stone-bricks: 0.5}). A line whose
     * material is unknown or whose price is not a number of 0 or above is skipped with a
     * warning. Null clears the list. Answers how many prices were loaded.
     */
    public int load(ConfigurationSection section) {
        Map<Material, BigDecimal> read = new EnumMap<>(Material.class);
        if (section != null) {
            String where = section.getCurrentPath() == null || section.getCurrentPath().isEmpty()
                    ? "the price list" : "'" + section.getCurrentPath() + "'";
            for (Map.Entry<String, Object> line : section.getValues(false).entrySet()) {
                Material material = material(line.getKey());
                if (material == null) {
                    logger.warning("Unknown material '" + line.getKey() + "' in " + where + "; that price is skipped.");
                    continue;
                }
                BigDecimal price = price(line.getValue());
                if (price == null) {
                    logger.warning("The price of '" + line.getKey() + "' in " + where + " is '" + line.getValue()
                            + "', not a number of 0 or above; that price is skipped.");
                    continue;
                }
                read.put(material, price);
            }
        }
        own.clear();
        own.putAll(read);
        return read.size();
    }

    /**
     * {@link #load(ConfigurationSection)} from the section {@code path} of {@code file}, now and
     * again every time that file is reloaded. Call it once per file: each call adds a reload hook.
     */
    public int load(RsYml file, String path) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(path, "path");
        file.onReload(() -> load(file.getSection(path)));
        return load(file.getSection(path));
    }

    // ---------------------------------------------------------------- asking

    /**
     * What {@code stack} sells for, or empty when it is not sellable: null, air, an amount
     * below 1, or no source prices it above 0.
     */
    public Optional<SellPrice> sellPrice(ItemStack stack) {
        if (stack == null) {
            return Optional.empty();
        }
        Material type = stack.getType();
        int amount = stack.getAmount();
        if (type == null || isAir(type) || amount < 1) {
            return Optional.empty();
        }
        BigDecimal mine = own.get(type);
        if (mine != null) {
            return mine.signum() > 0 ? Optional.of(new SellPrice(mine, amount, OWN)) : Optional.empty();
        }
        BigDecimal worth = essentialsPrice(stack);
        if (worth != null && worth.signum() > 0) {
            return Optional.of(new SellPrice(worth, amount, ESSENTIALS));
        }
        return Optional.empty();
    }

    /** True when {@link #sellPrice} has an answer for {@code stack}. */
    public boolean sellable(ItemStack stack) {
        return sellPrice(stack).isPresent();
    }

    // ---------------------------------------------------------------- internals

    private BigDecimal essentialsPrice(ItemStack stack) {
        try {
            Lookup lookup = essentials.get();
            return lookup == null ? null : lookup.unitPrice(stack);
        } catch (RuntimeException | LinkageError e) {
            if (warned.add("essentials:" + e.getClass().getName())) {
                logger.warning("Could not read a price from Essentials' worth.yml (" + e
                        + "); items it prices are not sellable until it answers.");
            }
            return null;
        }
    }

    private static boolean isAir(Material type) {
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    /** {@code diamond}, {@code DIAMOND}, {@code minecraft:diamond} or {@code stone-bricks}; null when unknown. */
    static Material material(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return Material.matchMaterial(key.trim().toLowerCase(Locale.ROOT).replace('-', '_'));
    }

    /** A number of 0 or above, or null. */
    static BigDecimal price(Object value) {
        if (value instanceof Number || value instanceof String) {
            try {
                BigDecimal price = new BigDecimal(String.valueOf(value).trim());
                return price.signum() < 0 ? null : price;
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }
}
