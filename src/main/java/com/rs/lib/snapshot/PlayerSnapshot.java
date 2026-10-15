package com.rs.lib.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import com.rs.lib.util.LocationSerializer;

/**
 * The state of one player at one moment: some of inventory, ender chest, health, experience,
 * effects, game mode and location ({@link SnapshotPart}). Immutable; the items are copies.
 *
 * <p>{@link #capture}, {@link #restoreTo} and {@link #clear} touch the player and must run on
 * the player's thread (the main thread on Paper, the player's region on Folia).
 * {@link Snapshots} does that for you and keeps the snapshots on disk.</p>
 *
 * <p>The text form ({@link #toYaml()}) is {@code format: 1}; items are {@code ItemSerializer}
 * Base64 (the real amount in front, so stacks above the normal size survive) under their slot
 * number, and only non-empty slots are written.</p>
 */
public final class PlayerSnapshot {

    static final int FORMAT = 1;
    /** The largest inventory a file may describe. */
    static final int MAX_SIZE = 256;
    static final int FULL_FOOD = 20;
    static final float CLEAR_SATURATION = 5f;
    private static final int HOTBAR = 9;

    private final long takenAt;
    private final Set<SnapshotPart> parts;
    private final ItemStack[] inventory;
    private final ItemStack cursor;
    private final int heldSlot;
    private final ItemStack[] enderChest;
    private final Vitals vitals;
    private final Xp experience;
    private final List<PotionEffect> effects;
    private final Mode mode;
    private final String location;
    private final int skippedEffects;

    /** Health and hunger. */
    record Vitals(double health, int food, float saturation, float exhaustion, int air, int fireTicks) {
    }

    /** Level, bar progress (0 to 1) and total points. */
    record Xp(int level, float exp, int total) {
    }

    record Mode(GameMode gameMode, boolean allowFlight, boolean flying) {
    }

    PlayerSnapshot(long takenAt, Set<SnapshotPart> parts, ItemStack[] inventory, ItemStack cursor, int heldSlot,
            ItemStack[] enderChest, Vitals vitals, Xp experience, List<PotionEffect> effects, Mode mode,
            String location, int skippedEffects) {
        this.takenAt = takenAt;
        this.parts = SnapshotPart.copyOf(parts);
        this.inventory = inventory;
        this.cursor = cursor;
        this.heldSlot = heldSlot;
        this.enderChest = enderChest;
        this.vitals = vitals;
        this.experience = experience;
        this.effects = effects == null ? null : List.copyOf(effects);
        this.mode = mode;
        this.location = location;
        this.skippedEffects = skippedEffects;
    }

    // ------------------------------------------------------------------ capture

    /** The {@link SnapshotPart#DEFAULT} parts of {@code player}, now. */
    public static PlayerSnapshot capture(Player player) {
        return capture(player, SnapshotPart.DEFAULT);
    }

    /** The given parts of {@code player}, now. Call on the player's thread. */
    public static PlayerSnapshot capture(Player player, Set<SnapshotPart> parts) {
        Set<SnapshotPart> wanted = SnapshotPart.copyOf(parts);
        ItemStack[] inventory = null;
        ItemStack cursor = null;
        int heldSlot = 0;
        if (wanted.contains(SnapshotPart.INVENTORY)) {
            PlayerInventory inv = player.getInventory();
            inventory = copies(inv.getContents());
            cursor = copy(player.getItemOnCursor());
            heldSlot = inv.getHeldItemSlot();
        }
        ItemStack[] ender = wanted.contains(SnapshotPart.ENDER_CHEST) ? copies(player.getEnderChest().getContents()) : null;
        Vitals vitals = wanted.contains(SnapshotPart.HEALTH)
                ? new Vitals(player.getHealth(), player.getFoodLevel(), player.getSaturation(), player.getExhaustion(),
                        player.getRemainingAir(), player.getFireTicks())
                : null;
        Xp xp = wanted.contains(SnapshotPart.EXPERIENCE)
                ? new Xp(player.getLevel(), player.getExp(), player.getTotalExperience())
                : null;
        List<PotionEffect> effects = wanted.contains(SnapshotPart.EFFECTS)
                ? new ArrayList<>(player.getActivePotionEffects())
                : null;
        Mode mode = wanted.contains(SnapshotPart.GAMEMODE)
                ? new Mode(player.getGameMode(), player.getAllowFlight(), player.isFlying())
                : null;
        String location = wanted.contains(SnapshotPart.LOCATION) ? LocationSerializer.serialize(player.getLocation()) : null;
        return new PlayerSnapshot(System.currentTimeMillis(), wanted, inventory, cursor, heldSlot, ender, vitals, xp,
                effects, mode, location, 0);
    }

    // ------------------------------------------------------------------ restore and clear

    /**
     * Replaces the captured parts of {@code player} with this snapshot. Call on the player's
     * thread. Health is kept within the current maximum (0 or less becomes the maximum), air
     * within the maximum air, flight only when it is allowed, and the location only when its
     * world is loaded (the teleport is asynchronous). Items that do not fit (a smaller
     * inventory, the stored cursor) go into the inventory or drop at the player's feet.
     */
    public void restoreTo(Player player) {
        apply(player, false);
    }

    /**
     * Applies this snapshot; with {@code keepCurrent} the items the player has now in the
     * replaced inventories are given back after it instead of being replaced (used to undo a
     * clear without losing what the player picked up since).
     */
    void apply(Player player, boolean keepCurrent) {
        List<ItemStack> extra = new ArrayList<>();
        if (mode != null) {
            player.setGameMode(mode.gameMode());
            player.setAllowFlight(mode.allowFlight());
            if (player.getAllowFlight()) {
                player.setFlying(mode.flying());
            }
        }
        if (effects != null) {
            removeEffects(player);
            for (PotionEffect effect : effects) {
                player.addPotionEffect(effect);
            }
        }
        if (vitals != null) {
            double max = maxHealth(player);
            double health = vitals.health();
            player.setHealth(health > 0 ? Math.min(health, max) : max);
            player.setFoodLevel(Math.max(0, Math.min(FULL_FOOD, vitals.food())));
            player.setSaturation(Math.max(0f, vitals.saturation()));
            player.setExhaustion(Math.max(0f, vitals.exhaustion()));
            player.setRemainingAir(Math.min(vitals.air(), player.getMaximumAir()));
            player.setFireTicks(vitals.fireTicks());
        }
        if (experience != null) {
            player.setTotalExperience(Math.max(0, experience.total()));
            player.setLevel(Math.max(0, experience.level()));
            float exp = experience.exp();
            player.setExp(exp >= 0f && exp <= 1f ? exp : 0f);
        }
        if (inventory != null) {
            PlayerInventory inv = player.getInventory();
            if (keepCurrent) {
                collect(inv.getContents(), extra);
                addIfPresent(player.getItemOnCursor(), extra);
            }
            player.setItemOnCursor(null);
            inv.setContents(fit(inventory, inv.getSize(), extra));
            addIfPresent(cursor, extra);
            inv.setHeldItemSlot(heldSlot >= 0 && heldSlot < HOTBAR ? heldSlot : 0);
        }
        if (enderChest != null) {
            Inventory ender = player.getEnderChest();
            if (keepCurrent) {
                collect(ender.getContents(), extra);
            }
            ender.setContents(fit(enderChest, ender.getSize(), extra));
        }
        if (!extra.isEmpty()) {
            give(player, extra);
        }
        if (location != null) {
            Location target = LocationSerializer.deserialize(location);
            if (target != null) {
                player.teleportAsync(target);
            }
        }
    }

    /**
     * Empties the given parts of {@code player}: inventory and cursor (and ender chest) empty,
     * health, food (20), saturation (5) and air full, no exhaustion or fire, no experience, no
     * effects. Game mode and location are left as they are. Call on the player's thread.
     */
    public static void clear(Player player, Set<SnapshotPart> parts) {
        Set<SnapshotPart> which = SnapshotPart.copyOf(parts);
        if (which.contains(SnapshotPart.EFFECTS)) {
            removeEffects(player);
        }
        if (which.contains(SnapshotPart.INVENTORY)) {
            player.setItemOnCursor(null);
            PlayerInventory inv = player.getInventory();
            inv.setContents(new ItemStack[inv.getSize()]);
        }
        if (which.contains(SnapshotPart.ENDER_CHEST)) {
            Inventory ender = player.getEnderChest();
            ender.setContents(new ItemStack[ender.getSize()]);
        }
        if (which.contains(SnapshotPart.HEALTH)) {
            player.setHealth(maxHealth(player));
            player.setFoodLevel(FULL_FOOD);
            player.setSaturation(CLEAR_SATURATION);
            player.setExhaustion(0f);
            player.setRemainingAir(player.getMaximumAir());
            player.setFireTicks(0);
        }
        if (which.contains(SnapshotPart.EXPERIENCE)) {
            player.setTotalExperience(0);
            player.setLevel(0);
            player.setExp(0f);
        }
    }

    // ------------------------------------------------------------------ reading

    /** The captured parts. */
    public Set<SnapshotPart> parts() {
        return parts;
    }

    /** When it was captured, in epoch milliseconds. */
    public long takenAt() {
        return takenAt;
    }

    /** How many non-empty stacks it holds (inventory, cursor and ender chest). */
    public int itemCount() {
        int count = cursor == null ? 0 : 1;
        count += count(inventory);
        count += count(enderChest);
        return count;
    }

    ItemStack[] inventory() {
        return inventory;
    }

    ItemStack cursor() {
        return cursor;
    }

    int heldSlot() {
        return heldSlot;
    }

    ItemStack[] enderChest() {
        return enderChest;
    }

    Vitals vitals() {
        return vitals;
    }

    Xp experience() {
        return experience;
    }

    List<PotionEffect> effects() {
        return effects;
    }

    Mode mode() {
        return mode;
    }

    String location() {
        return location;
    }

    /** Effects of the file that were left out because their type no longer exists. */
    int skippedEffects() {
        return skippedEffects;
    }

    // ------------------------------------------------------------------ text form

    /**
     * The YAML text of this snapshot ({@code format: 1}).
     *
     * @throws IllegalStateException when an item cannot be written
     */
    public String toYaml() {
        return toYaml(SnapshotCodec.SERVER);
    }

    String toYaml(SnapshotCodec codec) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        yaml.set("taken-at", takenAt);
        List<String> names = new ArrayList<>(parts.size());
        for (SnapshotPart part : parts) {
            names.add(part.name());
        }
        yaml.set("parts", names);
        if (inventory != null) {
            ConfigurationSection section = yaml.createSection("inventory");
            writeSlots(section.createSection("items"), inventory, codec, "inventory");
            section.set("cursor", cursor == null ? "" : encode(codec, cursor, "cursor"));
            section.set("held-slot", heldSlot);
        }
        if (vitals != null) {
            ConfigurationSection section = yaml.createSection("health");
            section.set("health", vitals.health());
            section.set("food", vitals.food());
            section.set("saturation", vitals.saturation());
            section.set("exhaustion", vitals.exhaustion());
            section.set("air", vitals.air());
            section.set("fire-ticks", vitals.fireTicks());
        }
        if (experience != null) {
            ConfigurationSection section = yaml.createSection("experience");
            section.set("level", experience.level());
            section.set("exp", experience.exp());
            section.set("total", experience.total());
        }
        if (effects != null) {
            List<Map<String, Object>> list = new ArrayList<>(effects.size());
            for (PotionEffect effect : effects) {
                list.add(codec.encodeEffect(effect));
            }
            yaml.set("effects", list);
        }
        if (mode != null) {
            ConfigurationSection section = yaml.createSection("gamemode");
            section.set("mode", mode.gameMode().name());
            section.set("allow-flight", mode.allowFlight());
            section.set("flying", mode.flying());
        }
        if (location != null) {
            yaml.set("location", location);
        }
        if (enderChest != null) {
            writeSlots(yaml.createSection("ender-chest"), enderChest, codec, "ender chest");
        }
        return yaml.saveToString();
    }

    /**
     * Reads a snapshot written by {@link #toYaml()}. Every item is decoded here: one that
     * cannot be read makes the whole snapshot unreadable, so it is never applied in part. An
     * effect whose type no longer exists is left out.
     *
     * @throws IllegalArgumentException when the text is not a readable {@code format: 1} snapshot
     */
    public static PlayerSnapshot fromYaml(String text) {
        return fromYaml(text, SnapshotCodec.SERVER);
    }

    static PlayerSnapshot fromYaml(String text, SnapshotCodec codec) {
        if (text == null) {
            throw unreadable("no text");
        }
        Object root;
        try {
            root = parser().load(text);
        } catch (YAMLException | ClassCastException e) {
            throw unreadable("not YAML (" + e.getMessage() + ")");
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw unreadable("not a YAML map");
        }
        Object format = map.get("format");
        if (!(format instanceof Number version) || version.doubleValue() != FORMAT) {
            throw unreadable("format " + format + " (only " + FORMAT + " is known)");
        }
        long takenAt = number(map, "taken-at", 0L, "taken-at").longValue();
        Set<SnapshotPart> parts = readParts(map.get("parts"));

        ItemStack[] inventory = null;
        ItemStack cursor = null;
        int heldSlot = 0;
        if (parts.contains(SnapshotPart.INVENTORY)) {
            Map<?, ?> section = section(map, "inventory");
            inventory = readSlots(section(section, "items"), codec, "inventory.items");
            Object rawCursor = section.get("cursor");
            if (rawCursor != null && !(rawCursor instanceof String)) {
                throw unreadable("inventory.cursor is not text");
            }
            cursor = rawCursor == null || ((String) rawCursor).isBlank() ? null
                    : decode(codec, (String) rawCursor, "inventory.cursor");
            heldSlot = number(section, "held-slot", 0, "inventory.held-slot").intValue();
        }
        ItemStack[] ender = parts.contains(SnapshotPart.ENDER_CHEST)
                ? readSlots(section(map, "ender-chest"), codec, "ender-chest")
                : null;
        Vitals vitals = null;
        if (parts.contains(SnapshotPart.HEALTH)) {
            Map<?, ?> s = section(map, "health");
            vitals = new Vitals(number(s, "health", 20.0, "health.health").doubleValue(),
                    number(s, "food", FULL_FOOD, "health.food").intValue(),
                    number(s, "saturation", CLEAR_SATURATION, "health.saturation").floatValue(),
                    number(s, "exhaustion", 0f, "health.exhaustion").floatValue(),
                    number(s, "air", Integer.MAX_VALUE, "health.air").intValue(),
                    number(s, "fire-ticks", 0, "health.fire-ticks").intValue());
        }
        Xp xp = null;
        if (parts.contains(SnapshotPart.EXPERIENCE)) {
            Map<?, ?> s = section(map, "experience");
            xp = new Xp(number(s, "level", 0, "experience.level").intValue(),
                    number(s, "exp", 0f, "experience.exp").floatValue(),
                    number(s, "total", 0, "experience.total").intValue());
        }
        List<PotionEffect> effects = null;
        int skipped = 0;
        if (parts.contains(SnapshotPart.EFFECTS)) {
            Object raw = map.get("effects");
            if (raw != null && !(raw instanceof List<?>)) {
                throw unreadable("effects is not a list");
            }
            effects = new ArrayList<>();
            if (raw != null) {
                for (Object entry : (List<?>) raw) {
                    PotionEffect effect = entry instanceof Map<?, ?> m ? decodeEffect(codec, m) : null;
                    if (effect == null) {
                        skipped++;
                    } else {
                        effects.add(effect);
                    }
                }
            }
        }
        Mode mode = null;
        if (parts.contains(SnapshotPart.GAMEMODE)) {
            Map<?, ?> s = section(map, "gamemode");
            Object name = s.get("mode");
            GameMode gameMode;
            try {
                gameMode = GameMode.valueOf(String.valueOf(name));
            } catch (IllegalArgumentException unknown) {
                throw unreadable("gamemode.mode " + name + " is not a game mode");
            }
            mode = new Mode(gameMode, bool(s, "allow-flight", "gamemode.allow-flight"), bool(s, "flying", "gamemode.flying"));
        }
        String location = null;
        if (parts.contains(SnapshotPart.LOCATION)) {
            Object raw = map.get("location");
            if (raw != null && !(raw instanceof String)) {
                throw unreadable("location is not text");
            }
            location = (String) raw;
        }
        return new PlayerSnapshot(takenAt, parts, inventory, cursor, heldSlot, ender, vitals, xp, effects, mode,
                location, skipped);
    }

    // ------------------------------------------------------------------ helpers

    /** A plain YAML reader: Bukkit's {@code ==} tags stay maps, so one bad effect cannot spoil the file. */
    private static Yaml parser() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        DumperOptions dumper = new DumperOptions();
        return new Yaml(new SafeConstructor(options), new Representer(dumper), dumper, options, new Resolver());
    }

    private static Set<SnapshotPart> readParts(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw unreadable("parts is not a list of parts");
        }
        Set<SnapshotPart> parts = EnumSet.noneOf(SnapshotPart.class);
        for (Object name : list) {
            try {
                parts.add(SnapshotPart.valueOf(String.valueOf(name)));
            } catch (IllegalArgumentException unknown) {
                throw unreadable("unknown part " + name);
            }
        }
        return Collections.unmodifiableSet(parts);
    }

    private static void writeSlots(ConfigurationSection section, ItemStack[] items, SnapshotCodec codec, String what) {
        section.set("size", items.length);
        for (int slot = 0; slot < items.length; slot++) {
            if (items[slot] != null) {
                section.set(Integer.toString(slot), encode(codec, items[slot], what + " slot " + slot));
            }
        }
    }

    private static ItemStack[] readSlots(Map<?, ?> section, SnapshotCodec codec, String path) {
        int size = number(section, "size", -1, path + ".size").intValue();
        if (size < 0 || size > MAX_SIZE) {
            throw unreadable(path + ".size " + section.get("size") + " is outside 0-" + MAX_SIZE);
        }
        ItemStack[] items = new ItemStack[size];
        for (Map.Entry<?, ?> entry : section.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.equals("size")) {
                continue;
            }
            int slot = slotNumber(key);
            if (slot < 0 || slot >= size) {
                throw unreadable(path + "." + key + " is not a slot below " + size);
            }
            Object value = entry.getValue();
            if (value != null && !(value instanceof String)) {
                throw unreadable(path + "." + key + " is not text");
            }
            if (value != null && !((String) value).isBlank()) {
                items[slot] = decode(codec, (String) value, path + "." + key);
            }
        }
        return items;
    }

    /** The slot in {@code key} (digits only), or -1. */
    static int slotNumber(String key) {
        int length = key.length();
        if (length == 0 || length > 3) {
            return -1;
        }
        int slot = 0;
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            slot = slot * 10 + (c - '0');
        }
        return slot;
    }

    private static Map<?, ?> section(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> section)) {
            throw unreadable(key + " is missing or not a section");
        }
        return section;
    }

    private static Number number(Map<?, ?> map, String key, Number fallback, String path) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw unreadable(path + " is not a number: " + value);
        }
        return number;
    }

    private static boolean bool(Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean b)) {
            throw unreadable(path + " is not true or false: " + value);
        }
        return b;
    }

    private static String encode(SnapshotCodec codec, ItemStack item, String where) {
        try {
            return codec.encodeItem(item);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The item in " + where + " cannot be written: " + e.getMessage(), e);
        }
    }

    private static ItemStack decode(SnapshotCodec codec, String text, String path) {
        try {
            ItemStack item = codec.decodeItem(text);
            if (item == null) {
                throw new IllegalArgumentException("no item");
            }
            return item;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unreadable snapshot: the item at " + path + " cannot be read ("
                    + e.getMessage() + ")", e);
        }
    }

    private static PotionEffect decodeEffect(SnapshotCodec codec, Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        try {
            return codec.decodeEffect(map);
        } catch (RuntimeException unknownType) {
            return null;
        }
    }

    private static IllegalArgumentException unreadable(String reason) {
        return new IllegalArgumentException("Unreadable snapshot: " + reason);
    }

    private static ItemStack[] copies(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = copy(items[i]);
        }
        return out;
    }

    /** A copy of a non-empty stack, or null. */
    private static ItemStack copy(ItemStack item) {
        return empty(item) ? null : item.clone();
    }

    /** Null, air or no amount. Compares the three airs directly: {@code isAir()} goes through the registries. */
    static boolean empty(ItemStack item) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }
        Material type = item.getType();
        return type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR;
    }

    private static void addIfPresent(ItemStack item, List<ItemStack> out) {
        if (!empty(item)) {
            out.add(item.clone());
        }
    }

    private static void collect(ItemStack[] items, List<ItemStack> out) {
        for (ItemStack item : items) {
            addIfPresent(item, out);
        }
    }

    /** {@code items} copied into an array of {@code size}; what does not fit goes to {@code extra}. */
    private static ItemStack[] fit(ItemStack[] items, int size, List<ItemStack> extra) {
        ItemStack[] out = new ItemStack[size];
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item == null) {
                continue;
            }
            if (slot < size) {
                out[slot] = item.clone();
            } else {
                extra.add(item.clone());
            }
        }
        return out;
    }

    /** Adds {@code items} (non-empty copies) to the inventory and drops at the player's feet what does not fit. */
    private static void give(Player player, List<ItemStack> items) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(items.toArray(new ItemStack[0]));
        if (leftover.isEmpty()) {
            return;
        }
        Location feet = player.getLocation();
        World world = feet.getWorld();
        for (ItemStack rest : leftover.values()) {
            int max = Math.max(1, rest.getMaxStackSize());
            int left = rest.getAmount();
            while (left > 0) {
                ItemStack drop = rest.clone();
                drop.setAmount(Math.min(left, max));
                left -= drop.getAmount();
                world.dropItem(feet, drop);
            }
        }
    }

    private static int count(ItemStack[] items) {
        if (items == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : items) {
            if (item != null) {
                count++;
            }
        }
        return count;
    }

    private static void removeEffects(Player player) {
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
    }

    @SuppressWarnings("deprecation")
    private static double maxHealth(Player player) {
        return player.getMaxHealth();
    }
}
