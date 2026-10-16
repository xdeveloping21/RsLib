package com.rs.lib.audit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.rs.lib.util.NumberFormatter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * How audit entries write money and items, and what changed in a container.
 *
 * <pre>
 * ItemStack[] before = AuditItems.snapshot(chest.getInventory().getContents());
 * ... the player edits the chest ...
 * AuditItems.Diff diff = AuditItems.diff(before, chest.getInventory().getContents());
 * if (!diff.isEmpty()) {
 *     rs().audit().entry("chest-edit").actor(player).items(diff.describe()).record();
 * }
 * </pre>
 */
public final class AuditItems {

    /** An item and how many of it, which may be more than one stack holds. */
    public record Count(ItemStack item, int amount) {

        /** {@code 64x Diamond}, or {@code 1x Diamond Sword "Excalibur"} for a renamed item. */
        public String describe() {
            return amount + "x " + name(item);
        }
    }

    /**
     * What came into a container and what left it, by item rather than by slot: moving a stack
     * from one slot to another changes nothing.
     *
     * @param added   what there is more of, in the order it appears afterwards
     * @param removed what there is less of, in the order it appeared before
     */
    public record Diff(List<Count> added, List<Count> removed) {

        public Diff {
            added = List.copyOf(added);
            removed = List.copyOf(removed);
        }

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }

        /** {@code Removed: 64x Diamond | Added: 1x Dirt}; empty when nothing changed. */
        public String describe() {
            StringJoiner parts = new StringJoiner(" | ");
            if (!removed.isEmpty()) {
                parts.add("Removed: " + join(removed));
            }
            if (!added.isEmpty()) {
                parts.add("Added: " + join(added));
            }
            return parts.toString();
        }
    }

    private AuditItems() {
    }

    /** {@code 1,500} (up to two decimals). */
    public static String money(double amount) {
        return NumberFormatter.formatComma(amount);
    }

    /** {@code 1,500 coins}; a null or blank currency writes the amount alone. */
    public static String money(double amount, String currency) {
        String number = money(amount);
        return currency == null || currency.isBlank() ? number : number + " " + currency.trim();
    }

    /** {@link #money(double)} of a {@link BigDecimal}. */
    public static String money(BigDecimal amount) {
        return amount == null ? "" : money(amount.doubleValue());
    }

    /** {@code 16x Diamond}; empty for nothing (null, air or no amount). */
    public static String describe(ItemStack item) {
        return isEmpty(item) ? "" : new Count(item, item.getAmount()).describe();
    }

    /** {@code 64x Diamond, 1x Dirt}: equal items added together, in the order they first appear. */
    public static String describe(Collection<ItemStack> items) {
        return items == null ? "" : join(count(items.toArray(new ItemStack[0])));
    }

    /** A copy of a container's contents that later changes to the container do not touch. */
    public static ItemStack[] snapshot(ItemStack[] contents) {
        if (contents == null) {
            return new ItemStack[0];
        }
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    /** What changed between {@code before} (from {@link #snapshot}) and {@code after}. */
    public static Diff diff(ItemStack[] before, ItemStack[] after) {
        List<Count> was = count(before);
        List<Count> now = count(after);
        List<Count> added = new ArrayList<>();
        List<Count> removed = new ArrayList<>();
        for (Count old : was) {
            int left = amountOf(now, old.item());
            if (left < old.amount()) {
                removed.add(new Count(old.item(), old.amount() - left));
            }
        }
        for (Count fresh : now) {
            int had = amountOf(was, fresh.item());
            if (fresh.amount() > had) {
                added.add(new Count(fresh.item(), fresh.amount() - had));
            }
        }
        return new Diff(added, removed);
    }

    /** Equal items of {@code items} added together, in the order they first appear. */
    static List<Count> count(ItemStack[] items) {
        List<Count> counts = new ArrayList<>();
        if (items == null) {
            return counts;
        }
        for (ItemStack item : items) {
            if (isEmpty(item)) {
                continue;
            }
            boolean merged = false;
            for (int i = 0; i < counts.size(); i++) {
                Count count = counts.get(i);
                if (count.item().isSimilar(item)) {
                    counts.set(i, new Count(count.item(), count.amount() + item.getAmount()));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                ItemStack template = item.clone();
                counts.add(new Count(template, item.getAmount()));
            }
        }
        return counts;
    }

    private static int amountOf(List<Count> counts, ItemStack item) {
        for (Count count : counts) {
            if (count.item().isSimilar(item)) {
                return count.amount();
            }
        }
        return 0;
    }

    private static String join(List<Count> counts) {
        StringJoiner out = new StringJoiner(", ");
        for (Count count : counts) {
            out.add(count.describe());
        }
        return out.toString();
    }

    private static boolean isEmpty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }
        Material type = item.getType();
        return type == null || type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    /** {@code Diamond Sword}, followed by the custom name in quotes when the item has one. */
    static String name(ItemStack item) {
        String material = pretty(item.getType().name());
        String custom = customName(item);
        return custom == null || custom.isBlank() ? material : material + " \"" + custom + "\"";
    }

    private static String customName(ItemStack item) {
        try {
            if (!item.hasItemMeta()) {
                return null;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) {
                return null;
            }
            Component name = meta.displayName();
            return name == null ? null : PlainTextComponentSerializer.plainText().serialize(name);
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    static String pretty(String enumName) {
        StringBuilder out = new StringBuilder(enumName.length());
        for (String word : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }
}
