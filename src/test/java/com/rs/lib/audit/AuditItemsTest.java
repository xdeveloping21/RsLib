package com.rs.lib.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

class AuditItemsTest {

    /** A stack of a material, an optional custom name and an amount; similar when both match. */
    static final class Stack extends ItemStack {
        final Material material;
        final String custom;
        int amount;

        @SuppressWarnings("deprecation")
        Stack(Material material, String custom, int amount) {
            super();
            this.material = material;
            this.custom = custom;
            this.amount = amount;
        }

        @Override
        public Stack clone() {
            return new Stack(material, custom, amount);
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public boolean isSimilar(ItemStack other) {
            return other instanceof Stack stack && stack.material == material
                    && java.util.Objects.equals(stack.custom, custom);
        }

        @Override
        public boolean hasItemMeta() {
            return custom != null;
        }

        @Override
        public ItemMeta getItemMeta() {
            return (ItemMeta) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ItemMeta.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasDisplayName" -> true;
                        case "displayName" -> Component.text(custom, NamedTextColor.RED);
                        default -> null;
                    });
        }
    }

    private static Stack stack(Material material, int amount) {
        return new Stack(material, null, amount);
    }

    @Test
    void itemsAreDescribedByAmountAndNameWithCustomNamesInQuotes() {
        assertEquals("16x Diamond", AuditItems.describe(stack(Material.DIAMOND, 16)));
        assertEquals("1x Diamond Sword \"&cExcalibur\"", AuditItems.describe(new Stack(Material.DIAMOND_SWORD, "&cExcalibur", 1)),
                "the custom name is plain text, kept as written");
        assertEquals("", AuditItems.describe((ItemStack) null));
        assertEquals("", AuditItems.describe(stack(Material.AIR, 1)));
        assertEquals("64x Diamond, 1x Dirt", AuditItems.describe(List.of(stack(Material.DIAMOND, 60),
                stack(Material.DIRT, 1), stack(Material.DIAMOND, 4))), "equal items are added together");
    }

    @Test
    void moneyIsGroupedWithAnOptionalCurrency() {
        assertEquals("1,500", AuditItems.money(1500));
        assertEquals("1,234.57", AuditItems.money(1234.567));
        assertEquals("1,500 coins", AuditItems.money(1500, " coins "));
        assertEquals("2.5", AuditItems.money(new BigDecimal("2.50")));
        assertEquals("1,500", AuditItems.money(1500, ""));
    }

    @Test
    void theDiffIsByItemNotBySlot() {
        ItemStack[] before = {stack(Material.DIAMOND, 64), null, stack(Material.DIRT, 10), stack(Material.STONE, 5)};
        ItemStack[] after = {null, stack(Material.DIRT, 4), stack(Material.DIAMOND, 64), stack(Material.STONE, 3),
                stack(Material.GOLD_INGOT, 2), stack(Material.DIRT, 7)};
        AuditItems.Diff diff = AuditItems.diff(before, after);
        assertEquals(List.of("2x Stone"), diff.removed().stream().map(AuditItems.Count::describe).toList());
        assertEquals(List.of("1x Dirt", "2x Gold Ingot"), diff.added().stream().map(AuditItems.Count::describe).toList(),
                "the diamonds only moved; dirt went from 10 to 11");
        assertEquals("Removed: 2x Stone | Added: 1x Dirt, 2x Gold Ingot", diff.describe());
    }

    @Test
    void nothingChangedIsAnEmptyDiff() {
        ItemStack[] contents = {stack(Material.DIAMOND, 3), stack(Material.DIAMOND, 1)};
        AuditItems.Diff diff = AuditItems.diff(contents, new ItemStack[] {stack(Material.DIAMOND, 4), null});
        assertTrue(diff.isEmpty());
        assertEquals("", diff.describe());
        AuditItems.Diff emptied = AuditItems.diff(contents, null);
        assertEquals("Removed: 4x Diamond", emptied.describe());
    }

    @Test
    void aSnapshotDoesNotChangeWithTheContainer() {
        Stack live = stack(Material.DIAMOND, 5);
        ItemStack[] contents = {live, null};
        ItemStack[] before = AuditItems.snapshot(contents);
        assertNotSame(live, before[0]);
        assertNull(before[1]);
        live.setAmount(1);
        assertEquals("Removed: 4x Diamond", AuditItems.diff(before, contents).describe());
        assertEquals(0, AuditItems.snapshot(null).length);
    }

    @Test
    void enumNamesArePrettified() {
        assertEquals("Diamond Sword", AuditItems.pretty("DIAMOND_SWORD"));
        assertEquals("Tnt", AuditItems.pretty("TNT"));
    }
}
