package com.rs.lib.util;

import java.util.Locale;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Text tags stored on items (persistent data, type STRING), one namespace per plugin.
 *
 * <p>The key is {@code <plugin name in lower case>:<key in lower case>}, exactly the key
 * {@code new NamespacedKey(plugin, key)} makes, so items players already hold (vouchers, crates,
 * staff items tagged as {@code rsvouchers:voucher}) keep being recognised. A null, air or
 * meta-less item is left alone: reads answer null/false and writes return it unchanged.
 * Writes change {@code item} itself and return it. Call on the thread that owns the item.</p>
 */
public final class TagIo {

    private TagIo() {
    }

    /**
     * Stores {@code value} under {@code key}; a null value removes the tag.
     *
     * @throws IllegalArgumentException when {@code key} has characters a key cannot hold
     */
    public static ItemStack set(ItemStack item, JavaPlugin owner, String key, String value) {
        if (value == null) {
            return remove(item, owner, key);
        }
        NamespacedKey tag = key(owner, key);
        ItemMeta meta = metaOf(item);
        if (meta == null) {
            return item;
        }
        meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, value);
        item.setItemMeta(meta);
        return item;
    }

    /** The text stored under {@code key}, or null. */
    public static String get(ItemStack item, JavaPlugin owner, String key) {
        NamespacedKey tag = key(owner, key);
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
    }

    /** True when a text is stored under {@code key}. */
    public static boolean has(ItemStack item, JavaPlugin owner, String key) {
        NamespacedKey tag = key(owner, key);
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(tag, PersistentDataType.STRING);
    }

    /** Removes the tag under {@code key}, if any. */
    public static ItemStack remove(ItemStack item, JavaPlugin owner, String key) {
        NamespacedKey tag = key(owner, key);
        if (item == null || !item.hasItemMeta()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().getKeys().contains(tag)) {
            return item;
        }
        meta.getPersistentDataContainer().remove(tag);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemMeta metaOf(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.getItemMeta();
    }

    private static NamespacedKey key(JavaPlugin owner, String key) {
        return keyOf(owner.getName(), key);
    }

    /** {@code <plugin lower case>:<key lower case>}, the same key {@code new NamespacedKey(plugin, key)} gives. */
    static NamespacedKey keyOf(String pluginName, String key) {
        return new NamespacedKey(pluginName.toLowerCase(Locale.ROOT), key.toLowerCase(Locale.ROOT));
    }
}
