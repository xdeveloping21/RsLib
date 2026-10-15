package com.rs.lib.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import com.rs.lib.item.ItemSerializer;

/**
 * How items and potion effects become YAML values and back. The server form is the only one
 * outside tests; tests swap it because Paper's item bytes and effect types need a server.
 */
interface SnapshotCodec {

    /** Bukkit's type tag for a serialized potion effect. */
    String TYPE_KEY = "==";

    /** The text of a non-empty item; throws when it cannot be written. */
    String encodeItem(ItemStack item);

    /** The item in {@code text}; throws {@link IllegalArgumentException} (or any runtime error) when unreadable. */
    ItemStack decodeItem(String text);

    /** The map Bukkit writes for {@code effect}. */
    Map<String, Object> encodeEffect(PotionEffect effect);

    /** The effect in {@code map}; throws when its type is unknown or the map is malformed. */
    PotionEffect decodeEffect(Map<String, Object> map);

    /**
     * Items as {@link ItemSerializer} Base64 (the real amount in 4 bytes, then Paper's bytes of
     * the stack with amount 1); effects as Bukkit's own map with its {@code ==: PotionEffect} tag.
     */
    SnapshotCodec SERVER = new SnapshotCodec() {

        @Override
        public String encodeItem(ItemStack item) {
            return ItemSerializer.serializeBase64(item);
        }

        @Override
        public ItemStack decodeItem(String text) {
            ItemStack item = ItemSerializer.deserializeBase64(text);
            if (PlayerSnapshot.empty(item)) {
                throw new IllegalArgumentException("the item is empty");
            }
            return item;
        }

        @Override
        public Map<String, Object> encodeEffect(PotionEffect effect) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(TYPE_KEY, "PotionEffect");
            map.putAll(effect.serialize());
            return map;
        }

        @Override
        public PotionEffect decodeEffect(Map<String, Object> map) {
            Map<String, Object> fields = new LinkedHashMap<>(map);
            fields.remove(TYPE_KEY);
            return new PotionEffect(fields);
        }
    };
}
