package com.rs.lib.item;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.rs.lib.Ph;
import com.rs.lib.text.RsText;
import com.rs.lib.util.HeadUtil;
import com.rs.lib.yml.RsYml;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Builds an {@link ItemStack} from the appearance keys every Rs config uses (see
 * {@link ItemAppearance} for the YAML form).
 *
 * <p>Texts go through the full {@link RsText} pipeline and come out without italics unless
 * the text asks for them; a lore line holding line breaks becomes several lines. Values the
 * server cannot use (an unknown enchantment, a key newer than this Minecraft version) are
 * skipped with a warning printed once until the next reload ({@link #forgetWarnings()}).
 * Setters left unused change nothing on the item: {@code modelData(0)} sets the data to 0,
 * not calling it leaves it unset.</p>
 *
 * <p>A builder is not thread-safe; {@link #build()} belongs on the main thread. {@code build()}
 * does not change the builder, so one builder can build many items.</p>
 */
public final class RsItem {

    private static final Consumer<String> DEFAULT_WARN = ItemWarnings.to(null, "RsItem");

    private Material material;
    private String texture;
    private String rawName;
    private Component name;
    private List<String> rawLore;
    private List<Component> lore;
    private Integer amount;
    private boolean glow;
    private final List<ItemParts.Leveled> enchants = new ArrayList<>();
    private final List<String> flags = new ArrayList<>();
    private int color = -1;
    private String trimPattern;
    private String trimMaterial;
    private final List<ItemParts.Effect> effects = new ArrayList<>();
    private Integer modelData;
    private NamespacedKey itemModel;
    private String skullOwner;
    private Consumer<String> skinRefresh;
    private final List<ItemParts.AttributeLine> attributes = new ArrayList<>();
    private Integer damage;
    private Boolean unbreakable;
    private Integer maxStackSize;
    private String equipmentSlot;
    private Consumer<String> warn = DEFAULT_WARN;

    private RsItem(Material material) {
        this.material = material == null ? Material.STONE : material;
    }

    /** A builder for {@code material} (STONE when null). */
    public static RsItem builder(Material material) {
        return new RsItem(material);
    }

    /**
     * The appearance written at the root of {@code yml}, resolved for {@code viewer} with
     * {@code phs}: the file's own placeholders, then {@code phs}, then PlaceholderAPI.
     */
    public static RsItem fromConfig(RsYml yml, Player viewer, Ph... phs) {
        return fromConfig(yml, null, viewer, phs);
    }

    /**
     * The appearance written in the section at {@code path} of {@code yml} (the root when null),
     * resolved like {@link #fromConfig(RsYml, Player, Ph...)}. The section is parsed once and
     * kept until the file is reloaded. A missing section gives a STONE builder and a warning.
     */
    public static RsItem fromConfig(RsYml yml, String path, Player viewer, Ph... phs) {
        Objects.requireNonNull(yml, "yml");
        ItemAppearance appearance = ItemAppearance.of(yml, path);
        if (appearance == null) {
            ItemWarnings.of(yml, path).accept("There is no item section here; using STONE.");
            return builder(Material.STONE);
        }
        return appearance.toBuilder(viewer, ItemAppearance.locals(phs));
    }

    // ------------------------------------------------------------------ setters

    /** The name, through the text pipeline; null or empty keeps the vanilla name. */
    public RsItem name(String text) {
        this.rawName = text == null || text.isEmpty() ? null : text;
        this.name = null;
        return this;
    }

    /** The lore; each line through the text pipeline, line breaks inside a line split it. */
    public RsItem lore(List<String> lines) {
        this.rawLore = lines == null ? null : new ArrayList<>(lines);
        this.lore = null;
        return this;
    }

    public RsItem lore(String... lines) {
        return lore(lines == null ? null : Arrays.asList(lines));
    }

    /** The stack size, kept between 1 and 99. */
    public RsItem amount(int amount) {
        this.amount = Math.max(1, Math.min(99, amount));
        return this;
    }

    /** The enchantment glint without an enchantment (a hidden one on 1.20.4 and older). */
    public RsItem glow() {
        this.glow = true;
        return this;
    }

    /** Adds an enchantment by key ({@code sharpness}, {@code minecraft:sharpness}) or old name ({@code DAMAGE_ALL}), any level. */
    public RsItem enchant(String id, int level) {
        if (id != null && !id.isBlank()) {
            enchants.add(new ItemParts.Leveled(id.trim(), level));
        }
        return this;
    }

    /** Adds item flags by name; {@code HIDE_ALL} is every flag, {@code HIDE_TOOLTIP} hides the whole tooltip (1.20.5+). */
    public RsItem flags(List<String> names) {
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) {
                    flags.add(name.trim());
                }
            }
        }
        return this;
    }

    /** Every item flag ({@code HIDE_ALL}); the whole tooltip stays visible. */
    public RsItem hideAllTooltipFlags() {
        flags.add(ItemParts.HIDE_ALL);
        return this;
    }

    /** {@code "R, G, B"}, {@code "RRGGBB"} or {@code "#RRGGBB"}, for leather armour and potions. */
    public RsItem color(String text) {
        if (text == null || text.isBlank()) {
            this.color = -1;
            return this;
        }
        int rgb = ItemParts.rgb(text);
        if (rgb < 0) {
            warn.accept("Invalid color '" + text + "' (use \"R, G, B\", \"RRGGBB\" or \"#RRGGBB\"); ignored.");
        }
        this.color = rgb;
        return this;
    }

    /** An armour trim; both parts are needed, {@code NONE} or empty in either removes it. */
    public RsItem trim(String pattern, String material) {
        boolean none = pattern == null || material == null || pattern.isBlank() || material.isBlank()
                || pattern.trim().equalsIgnoreCase("NONE") || material.trim().equalsIgnoreCase("NONE");
        this.trimPattern = none ? null : pattern.trim();
        this.trimMaterial = none ? null : material.trim();
        return this;
    }

    /** Adds potion effects written as {@code [effect, level, ticks, ...]} (level 1 and 200 ticks by default). */
    public RsItem potionEffects(List<String> entries) {
        effects.addAll(ItemParts.effects(entries, warn));
        return this;
    }

    /** Custom model data; 0 is a value too. */
    public RsItem modelData(int data) {
        this.modelData = data;
        return this;
    }

    /** The item model key (1.21.2+); without a namespace it is {@code minecraft:}. Empty removes it. */
    public RsItem itemModel(String key) {
        if (key == null || key.isBlank()) {
            this.itemModel = null;
            return this;
        }
        NamespacedKey parsed = ItemCompat.key(key);
        if (parsed == null) {
            warn.accept("Invalid item-model '" + key + "' (expected namespace:path); ignored.");
        }
        this.itemModel = parsed;
        return this;
    }

    /** A head texture: Base64 value, skin URL, or either behind {@code basehead-}, {@code texture-}, {@code base64-}. */
    public RsItem headBase64(String value) {
        this.texture = value == null || value.isBlank() ? null : value.trim();
        return this;
    }

    /** A player name or UUID whose skin the head shows; it wins over a texture. Never waits for the network. */
    public RsItem skullOwner(String owner) {
        this.skullOwner = owner == null || owner.isBlank() ? null : owner.trim();
        return this;
    }

    /** Called on the main thread with the owner when a {@link #skullOwner} skin that was not cached arrives. */
    public RsItem skinRefresh(Consumer<String> callback) {
        this.skinRefresh = callback;
        return this;
    }

    /** {@link #attribute(String, String, double, String)} for any slot. */
    public RsItem attribute(String id, String operation, double amount) {
        return attribute(id, operation, amount, null);
    }

    /**
     * Adds an attribute modifier: {@code GENERIC_ATTACK_DAMAGE} or {@code attack_damage} (any
     * spelling of any version), {@code ADD_NUMBER}/{@code ADD_SCALAR}/{@code MULTIPLY_SCALAR_1},
     * slot group {@code ANY} (null), {@code HAND}, {@code ARMOR}, {@code MAINHAND},
     * {@code OFFHAND}, {@code HEAD}, {@code CHEST}, {@code LEGS} or {@code FEET}.
     */
    public RsItem attribute(String id, String operation, double amount, String slotGroup) {
        String line = id + " " + operation + " " + amount + (slotGroup == null || slotGroup.isBlank() ? "" : " " + slotGroup);
        ItemParts.AttributeLine parsed = ItemParts.attribute(line, warn);
        if (parsed != null) {
            attributes.add(parsed);
        }
        return this;
    }

    /**
     * Vanilla damage taken, kept between 0 and the material's durability; a material without
     * durability is reported and keeps no damage.
     */
    public RsItem damage(int damage) {
        this.damage = Math.max(0, damage);
        return this;
    }

    public RsItem unbreakable(boolean unbreakable) {
        this.unbreakable = unbreakable;
        return this;
    }

    /** The stack size this item allows, 1 to 99 (1.20.5+). */
    public RsItem maxStackSize(int size) {
        if (size < 1 || size > 99) {
            warn.accept("max-stack-size must be between 1 and 99, got " + size + "; using "
                    + Math.max(1, Math.min(99, size)) + ".");
        }
        this.maxStackSize = Math.max(1, Math.min(99, size));
        return this;
    }

    /** The equipment slot of a registered item (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET); checked here, enforced by the item registry. */
    public RsItem equipmentSlot(String slot) {
        if (slot == null || slot.isBlank()) {
            this.equipmentSlot = null;
            return this;
        }
        if (ItemParts.equipmentSlot(slot) == null) {
            warn.accept("Unknown equipment-slot '" + slot + "' (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET); ignored.");
            this.equipmentSlot = null;
        } else {
            this.equipmentSlot = slot.trim();
        }
        return this;
    }

    // ------------------------------------------------------------------ package setters

    RsItem material(Material value) {
        this.material = value == null ? Material.STONE : value;
        return this;
    }

    RsItem nameComponent(Component value) {
        this.name = value;
        this.rawName = null;
        return this;
    }

    RsItem loreComponents(List<Component> value) {
        this.lore = value;
        this.rawLore = null;
        return this;
    }

    RsItem enchantments(List<ItemParts.Leveled> list) {
        enchants.addAll(list);
        return this;
    }

    RsItem effects(List<ItemParts.Effect> list) {
        effects.addAll(list);
        return this;
    }

    RsItem attributes(List<ItemParts.AttributeLine> list) {
        attributes.addAll(list);
        return this;
    }

    RsItem rgb(int rgb) {
        this.color = rgb;
        return this;
    }

    /** Where warnings go; their text names the file and path of the item. */
    RsItem warnings(Consumer<String> sink) {
        this.warn = sink == null ? DEFAULT_WARN : sink;
        return this;
    }

    /** An independent copy of every setting. */
    RsItem copy() {
        RsItem c = new RsItem(material);
        c.texture = texture;
        c.rawName = rawName;
        c.name = name;
        c.rawLore = rawLore == null ? null : new ArrayList<>(rawLore);
        c.lore = lore;
        c.amount = amount;
        c.glow = glow;
        c.enchants.addAll(enchants);
        c.flags.addAll(flags);
        c.color = color;
        c.trimPattern = trimPattern;
        c.trimMaterial = trimMaterial;
        c.effects.addAll(effects);
        c.modelData = modelData;
        c.itemModel = itemModel;
        c.skullOwner = skullOwner;
        c.skinRefresh = skinRefresh;
        c.attributes.addAll(attributes);
        c.damage = damage;
        c.unbreakable = unbreakable;
        c.maxStackSize = maxStackSize;
        c.equipmentSlot = equipmentSlot;
        c.warn = warn;
        return c;
    }

    // ------------------------------------------------------------------ readers

    Material material() {
        return material;
    }

    String equipmentSlotName() {
        return equipmentSlot;
    }

    Integer modelDataValue() {
        return modelData;
    }

    String skullOwnerValue() {
        return skullOwner;
    }

    String textureValue() {
        return texture;
    }

    Integer amountValue() {
        return amount;
    }

    Component nameValue() {
        return name;
    }

    List<Component> loreValue() {
        return lore;
    }

    // ------------------------------------------------------------------ building

    /** A new stack with everything set on this builder. */
    public ItemStack build() {
        return applyTo(new ItemStack(material));
    }

    /**
     * Writes everything set on this builder onto {@code stack} (its material is kept) and
     * returns it. Settings left unset do not touch the stack.
     */
    ItemStack applyTo(ItemStack stack) {
        if (amount != null) {
            stack.setAmount(amount);
        }
        if (stack.getType().isAir()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        applyMeta(meta, stack.getType());
        stack.setItemMeta(meta);
        return stack;
    }

    private void applyMeta(ItemMeta meta, Material type) {
        Component shownName = name != null ? name : rawName == null ? null : RsText.color(rawName);
        if (shownName != null) {
            meta.displayName(plain(shownName));
        }
        List<Component> shownLore = lore != null ? lore : rawLore == null ? null : renderLore(rawLore);
        if (shownLore != null) {
            meta.lore(shownLore);
        }
        applyEnchantments(meta);
        if (glow) {
            ItemCompat.glow(meta, type, warn);
        }
        applyFlags(meta);
        applyColor(meta);
        applyTrim(meta);
        applyEffects(meta);
        if (modelData != null) {
            ItemCompat.modelData(meta, modelData);
        }
        if (itemModel != null) {
            ItemCompat.itemModel(meta, itemModel, warn);
        }
        applyHead(meta);
        applyAttributes(meta);
        applyDamage(meta, type);
        if (unbreakable != null) {
            meta.setUnbreakable(unbreakable);
        }
        if (maxStackSize != null) {
            ItemCompat.maxStackSize(meta, maxStackSize, warn);
        }
    }

    /** No italics unless the text set them. */
    static Component plain(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static List<Component> renderLore(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            for (String part : ItemParts.lines(line)) {
                out.add(plain(RsText.color(part)));
            }
        }
        return out;
    }

    private void applyEnchantments(ItemMeta meta) {
        for (ItemParts.Leveled entry : enchants) {
            Enchantment enchantment = ItemCompat.enchantment(entry.id());
            if (enchantment == null) {
                warn.accept("Unknown enchantment '" + entry.id() + "'; skipped.");
            } else if (meta instanceof EnchantmentStorageMeta book) {
                book.addStoredEnchant(enchantment, entry.level(), true);
            } else {
                meta.addEnchant(enchantment, entry.level(), true);
            }
        }
    }

    private void applyFlags(ItemMeta meta) {
        for (String written : flags) {
            String name = ItemParts.constant(written);
            if (name.equals(ItemParts.HIDE_ALL)) {
                meta.addItemFlags(ItemFlag.values());
            } else if (name.equals(ItemParts.HIDE_TOOLTIP)) {
                ItemCompat.hideTooltip(meta, warn);
            } else {
                ItemFlag flag = ItemCompat.flag(written);
                if (flag == null) {
                    warn.accept("Unknown item flag '" + written + "'; skipped.");
                } else {
                    meta.addItemFlags(flag);
                }
            }
        }
    }

    private void applyColor(ItemMeta meta) {
        if (color < 0) {
            return;
        }
        if (meta instanceof LeatherArmorMeta leather) {
            leather.setColor(Color.fromRGB(color));
        } else if (meta instanceof PotionMeta potion) {
            potion.setColor(Color.fromRGB(color));
        } else {
            warn.accept("color only applies to leather armour and potions; ignored.");
        }
    }

    private void applyTrim(ItemMeta meta) {
        if (trimPattern == null) {
            return;
        }
        if (!(meta instanceof ArmorMeta armor)) {
            warn.accept("trim-pattern and trim-material only apply to armour; ignored.");
            return;
        }
        TrimPattern pattern = ItemCompat.trimPattern(trimPattern);
        TrimMaterial trimWith = ItemCompat.trimMaterial(trimMaterial);
        if (pattern == null || trimWith == null) {
            warn.accept("Unknown armour trim '" + trimPattern + "' / '" + trimMaterial + "'; ignored.");
            return;
        }
        armor.setTrim(new ArmorTrim(trimWith, pattern));
    }

    private void applyEffects(ItemMeta meta) {
        if (effects.isEmpty()) {
            return;
        }
        if (!(meta instanceof PotionMeta potion)) {
            warn.accept("potion-effects only apply to potions and tipped arrows; ignored.");
            return;
        }
        for (ItemParts.Effect effect : effects) {
            PotionEffectType type = ItemCompat.effect(effect.id());
            if (type == null) {
                warn.accept("Unknown potion effect '" + effect.id() + "'; skipped.");
                continue;
            }
            potion.addCustomEffect(new PotionEffect(type, Math.max(1, effect.ticks()), Math.max(0, effect.level() - 1)), true);
        }
    }

    private void applyHead(ItemMeta meta) {
        if (skullOwner == null && texture == null) {
            return;
        }
        if (!(meta instanceof SkullMeta skull)) {
            warn.accept((skullOwner != null ? "skull-owner" : "A head texture") + " needs material PLAYER_HEAD; ignored.");
            return;
        }
        if (skullOwner != null) {
            if (texture != null) {
                warn.accept(ItemParts.SKULL_AND_TEXTURE);
            }
            SkinCache.apply(skull, skullOwner, skinRefresh, warn);
        } else {
            HeadUtil.applyBase64(skull, texture);
        }
    }

    private void applyAttributes(ItemMeta meta) {
        for (int i = 0; i < attributes.size(); i++) {
            ItemParts.AttributeLine line = attributes.get(i);
            Attribute attribute = ItemCompat.attribute(line.attribute());
            if (attribute == null) {
                warn.accept("Unknown attribute '" + line.attribute() + "'; skipped.");
                continue;
            }
            try {
                meta.addAttributeModifier(attribute, ItemCompat.modifier(i, line));
            } catch (IllegalArgumentException duplicate) {
                warn.accept("Attribute line '" + line.attribute() + "' could not be added: " + duplicate.getMessage());
            }
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private void applyDamage(ItemMeta meta, Material type) {
        if (damage == null) {
            return;
        }
        Integer applied = ItemParts.damage(damage, type.getMaxDurability(), type.name(), warn);
        if (applied != null && meta instanceof Damageable damageable) {
            damageable.setDamage(applied);
        }
    }

    // ------------------------------------------------------------------ statics

    /** {@code MAINHAND}, {@code OFFHAND}, {@code HEAD}, {@code CHEST}, {@code LEGS} or {@code FEET} (any case); null otherwise. */
    public static EquipmentSlot parseEquipmentSlot(String name) {
        return ItemParts.equipmentSlot(name);
    }

    /** Lets every item warning be printed again (RsLib calls it on reload). */
    public static void forgetWarnings() {
        ItemWarnings.forget();
    }
}
