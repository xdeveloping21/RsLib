package com.rs.lib.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.rs.lib.Ph;
import com.rs.lib.papi.RsPapi;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.RsYml;

import net.kyori.adventure.text.Component;

/**
 * An item appearance read from YAML once and rendered many times, per viewer. The same keys
 * serve menu items and templates, {@code nav-disabled}, registered physical items and the
 * selection wand:
 *
 * <pre>
 * material: DIAMOND_SWORD        # a material, or a head texture (basehead-..., texture-..., eyJ..., skin URL); STONE
 * display-name: "&amp;bSword"        # text pipeline, no italics unless asked; "" keeps the vanilla name
 * lore: ["&amp;7Line"]               # a line holding line breaks becomes several lines
 * amount: 1                      # 1 to 99
 * glow: false
 * enchantments: [sharpness, 5]   # [id, level, id, level]; level 1 when left out
 * flags: [HIDE_ATTRIBUTES]       # item flags, HIDE_ALL (every flag), HIDE_TOOLTIP (the whole tooltip, 1.20.5+)
 * color: "235, 64, 52"           # or "FF0000" / "#FF0000"; leather armour and potions
 * trim-pattern: SENTRY           # both trim keys together; NONE or empty removes the trim
 * trim-material: GOLD
 * potion-effects: [speed, 2, 600]  # [effect, level, ticks, ...]; level 1, 200 ticks by default
 * custom-model-data: 7           # only when written; 0 is a value
 * item-model: "nexo:ruby"        # 1.21.2+; minecraft: when there is no namespace
 * skull-owner: "%player_name%"   # name or UUID, wins over a texture; never waits for the network
 * attributes: ["GENERIC_ATTACK_DAMAGE ADD_NUMBER 5 MAINHAND"]   # fixed values, no placeholders
 * damage: 10                     # only when written; kept within the durability
 * unbreakable: true              # only when written
 * max-stack-size: 16             # only when written; 1 to 99, 1.20.5+
 * equipment-slot: HEAD           # checked here, enforced by the item registry
 * </pre>
 *
 * <h2>Rendering</h2>
 * <p>Every text value may hold placeholders, resolved per render in this order: the file's own
 * placeholders, the caller's locals ({@link Ph} pairs), then PlaceholderAPI (main thread only).
 * A value with neither {@code %} nor <code>{</code> can never change, so it is parsed and
 * rendered once, here. When the material is fixed, everything fixed is built once into a base
 * stack; a render then clones it and only writes what depends on the viewer, and an appearance
 * with nothing variable is a plain clone. Rendered texts are kept per line for the last values
 * seen, so a menu refreshed with unchanged values does not parse them again. Problems are
 * reported once (file and path in the text) until a reload.</p>
 *
 * <p>Immutable once parsed and safe to share; {@link #render} belongs on the main thread.</p>
 */
public final class ItemAppearance {

    /** Resolves the placeholders of a raw text for one render. */
    @FunctionalInterface
    public interface TextResolver {

        /** The text with {@code locals} (may be null) and any other placeholders resolved for {@code viewer} (may be null). */
        String resolve(String raw, Player viewer, Function<String, String> locals);

        /** The file's own placeholders, then the locals, then PlaceholderAPI: the order of {@link RsYml}'s getters. */
        static TextResolver of(RsYml yml) {
            Objects.requireNonNull(yml, "yml");
            return yml::resolveText;
        }

        /** The locals, then PlaceholderAPI through {@code papi} (none when null). */
        static TextResolver of(RsPapi papi) {
            return (raw, viewer, locals) -> {
                String out = locals == null ? raw : RsText.applyLocals(raw, locals);
                return papi == null ? out : papi.apply(viewer, out);
            };
        }
    }

    /** Appearance keys whose value is a single text (or number, or boolean). */
    static final List<String> SCALAR_KEYS = List.of("material", "display-name", "amount", "glow", "color",
            "trim-pattern", "trim-material", "custom-model-data", "item-model", "skull-owner", "damage",
            "unbreakable", "max-stack-size", "equipment-slot");
    /** Appearance keys whose value is a list. */
    static final List<String> LIST_KEYS = List.of("lore", "enchantments", "flags", "potion-effects", "attributes");
    /** Keys whose value is text; a number or boolean there is used as text with a warning. */
    private static final List<String> TEXT_KEYS = List.of("material", "display-name", "color", "trim-pattern",
            "trim-material", "item-model", "skull-owner", "equipment-slot");

    private static final int RENDER_MEMORY = 16;

    private final String source;
    private final Consumer<String> warn;
    private final TextResolver resolver;
    private final Map<String, Value> scalars;
    private final Map<String, Values> lists;
    private final Line name;
    private final List<Line> lore;
    private final boolean loreDeclared;
    private final RsItem fixed;
    private final boolean materialFixed;
    private final boolean variable;
    private volatile ItemStack base;

    private ItemAppearance(String source, Consumer<String> warn, TextResolver resolver, Map<String, Value> scalars,
                           Map<String, Values> lists) {
        this.source = source;
        this.warn = warn;
        this.resolver = resolver;
        this.scalars = scalars;
        this.lists = lists;
        Value nameValue = scalars.get("display-name");
        this.name = nameValue == null || nameValue.raw().isEmpty() ? null : new Line(nameValue.raw(), false);
        Values loreValues = lists.get("lore");
        this.loreDeclared = loreValues != null;
        List<Line> loreLines = new ArrayList<>();
        if (loreValues != null) {
            for (String raw : loreValues.raw()) {
                loreLines.add(new Line(raw, true));
            }
        }
        this.lore = List.copyOf(loreLines);
        Value materialValue = scalars.get("material");
        this.materialFixed = materialValue == null || !materialValue.variable();
        this.fixed = RsItem.builder(Material.STONE).warnings(warn);
        boolean anyVariable = false;
        if (materialFixed) {
            applyMaterial(fixed, materialValue == null ? null : materialValue.raw());
        }
        for (Map.Entry<String, Value> entry : scalars.entrySet()) {
            String key = entry.getKey();
            if (isSpecial(key)) {
                continue;
            }
            if (entry.getValue().variable()) {
                anyVariable = true;
            } else {
                applyScalar(fixed, key, entry.getValue().raw());
            }
        }
        if (trimVaries()) {
            anyVariable = true;
        } else {
            applyTrim(fixed, null, null);
        }
        for (Map.Entry<String, Values> entry : lists.entrySet()) {
            if (entry.getKey().equals("lore")) {
                continue;
            }
            if (entry.getValue().variable()) {
                anyVariable = true;
            } else {
                applyList(fixed, entry.getKey(), entry.getValue().raw());
            }
        }
        if (name != null) {
            if (name.isFixed()) {
                fixed.nameComponent(name.fixed().get(0));
            } else {
                anyVariable = true;
            }
        }
        if (loreDeclared) {
            if (lore.stream().allMatch(Line::isFixed)) {
                List<Component> all = new ArrayList<>();
                lore.forEach(line -> all.addAll(line.fixed()));
                fixed.loreComponents(List.copyOf(all));
            } else {
                anyVariable = true;
            }
        }
        Value owner = scalars.get("skull-owner");
        if (owner != null && materialValue != null && !materialValue.variable()
                && ItemParts.material(materialValue.raw(), m -> true, w -> { }).texture() != null) {
            warn.accept("Both skull-owner and a head texture are set; skull-owner wins.");
        }
        this.variable = anyVariable || owner != null || !materialFixed;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses the appearance keys of {@code section}. Other keys (slots, actions, behaviour) are
     * ignored. Never throws for a bad value: it is reported through {@code warn} and the key
     * keeps its default.
     *
     * @param source   what to call this item in warnings, such as {@code guis/shop.yml -> items.close}
     * @param resolver how placeholders are resolved per render
     * @param warn     where problems go (once each); null logs them through RsLib's logger
     */
    public static ItemAppearance parse(ConfigurationSection section, String source, TextResolver resolver,
                                       Consumer<String> warn) {
        Objects.requireNonNull(section, "section");
        Consumer<String> sink = warn != null ? warn : ItemWarnings.to(null, source);
        TextResolver texts = resolver != null ? resolver : TextResolver.of((RsPapi) null);
        Map<String, Value> scalars = new LinkedHashMap<>();
        for (String key : SCALAR_KEYS) {
            Value value = scalar(section, key, sink);
            if (value != null) {
                scalars.put(key, value);
            }
        }
        Map<String, Values> lists = new LinkedHashMap<>();
        for (String key : LIST_KEYS) {
            Values values = list(section, key, sink);
            if (values != null) {
                lists.put(key, values);
            }
        }
        if (lists.containsKey("attributes") && lists.get("attributes").variable()) {
            // Attribute lines are fixed values: placeholders are not resolved in them.
            lists.put("attributes", new Values(lists.get("attributes").raw(), false));
        }
        return new ItemAppearance(source, sink, texts, scalars, lists);
    }

    /**
     * Parses the section at {@code path} of {@code yml} (its root when null or empty), resolving
     * placeholders like the file's getters and reporting through the owning plugin's logger.
     * Null when there is no section there. Parses on every call: keep the result.
     */
    public static ItemAppearance of(RsYml yml, String path) {
        Objects.requireNonNull(yml, "yml");
        ConfigurationSection section = path == null || path.isEmpty() ? yml.getSection("") : yml.getSection(path);
        if (section == null) {
            return null;
        }
        return parse(section, ItemWarnings.source(yml, path), TextResolver.of(yml), ItemWarnings.of(yml, path));
    }

    private static Value scalar(ConfigurationSection section, String key, Consumer<String> warn) {
        if (!section.contains(key, true)) {
            return null;
        }
        Object raw = section.get(key);
        if (raw == null) {
            return null;
        }
        if (raw instanceof ConfigurationSection || raw instanceof Map<?, ?> || raw instanceof List<?>) {
            warn.accept("'" + key + "' must be a single value, got '" + raw + "'; ignored.");
            return null;
        }
        String text = String.valueOf(raw);
        if (!(raw instanceof String) && TEXT_KEYS.contains(key)) {
            warn.accept("'" + key + "' is not text ('" + text + "'); using it as text. Put it in quotes to silence this warning.");
        }
        return new Value(text, mayVary(text));
    }

    private static Values list(ConfigurationSection section, String key, Consumer<String> warn) {
        if (!section.contains(key, true)) {
            return null;
        }
        Object raw = section.get(key);
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> elements) {
            for (int i = 0; i < elements.size(); i++) {
                Object element = elements.get(i);
                if (element instanceof List<?> || element instanceof Map<?, ?> || element instanceof ConfigurationSection) {
                    warn.accept("'" + key + "' entry " + (i + 1) + " is not text (" + element
                            + "); write it in quotes; skipped.");
                } else {
                    out.add(element == null ? "" : String.valueOf(element));
                }
            }
        } else if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) {
            out.add(String.valueOf(raw));
        } else if (raw != null) {
            warn.accept("'" + key + "' must be a list (one '- ' line per entry), got '" + raw + "'; ignored.");
            return null;
        }
        boolean variable = false;
        for (String line : out) {
            variable |= mayVary(line);
        }
        return new Values(List.copyOf(out), variable);
    }

    private static boolean mayVary(String text) {
        return text.indexOf('%') >= 0 || text.indexOf('{') >= 0;
    }

    // ------------------------------------------------------------------ reading

    /** What this item is called in warnings. */
    public String source() {
        return source;
    }

    /** True when every render gives the same item, whoever looks at it. */
    public boolean isStatic() {
        return !variable;
    }

    /** True when {@code display-name} is written and not empty. */
    public boolean declaresName() {
        return name != null;
    }

    /** True when {@code lore} is written (even as an empty list). */
    public boolean declaresLore() {
        return loreDeclared;
    }

    /** {@code equipment-slot} as written, or null. */
    public String equipmentSlot() {
        Value value = scalars.get("equipment-slot");
        return value == null || value.raw().isBlank() ? null : value.raw().trim();
    }

    /** True when {@code key} (an appearance key) is written. */
    public boolean has(String key) {
        return scalars.containsKey(key) || lists.containsKey(key);
    }

    // ------------------------------------------------------------------ rendering

    /** The item for {@code viewer} (may be null) with {@code phs} as local placeholders. */
    public ItemStack render(Player viewer, Ph... phs) {
        return render(viewer, locals(phs), null);
    }

    /** The item for {@code viewer} (may be null) with {@code locals} (may be null) as local placeholders. */
    public ItemStack render(Player viewer, Function<String, String> locals) {
        return render(viewer, locals, null);
    }

    /**
     * The item for {@code viewer}. {@code skinRefresh} (may be null) is called on the main thread
     * with the owner when a {@code skull-owner} skin that was not cached yet arrives, so the
     * caller can render this item again.
     */
    public ItemStack render(Player viewer, Function<String, String> locals, Consumer<String> skinRefresh) {
        if (!materialFixed) {
            return toBuilder(viewer, locals).skinRefresh(skinRefresh).build();
        }
        ItemStack stack = base().clone();
        if (!variable) {
            return stack;
        }
        RsItem delta = RsItem.builder(stack.getType()).warnings(warn).skinRefresh(skinRefresh);
        applyVariable(delta, viewer, locals);
        return delta.applyTo(stack);
    }

    /**
     * A builder holding this appearance resolved for {@code viewer}, for a caller that changes
     * something before building.
     */
    public RsItem toBuilder(Player viewer, Function<String, String> locals) {
        RsItem full = fixed.copy();
        if (!materialFixed) {
            applyMaterial(full, resolver.resolve(scalars.get("material").raw(), viewer, locals));
        }
        applyVariable(full, viewer, locals);
        return full;
    }

    /**
     * Paints this appearance's name and lore over a copy of {@code stack}, which keeps
     * everything else: a resolved {@code display-name} that is not empty replaces the name and
     * the {@code lore} lines are added after the lore the stack already has. When neither gives
     * anything the copy is returned untouched, its meta never rewritten. {@code stack} itself is
     * never changed.
     */
    public ItemStack overlay(ItemStack stack, Player viewer, Function<String, String> locals) {
        ItemStack out = stack.clone();
        Component shownName = null;
        if (name != null) {
            String text = name.resolve(resolver, viewer, locals);
            if (!text.isEmpty()) {
                shownName = name.rendered(text).get(0);
            }
        }
        List<Component> extra = new ArrayList<>();
        for (Line line : lore) {
            extra.addAll(line.render(resolver, viewer, locals));
        }
        if (shownName == null && extra.isEmpty()) {
            return out;
        }
        ItemMeta meta = out.getItemMeta();
        if (meta == null) {
            return out;
        }
        if (shownName != null) {
            meta.displayName(shownName);
        }
        if (!extra.isEmpty()) {
            List<Component> current = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            current.addAll(extra);
            meta.lore(current);
        }
        out.setItemMeta(meta);
        return out;
    }

    /** The fixed part built once; material, texture and every fixed key. */
    private ItemStack base() {
        ItemStack ready = base;
        if (ready == null) {
            ready = fixed.build();
            base = ready;
        }
        return ready;
    }

    private void applyVariable(RsItem target, Player viewer, Function<String, String> locals) {
        for (Map.Entry<String, Value> entry : scalars.entrySet()) {
            String key = entry.getKey();
            Value value = entry.getValue();
            if (key.equals("skull-owner")) {
                String owner = value.variable() ? resolver.resolve(value.raw(), viewer, locals) : value.raw();
                target.skullOwner(owner);
            } else if (!isSpecial(key) && value.variable()) {
                applyScalar(target, key, resolver.resolve(value.raw(), viewer, locals));
            }
        }
        if (trimVaries()) {
            applyTrim(target, viewer, locals);
        }
        for (Map.Entry<String, Values> entry : lists.entrySet()) {
            if (!entry.getKey().equals("lore") && entry.getValue().variable()) {
                List<String> resolved = new ArrayList<>(entry.getValue().raw().size());
                for (String raw : entry.getValue().raw()) {
                    resolved.add(resolver.resolve(raw, viewer, locals));
                }
                applyList(target, entry.getKey(), resolved);
            }
        }
        if (name != null && !name.isFixed()) {
            String text = name.resolve(resolver, viewer, locals);
            if (!text.isEmpty()) {
                target.nameComponent(name.rendered(text).get(0));
            }
        }
        if (loreDeclared && !lore.stream().allMatch(Line::isFixed)) {
            List<Component> all = new ArrayList<>();
            for (Line line : lore) {
                all.addAll(line.render(resolver, viewer, locals));
            }
            target.loreComponents(all);
        }
    }

    /** Keys applied by their own step, never by {@link #applyScalar}. */
    private static boolean isSpecial(String key) {
        return switch (key) {
            case "material", "display-name", "skull-owner", "trim-pattern", "trim-material" -> true;
            default -> false;
        };
    }

    private void applyMaterial(RsItem target, String raw) {
        ItemParts.MaterialSpec spec = ItemParts.material(raw, ItemParts::hasItemForm, warn);
        target.material(spec.material());
        if (spec.texture() != null) {
            target.headBase64(spec.texture());
        }
    }

    private void applyScalar(RsItem target, String key, String text) {
        String value = text == null ? "" : text.trim();
        switch (key) {
            case "amount" -> {
                Integer amount = number(key, value);
                if (amount != null) {
                    target.amount(amount);
                }
            }
            case "glow" -> {
                if (bool(key, value)) {
                    target.glow();
                }
            }
            case "color" -> target.color(value);
            case "custom-model-data" -> {
                Integer data = number(key, value);
                if (data != null) {
                    target.modelData(data);
                }
            }
            case "item-model" -> target.itemModel(value);
            case "damage" -> {
                Integer damage = number(key, value);
                if (damage != null) {
                    target.damage(damage);
                }
            }
            case "unbreakable" -> target.unbreakable(bool(key, value));
            case "max-stack-size" -> {
                Integer size = number(key, value);
                if (size != null) {
                    target.maxStackSize(size);
                }
            }
            case "equipment-slot" -> target.equipmentSlot(value);
            default -> {
                // material, display-name and skull-owner are handled by the caller.
            }
        }
    }

    /** True when the trim pair holds a placeholder in either key. */
    private boolean trimVaries() {
        Value pattern = scalars.get("trim-pattern");
        Value material = scalars.get("trim-material");
        return (pattern != null && pattern.variable()) || (material != null && material.variable());
    }

    /** The trim needs both keys; one without the other is reported and ignored. */
    private void applyTrim(RsItem target, Player viewer, Function<String, String> locals) {
        Value pattern = scalars.get("trim-pattern");
        Value material = scalars.get("trim-material");
        if (pattern == null && material == null) {
            return;
        }
        if (pattern == null || material == null) {
            Value only = pattern != null ? pattern : material;
            if (!only.raw().isBlank() && !only.raw().trim().equalsIgnoreCase("NONE")) {
                warn.accept((pattern == null ? "trim-material needs trim-pattern" : "trim-pattern needs trim-material")
                        + " too; ignored.");
            }
            return;
        }
        String p = pattern.variable() ? resolver.resolve(pattern.raw(), viewer, locals) : pattern.raw();
        String m = material.variable() ? resolver.resolve(material.raw(), viewer, locals) : material.raw();
        target.trim(p, m);
    }

    private void applyList(RsItem target, String key, List<String> values) {
        switch (key) {
            case "enchantments" -> target.enchantments(ItemParts.enchantments(values, warn));
            case "flags" -> target.flags(values);
            case "potion-effects" -> target.effects(ItemParts.effects(values, warn));
            case "attributes" -> {
                List<ItemParts.AttributeLine> parsed = new ArrayList<>();
                for (String line : values) {
                    ItemParts.AttributeLine attribute = ItemParts.attribute(line, warn);
                    if (attribute != null) {
                        parsed.add(attribute);
                    }
                }
                target.attributes(parsed);
            }
            default -> {
                // lore is handled by the caller.
            }
        }
    }

    private Integer number(String key, String value) {
        Integer parsed = ItemParts.intOrNull(value);
        if (parsed == null) {
            warn.accept("'" + key + "' must be a whole number, got '" + value + "'; ignored.");
        }
        return parsed;
    }

    private boolean bool(String key, String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (!value.equalsIgnoreCase("false")) {
            warn.accept("'" + key + "' must be true or false, got '" + value + "'; using false.");
        }
        return false;
    }

    /** A resolver over {@code phs}: the last pair with a key wins; literal values are kept as written. */
    public static Function<String, String> locals(Ph... phs) {
        if (phs == null || phs.length == 0) {
            return null;
        }
        Map<String, String> map = new HashMap<>(phs.length * 2);
        for (Ph ph : phs) {
            if (ph != null) {
                map.put(ph.key(), ph.literal() ? RsText.literal(ph.value()) : ph.value());
            }
        }
        return map::get;
    }

    // ------------------------------------------------------------------ values

    /** A scalar as written; {@code variable} when it may hold a placeholder. */
    private record Value(String raw, boolean variable) {
    }

    /** A list as written; {@code variable} when any entry may hold a placeholder. */
    private record Values(List<String> raw, boolean variable) {
    }

    /**
     * One name or lore line. A fixed line is rendered once; a variable one is rendered per
     * resolved text and the last {@value #RENDER_MEMORY} results are kept.
     */
    private static final class Line {
        private final String raw;
        private final boolean split;
        private final List<Component> fixed;
        private final Map<String, List<Component>> recent;

        Line(String raw, boolean split) {
            this.raw = raw;
            this.split = split;
            if (mayVary(raw)) {
                this.fixed = null;
                this.recent = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, List<Component>> eldest) {
                        return size() > RENDER_MEMORY;
                    }
                });
            } else {
                this.fixed = renderText(raw, split, false);
                this.recent = null;
            }
        }

        boolean isFixed() {
            return fixed != null;
        }

        List<Component> fixed() {
            return fixed;
        }

        /** The text after placeholders, never null. */
        String resolve(TextResolver resolver, Player viewer, Function<String, String> locals) {
            if (fixed != null) {
                return raw;
            }
            String text = resolver.resolve(raw, viewer, locals);
            return text == null ? "" : text;
        }

        /** The components for a text {@link #resolve} returned. */
        List<Component> rendered(String text) {
            if (fixed != null) {
                return fixed;
            }
            List<Component> known = recent.get(text);
            if (known == null) {
                known = renderText(text, split, true);
                recent.put(text, known);
            }
            return known;
        }

        List<Component> render(TextResolver resolver, Player viewer, Function<String, String> locals) {
            return fixed != null ? fixed : rendered(resolve(resolver, viewer, locals));
        }

        private static List<Component> renderText(String text, boolean split, boolean perViewer) {
            if (!split) {
                return List.of(RsItem.plain(perViewer ? RsText.colorUncached(text) : RsText.color(text)));
            }
            List<String> parts = ItemParts.lines(text);
            List<Component> out = new ArrayList<>(parts.size());
            for (String part : parts) {
                out.add(RsItem.plain(perViewer ? RsText.colorUncached(part) : RsText.color(part)));
            }
            return List.copyOf(out);
        }
    }
}
