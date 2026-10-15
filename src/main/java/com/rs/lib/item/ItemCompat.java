package com.rs.lib.item;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffectType;

import com.rs.lib.compat.RsVersion;

/**
 * Item API that changed between Paper 1.20.1 and 1.21.11, behind feature checks.
 *
 * <ul>
 *     <li>Registry lookups go through {@link Registry} with the older static lookups as a
 *     fallback, and never call a method on {@link Attribute} through its own type (an enum up to
 *     1.21.2, an interface after, which would fail to link).</li>
 *     <li>Glint override, hide-tooltip and max stack size need 1.20.5; item model needs 1.21.2;
 *     attribute modifiers take a key from 1.21 and a slot group from 1.20.5. A key a server
 *     cannot use is skipped with one warning.</li>
 * </ul>
 * Lookups are cached; registries do not change while the server runs.
 */
final class ItemCompat {

    static final boolean GLINT = RsVersion.supports(20, 5);
    static final boolean HIDE_TOOLTIP = RsVersion.supports(20, 5);
    static final boolean MAX_STACK = RsVersion.supports(20, 5);
    static final boolean ITEM_MODEL = RsVersion.supports(21, 2);
    static final boolean SLOT_GROUPS = RsVersion.supports(20, 5);
    static final boolean MODIFIER_KEYS = RsVersion.supports(21, 0);

    /** Namespace of the attribute modifier keys, the one items already in players' hands carry. */
    static final String MODIFIER_NAMESPACE = "snlib";

    private static final int MAX_CACHED = 1024;
    private static final Map<String, Optional<Enchantment>> ENCHANTMENTS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<PotionEffectType>> EFFECTS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Attribute>> ATTRIBUTES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<ItemFlag>> FLAGS = new ConcurrentHashMap<>();

    private ItemCompat() {
    }

    // ------------------------------------------------------------------ registries

    static Enchantment enchantment(String written) {
        return cached(ENCHANTMENTS, written, w -> first(ItemParts.enchantmentKeys(w), ItemCompat::enchantmentByKey));
    }

    static PotionEffectType effect(String written) {
        return cached(EFFECTS, written, w -> first(ItemParts.effectKeys(w), ItemCompat::effectByKey));
    }

    static Attribute attribute(String written) {
        return cached(ATTRIBUTES, written, w -> first(ItemParts.attributeKeys(w), ItemCompat::attributeByKey));
    }

    static ItemFlag flag(String written) {
        return cached(FLAGS, written, w -> {
            for (String name : ItemParts.flagNames(w)) {
                for (ItemFlag flag : ItemFlag.values()) {
                    if (flag.name().equals(name)) {
                        return flag;
                    }
                }
            }
            return null;
        });
    }

    @SuppressWarnings({"deprecation", "removal"})
    static TrimPattern trimPattern(String written) {
        NamespacedKey key = key(written);
        return key == null ? null : lookup(Registry.TRIM_PATTERN, key);
    }

    @SuppressWarnings({"deprecation", "removal"})
    static TrimMaterial trimMaterial(String written) {
        NamespacedKey key = key(written);
        return key == null ? null : lookup(Registry.TRIM_MATERIAL, key);
    }

    /** Lower case, spaces as {@code _}, {@code minecraft:} when there is no namespace; null when malformed. */
    static NamespacedKey key(String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        try {
            return NamespacedKey.fromString(written.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private static <T> T cached(Map<String, Optional<T>> cache, String written, Function<String, T> find) {
        if (written == null || written.isBlank()) {
            return null;
        }
        Optional<T> known = cache.get(written);
        if (known == null) {
            known = Optional.ofNullable(find.apply(written));
            if (cache.size() >= MAX_CACHED) {
                cache.clear();
            }
            cache.put(written, known);
        }
        return known.orElse(null);
    }

    private static <T> T first(List<String> keys, Function<NamespacedKey, T> byKey) {
        for (String text : keys) {
            NamespacedKey key = key(text);
            if (key != null) {
                T found = byKey.apply(key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends Keyed> T lookup(Registry<T> registry, NamespacedKey key) {
        try {
            return registry == null ? null : registry.get(key);
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Enchantment enchantmentByKey(NamespacedKey key) {
        try {
            Enchantment found = lookup(Registry.ENCHANTMENT, key);
            if (found != null) {
                return found;
            }
        } catch (LinkageError noRegistry) {
            // Older servers: the static lookup below.
        }
        try {
            return Enchantment.getByKey(key);
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static PotionEffectType effectByKey(NamespacedKey key) {
        try {
            PotionEffectType found = lookup(Registry.EFFECT, key);
            if (found != null) {
                return found;
            }
        } catch (LinkageError noRegistry) {
            // Registry.EFFECT is newer than 1.20.1: the static lookup below.
        }
        try {
            return PotionEffectType.getByKey(key);
        } catch (RuntimeException | LinkageError absent) {
            return null;
        }
    }

    private static Attribute attributeByKey(NamespacedKey key) {
        try {
            return lookup(Registry.ATTRIBUTE, key);
        } catch (LinkageError noRegistry) {
            return null;
        }
    }

    // ------------------------------------------------------------------ meta

    /** The enchantment glint without an enchantment; on 1.20.4 and older a hidden enchantment. */
    static void glow(ItemMeta meta, Material material) {
        if (GLINT) {
            meta.setEnchantmentGlintOverride(true);
            return;
        }
        if (meta.hasEnchants()) {
            return;
        }
        Enchantment hidden = enchantment(material == Material.BOW || material == Material.CROSSBOW ? "lure" : "infinity");
        if (hidden != null) {
            meta.addEnchant(hidden, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
    }

    static void hideTooltip(ItemMeta meta, Consumer<String> warn) {
        if (HIDE_TOOLTIP) {
            meta.setHideTooltip(true);
        } else {
            unsupported(warn, "HIDE_TOOLTIP", "1.20.5");
        }
    }

    static void maxStackSize(ItemMeta meta, int size, Consumer<String> warn) {
        if (MAX_STACK) {
            meta.setMaxStackSize(size);
        } else {
            unsupported(warn, "max-stack-size", "1.20.5");
        }
    }

    static void itemModel(ItemMeta meta, NamespacedKey model, Consumer<String> warn) {
        if (ITEM_MODEL) {
            meta.setItemModel(model);
        } else {
            unsupported(warn, "item-model", "1.21.2");
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    static void modelData(ItemMeta meta, int data) {
        meta.setCustomModelData(data);
    }

    private static void unsupported(Consumer<String> warn, String what, String since) {
        warn.accept(what + " needs Minecraft " + since + " or newer; this server runs " + RsVersion.describe()
                + ", so it is ignored.");
    }

    // ------------------------------------------------------------------ attributes

    /**
     * The modifier for line {@code index}. From 1.21 its key is
     * {@code snlib:attr_<index>_<attribute>}; before, a UUID derived from that same text. The
     * same line always gives the same modifier, so items built twice stack.
     */
    @SuppressWarnings({"deprecation", "removal"})
    static AttributeModifier modifier(int index, ItemParts.AttributeLine line) {
        String name = "attr_" + index + "_" + keyPart(ItemParts.attributeBase(line.attribute()));
        AttributeModifier.Operation operation = AttributeModifier.Operation.valueOf(line.operation());
        if (MODIFIER_KEYS) {
            return new AttributeModifier(new NamespacedKey(MODIFIER_NAMESPACE, name), line.amount(), operation,
                    slotGroup(line.group()));
        }
        UUID id = UUID.nameUUIDFromBytes((MODIFIER_NAMESPACE + ":" + name).getBytes(StandardCharsets.UTF_8));
        if (SLOT_GROUPS) {
            return new AttributeModifier(id, name, line.amount(), operation, slotGroup(line.group()));
        }
        return new AttributeModifier(id, name, line.amount(), operation, legacySlot(line.group()));
    }

    /** {@code text} with every character a key cannot hold replaced by {@code _}. */
    static String keyPart(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-';
            out.append(allowed ? c : '_');
        }
        return out.toString();
    }

    private static EquipmentSlotGroup slotGroup(String group) {
        EquipmentSlotGroup found = EquipmentSlotGroup.getByName(group.toLowerCase(Locale.ROOT));
        return found == null ? EquipmentSlotGroup.ANY : found;
    }

    /** Before 1.20.5 a modifier names one slot or none (any); groups of several slots mean any. */
    static EquipmentSlot legacySlot(String group) {
        return switch (group) {
            case "HAND", "MAINHAND" -> EquipmentSlot.HAND;
            case "OFFHAND" -> EquipmentSlot.OFF_HAND;
            case "HEAD" -> EquipmentSlot.HEAD;
            case "CHEST" -> EquipmentSlot.CHEST;
            case "LEGS" -> EquipmentSlot.LEGS;
            case "FEET" -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
