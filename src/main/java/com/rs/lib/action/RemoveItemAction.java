package com.rs.lib.action;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code [remove-item] [n] [selector]}: takes up to {@code n} items (1 by default) from the
 * player.
 *
 * <ul>
 *   <li>No selector: from the main hand.</li>
 *   <li>{@code offhand}: from the off hand.</li>
 *   <li>{@code id:<item-id>}: items this plugin registered with that id (its
 *   {@code <plugin>:snlib_item_id} or {@code <plugin>:rslib_item_id} tag), from slots 0 to 35
 *   and then the off hand.</li>
 *   <li>A material: stacks of it, same order, never one that carries a library tag
 *   ({@code snlib_*} or {@code rslib_*} key in any namespace): registered items and menu
 *   stacks are not plain materials.</li>
 * </ul>
 *
 * <p>An amount of 0 or less is reported and nothing is touched: a negative amount would add
 * items to the stack, a duplication.</p>
 */
final class RemoveItemAction implements ActionHandler {

    static final List<String> ITEM_ID_KEYS = List.of("snlib_item_id", "rslib_item_id");
    private static final int STORAGE_SLOTS = 36;

    /** Where items are taken from. */
    enum Where { MAIN_HAND, OFF_HAND, ITEM_ID, MATERIAL }

    /** A parsed argument. */
    record Spec(int amount, Where where, String value) {
    }

    private final ActionHost host;
    private final BiConsumer<String, Supplier<String>> warn;

    RemoveItemAction(ActionHost host, BiConsumer<String, Supplier<String>> warn) {
        this.host = host;
        this.warn = warn;
    }

    @Override
    public void run(Player player, String arg, ActionContext context) {
        Spec spec = parse(arg, message -> warn.accept("remove-item:" + arg, () -> message));
        if (player == null || spec == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        switch (spec.where) {
            case MAIN_HAND -> inventory.setItemInMainHand(take(inventory.getItemInMainHand(), spec.amount, null));
            case OFF_HAND -> inventory.setItemInOffHand(take(inventory.getItemInOffHand(), spec.amount, null));
            case ITEM_ID -> {
                JavaPlugin plugin = host.plugin();
                if (plugin != null) {
                    scan(inventory, spec.amount, stack -> hasItemId(stack, plugin, spec.value));
                }
            }
            case MATERIAL -> {
                Material material = Material.matchMaterial(spec.value);
                if (material == null) {
                    warn.accept("remove-item:material:" + spec.value, () -> "[remove-item] " + arg
                            + ": there is no material '" + spec.value + "'; nothing removed.");
                    return;
                }
                scan(inventory, spec.amount, stack -> stack.getType() == material && !libraryTagged(stack));
            }
            default -> throw new IllegalStateException(spec.where.name());
        }
    }

    /** Reads an argument; null (reported) when nothing must be removed. */
    static Spec parse(String arg, Consumer<String> warn) {
        List<String> words = Builtins.words(arg);
        int amount = 1;
        int next = 0;
        if (!words.isEmpty() && !Double.isNaN(RequirementEngine.number(words.get(0)))) {
            double value = RequirementEngine.number(words.get(0));
            if (value != Math.rint(value)) {
                warn.accept("[remove-item] " + arg + ": '" + words.get(0) + "' is not a whole amount; nothing removed.");
                return null;
            }
            amount = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
            next = 1;
        }
        if (amount <= 0) {
            warn.accept("[remove-item] " + arg + ": the amount must be 1 or more; nothing removed.");
            return null;
        }
        if (next >= words.size()) {
            return new Spec(amount, Where.MAIN_HAND, null);
        }
        String selector = words.get(next);
        if (selector.equalsIgnoreCase("offhand")) {
            return new Spec(amount, Where.OFF_HAND, null);
        }
        if (selector.regionMatches(true, 0, "id:", 0, 3)) {
            String id = selector.substring(3);
            if (id.isEmpty()) {
                warn.accept("[remove-item] " + arg + ": 'id:' needs an item id; nothing removed.");
                return null;
            }
            return new Spec(amount, Where.ITEM_ID, id);
        }
        return new Spec(amount, Where.MATERIAL, selector.toUpperCase(Locale.ROOT));
    }

    /** Takes up to {@code left} items from slots 0 to 35, then the off hand. */
    private static void scan(PlayerInventory inventory, int amount, Predicate<ItemStack> matches) {
        int left = amount;
        for (int slot = 0; slot < STORAGE_SLOTS && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && !stack.getType().isAir() && matches.test(stack)) {
                int before = stack.getAmount();
                inventory.setItem(slot, take(stack, left, null));
                left -= Math.min(before, left);
            }
        }
        ItemStack off = inventory.getItemInOffHand();
        if (left > 0 && !off.getType().isAir() && matches.test(off)) {
            inventory.setItemInOffHand(take(off, left, null));
        }
    }

    /** {@code stack} with up to {@code amount} fewer items, or {@code empty} when none are left. */
    private static ItemStack take(ItemStack stack, int amount, ItemStack empty) {
        if (stack == null || stack.getType().isAir()) {
            return stack;
        }
        int remaining = stack.getAmount() - Math.min(amount, stack.getAmount());
        if (remaining <= 0) {
            return empty;
        }
        stack.setAmount(remaining);
        return stack;
    }

    private static boolean hasItemId(ItemStack stack, JavaPlugin plugin, String id) {
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta == null) {
            return false;
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        for (String name : ITEM_ID_KEYS) {
            try {
                if (id.equals(data.get(new NamespacedKey(plugin, name), PersistentDataType.STRING))) {
                    return true;
                }
            } catch (IllegalArgumentException otherType) {
                // A value of another type under that key is not an item id.
            }
        }
        return false;
    }

    /** Whether the stack carries a key of RsLib or SnLib in any namespace. */
    static boolean libraryTagged(ItemStack stack) {
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta == null) {
            return false;
        }
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            String name = key.getKey();
            if (name.startsWith("snlib_") || name.startsWith("rslib_")) {
                return true;
            }
        }
        return false;
    }
}
