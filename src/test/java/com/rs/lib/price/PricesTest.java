package com.rs.lib.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

public class PricesTest {

    /** A stack that is only a material and an amount (no server needed). */
    static final class Stack extends ItemStack {
        private final Material type;
        private final int amount;

        @SuppressWarnings("deprecation")
        Stack(Material type, int amount) {
            super();
            this.type = type;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return type;
        }

        @Override
        public int getAmount() {
            return amount;
        }
    }

    /** Essentials' worth list in memory, counting the questions. */
    static final class FakeWorth implements Prices.Lookup {
        final Map<Material, BigDecimal> prices = new java.util.EnumMap<>(Material.class);
        final List<Material> asked = new ArrayList<>();
        RuntimeException failure;

        @Override
        public BigDecimal unitPrice(ItemStack stack) {
            asked.add(stack.getType());
            if (failure != null) {
                throw failure;
            }
            return prices.get(stack.getType());
        }
    }

    private final List<String> warnings = new ArrayList<>();
    private final Logger logger = Logger.getAnonymousLogger();
    private final FakeWorth essentials = new FakeWorth();
    private boolean essentialsInstalled = true;
    private final Prices prices;

    public PricesTest() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        prices = new Prices(logger, () -> essentialsInstalled ? essentials : null);
    }

    @Test
    void thePluginsOwnPriceComesFirstAndEssentialsIsNotAsked() {
        prices.set(Material.DIAMOND, 120);
        essentials.prices.put(Material.DIAMOND, new BigDecimal("80"));
        SellPrice price = prices.sellPrice(new Stack(Material.DIAMOND, 3)).orElseThrow();
        assertEquals(Prices.OWN, price.source());
        assertEquals(0, new BigDecimal("120").compareTo(price.each()));
        assertEquals(0, new BigDecimal("360").compareTo(price.total()));
        assertTrue(essentials.asked.isEmpty());
    }

    @Test
    void essentialsAnswersForWhatThePluginDoesNotPrice() {
        essentials.prices.put(Material.EMERALD, new BigDecimal("0.1"));
        SellPrice price = prices.sellPrice(new Stack(Material.EMERALD, 3)).orElseThrow();
        assertEquals(Prices.ESSENTIALS, price.source());
        assertEquals(new BigDecimal("0.3"), price.total(), "exact, never 0.30000000000000004");
        prices.set(Material.EMERALD, 2);
        assertEquals(Prices.OWN, prices.sellPrice(new Stack(Material.EMERALD, 1)).orElseThrow().source());
        prices.remove(Material.EMERALD);
        assertEquals(Prices.ESSENTIALS, prices.sellPrice(new Stack(Material.EMERALD, 1)).orElseThrow().source());
    }

    @Test
    void aPriceOfZeroInTheOwnListMeansNotSellableEvenIfEssentialsPricesIt() {
        prices.set(Material.DIRT, 0);
        essentials.prices.put(Material.DIRT, BigDecimal.ONE);
        assertFalse(prices.sellable(new Stack(Material.DIRT, 1)));
        assertTrue(essentials.asked.isEmpty());
    }

    @Test
    void unknownPricesAreNotSellable() {
        assertEquals(Optional.empty(), prices.sellPrice(new Stack(Material.STONE, 1)));
        essentials.prices.put(Material.STONE, BigDecimal.ZERO);
        assertFalse(prices.sellable(new Stack(Material.STONE, 1)));
        essentials.prices.put(Material.STONE, new BigDecimal("-1"));
        assertFalse(prices.sellable(new Stack(Material.STONE, 1)));
        essentialsInstalled = false;
        essentials.prices.put(Material.STONE, BigDecimal.TEN);
        assertFalse(prices.sellable(new Stack(Material.STONE, 1)), "without Essentials nothing answers");
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anEssentialsFailureIsNotSellableAndWarnsOnce() {
        essentials.prices.put(Material.GOLD_INGOT, BigDecimal.TEN);
        essentials.failure = new IllegalStateException("worth.yml broken");
        assertFalse(prices.sellable(new Stack(Material.GOLD_INGOT, 1)));
        assertFalse(prices.sellable(new Stack(Material.GOLD_INGOT, 1)));
        assertEquals(1, warnings.size(), warnings.toString());
        essentials.failure = null;
        assertTrue(prices.sellable(new Stack(Material.GOLD_INGOT, 1)));
    }

    @Test
    void nothingAirOrEmptyStacksAreNeverPriced() {
        prices.set(Material.AIR, 5);
        prices.set(Material.DIAMOND, 5);
        assertFalse(prices.sellable(null));
        assertFalse(prices.sellable(new Stack(Material.AIR, 1)));
        assertFalse(prices.sellable(new Stack(Material.CAVE_AIR, 1)));
        assertFalse(prices.sellable(new Stack(Material.DIAMOND, 0)));
        assertTrue(essentials.asked.isEmpty());
    }

    @Test
    void negativeOrNonFinitePricesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> prices.set(Material.DIAMOND, -1));
        assertThrows(IllegalArgumentException.class, () -> prices.set(Material.DIAMOND, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> prices.set(Material.DIAMOND, new BigDecimal("-0.5")));
        assertTrue(prices.ownPrices().isEmpty());
    }

    @Test
    void aSectionReplacesTheOwnListAndSkipsBadLines() throws InvalidConfigurationException {
        prices.set(Material.COAL, 1);
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString("""
                prices:
                  diamond: 100
                  STONE_BRICKS: 0.5
                  oak-log: '2'
                  'minecraft:emerald': 3
                  dirt: 0
                  not_a_block: 4
                  iron_ingot: -1
                  gold_ingot: lots
                """);
        assertEquals(5, prices.load(yml.getConfigurationSection("prices")));
        Map<Material, BigDecimal> own = prices.ownPrices();
        assertEquals(new BigDecimal("100"), own.get(Material.DIAMOND));
        assertEquals(new BigDecimal("0.5"), own.get(Material.STONE_BRICKS));
        assertEquals(new BigDecimal("2"), own.get(Material.OAK_LOG));
        assertEquals(new BigDecimal("3"), own.get(Material.EMERALD));
        assertEquals(BigDecimal.ZERO, own.get(Material.DIRT));
        assertFalse(own.containsKey(Material.COAL), "loading replaces the list");
        assertEquals(3, warnings.size(), warnings.toString());
        assertEquals(0, prices.load(null));
        assertTrue(prices.ownPrices().isEmpty());
    }

    // ---------------------------------------------------------------- Essentials through reflection

    public static final class TwoArgEssentials {
        public TwoArgWorth getWorth() {
            return new TwoArgWorth(this);
        }
    }

    public static final class TwoArgWorth {
        private final TwoArgEssentials owner;

        TwoArgWorth(TwoArgEssentials owner) {
            this.owner = owner;
        }

        public BigDecimal getPrice(TwoArgEssentials essentials, ItemStack stack) {
            if (essentials != owner) {
                throw new IllegalArgumentException("wrong Essentials");
            }
            return stack.getType() == Material.DIAMOND ? new BigDecimal("42.5") : null;
        }
    }

    public static final class OneArgEssentials {
        public OneArgWorth getWorth() {
            return new OneArgWorth();
        }
    }

    public static final class OneArgWorth {
        public Double getPrice(ItemStack stack) {
            return stack.getType() == Material.DIAMOND ? 7.0 : null;
        }
    }

    public static final class NoWorth {
        public Object getWorth() {
            return new Object();
        }
    }

    @Test
    void essentialsIsReadThroughReflectionInBothShapes() {
        EssentialsWorth two = EssentialsWorth.of(new TwoArgEssentials());
        assertEquals(new BigDecimal("42.5"), two.unitPrice(new Stack(Material.DIAMOND, 1)));
        assertEquals(null, two.unitPrice(new Stack(Material.STONE, 1)));
        EssentialsWorth one = EssentialsWorth.of(new OneArgEssentials());
        assertEquals(0, new BigDecimal("7").compareTo(one.unitPrice(new Stack(Material.DIAMOND, 1))));
        assertThrows(IllegalStateException.class, () -> EssentialsWorth.of(new NoWorth()));
        assertThrows(IllegalStateException.class, () -> EssentialsWorth.of(new Object()));
    }
}
