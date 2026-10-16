package com.rs.lib.item;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.rs.lib.Ph;
import com.rs.lib.Rs;
import com.rs.lib.action.Requirement;
import com.rs.lib.scheduler.RsScheduler;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;
import com.rs.lib.util.InvUtil;
import com.rs.lib.yml.RsYml;

import net.kyori.adventure.text.Component;

/**
 * The registered physical items of one plugin, reached with {@code rs().items()}.
 *
 * <p>An item is registered from YAML ({@link #register(String, RsYml)} reads the top-level
 * section with that id, {@link #loadAll} every top-level section; both also accept the items
 * wrapped in a root {@code items:} section; one broken item never stops the others) or from code ({@link #register(String, ItemDef)}). {@link #create} builds the
 * stack for a player and stamps it; one shared RsLib listener then gives it its behaviour:
 * actions on use, cooldown, requirements, custom durability, drop/move/place/trade rules,
 * locking, keeping on death, held effects and redeeming.</p>
 *
 * <h2>Stamps</h2>
 * <p>Created items carry persistent data under the plugin's namespace (its name in lower
 * case), with the key names items already in players' inventories use: {@value #TAG_KEY}
 * (STRING, the id), {@value #TAG_LOCKED}, {@value #TAG_NO_DROP}, {@value #TAG_NO_MANUAL_EQUIP},
 * {@value #TAG_KEEP_ON_DEATH} (STRING {@code "true"}, only when set), {@value #TAG_OBTAIN_VIA}
 * (STRING mode name, only for COMMAND_ONLY), and with custom durability
 * {@code snlib_durability} and {@code snlib_durability_line} (INTEGER). Recipes use the key
 * {@code <plugin>:snlib_recipe_<id>}. The listener finds the plugin of an item from the
 * namespace of its id key, so it only acts on items of plugins running on RsLib.</p>
 *
 * <p>Everything here belongs on the main thread.</p>
 */
public final class ItemRegistry {

    public static final String TAG_KEY = "snlib_item_id";
    public static final String TAG_LOCKED = "snlib_locked";
    public static final String TAG_NO_DROP = "snlib_no_drop";
    public static final String TAG_NO_MANUAL_EQUIP = "snlib_no_manual_equip";
    public static final String TAG_KEEP_ON_DEATH = "snlib_keep_on_death";
    public static final String TAG_OBTAIN_VIA = "snlib_obtain_via";
    static final String TAG_DURABILITY = "snlib_durability";
    static final String TAG_DURABILITY_LINE = "snlib_durability_line";
    static final String RECIPE_PREFIX = "snlib_recipe_";
    /** The optional root section that wraps the items of a file. */
    static final String ITEMS_SECTION = "items";
    static final String TRUE = "true";
    static final long HELD_EFFECT_PERIOD = 40L;
    static final int HELD_EFFECT_TICKS = 80;

    /** Live registries by namespace (plugin name in lower case), for the shared listener. */
    private static final Map<String, ItemRegistry> BY_NAMESPACE = new ConcurrentHashMap<>();

    /** A redeemable item: how much a click takes and what it gives. */
    public record RedeemEntry(RedeemSpec spec, RedeemHandler handler) {
    }

    private final Rs rs;
    private final JavaPlugin plugin;
    private final String namespace;
    private final Logger logger;
    private final NamespacedKey keyId;
    private final NamespacedKey keyLocked;
    private final NamespacedKey keyNoDrop;
    private final NamespacedKey keyNoManualEquip;
    private final NamespacedKey keyKeepOnDeath;
    private final NamespacedKey keyObtainVia;
    private final NamespacedKey keyDurability;
    private final NamespacedKey keyDurabilityLine;
    private final Map<String, ItemDef> defs = new LinkedHashMap<>();
    private final Map<String, RsYml> singleSources = new LinkedHashMap<>();
    private final Map<RsYml, Set<String>> fileSources = new LinkedHashMap<>();
    private final Map<String, RedeemEntry> redeemables = new ConcurrentHashMap<>();
    private final Set<NamespacedKey> recipes = new LinkedHashSet<>();
    private final EquipmentBackup backup;
    private volatile ItemActions actions;
    private TaskHandle heldEffects;
    private volatile boolean closed;

    /** Created by the context. */
    public ItemRegistry(Rs ctx) {
        this.rs = Objects.requireNonNull(ctx, "ctx");
        this.plugin = ctx.plugin();
        this.namespace = plugin.getName().toLowerCase(Locale.ROOT);
        this.logger = plugin.getLogger();
        this.keyId = new NamespacedKey(plugin, TAG_KEY);
        this.keyLocked = new NamespacedKey(plugin, TAG_LOCKED);
        this.keyNoDrop = new NamespacedKey(plugin, TAG_NO_DROP);
        this.keyNoManualEquip = new NamespacedKey(plugin, TAG_NO_MANUAL_EQUIP);
        this.keyKeepOnDeath = new NamespacedKey(plugin, TAG_KEEP_ON_DEATH);
        this.keyObtainVia = new NamespacedKey(plugin, TAG_OBTAIN_VIA);
        this.keyDurability = new NamespacedKey(plugin, TAG_DURABILITY);
        this.keyDurabilityLine = new NamespacedKey(plugin, TAG_DURABILITY_LINE);
        this.actions = ItemActions.unbound(logger);
        Path file = plugin.getDataFolder().toPath().resolve("data").resolve("equipment-backup.yml");
        this.backup = new EquipmentBackup(file, logger);
        BY_NAMESPACE.put(namespace, this);
        ctx.onQuit(this::handleQuit);
    }

    /** The listener RsLib registers once for every plugin's items. */
    public static Listener sharedListener() {
        return new ItemListener();
    }

    /** Starts what every plugin's items share (the death stash); RsLib calls it on enable. */
    public static void startShared(JavaPlugin rslib) {
        com.rs.lib.item.internal.DeathStash.start(rslib);
    }

    /** Writes the death stash and drops the skin cache; RsLib calls it on disable, after every plugin stopped. */
    public static void shutdownShared() {
        com.rs.lib.item.internal.DeathStash.shutdown();
        SkinCache.shutdown();
    }

    /**
     * Sets how this plugin's items run actions and check requirements; RsLib calls it with the
     * plugin's action engine. Requirements of items already registered are parsed again.
     */
    public void bindActions(ItemActions engine) {
        this.actions = engine == null ? ItemActions.unbound(logger) : engine;
        synchronized (defs) {
            for (Map.Entry<String, ItemDef> entry : defs.entrySet()) {
                entry.getValue().interactCheck(parseCheck(entry.getKey(), entry.getValue()));
            }
        }
    }

    // ------------------------------------------------------------------ registration

    /**
     * Registers the item written in the section {@code id} of {@code yml} (top level, or under a
     * root {@code items:} section, see {@link #loadAll}); reloads read it again.
     */
    public void register(String id, RsYml yml) {
        Objects.requireNonNull(yml, "yml");
        String checked = checkId(id);
        ItemDef def = readSection(yml, checked, itemsPath(yml.getSection(""), checked));
        if (def != null) {
            register(checked, def);
            synchronized (defs) {
                singleSources.put(checked, yml);
            }
        }
    }

    /** Registers (or replaces) an item defined in code. */
    public void register(String id, ItemDef def) {
        String checked = checkId(id);
        Objects.requireNonNull(def, "def");
        def.interactCheck(parseCheck(checked, def));
        synchronized (defs) {
            defs.put(checked, def);
            singleSources.remove(checked);
        }
        if (def.recipe() != null) {
            addRecipe(checked, def);
        }
        if (def.hasHeldEffects()) {
            startHeldEffects();
        }
    }

    /**
     * Registers every top-level section of {@code itemsFile} as an item; a broken item is
     * reported and the others still load. The items may also be wrapped in one root
     * {@code items:} section (see {@link #wrapped}). Reloads read the file's sections again (an
     * item removed from the file is unregistered).
     */
    public void loadAll(RsYml itemsFile) {
        Objects.requireNonNull(itemsFile, "itemsFile");
        ConfigurationSection file = itemsFile.getSection("");
        boolean wrapped = wrapped(file);
        ConfigurationSection root = wrapped ? file.getConfigurationSection(ITEMS_SECTION) : file;
        String prefix = wrapped ? ITEMS_SECTION + "." : "";
        Set<String> loaded = new LinkedHashSet<>();
        if (root != null) {
            for (String id : root.getKeys(false)) {
                if (!root.isConfigurationSection(id)) {
                    ItemWarnings.of(itemsFile, prefix + id).accept("Not an item section; skipped.");
                    continue;
                }
                try {
                    ItemDef def = readSection(itemsFile, id, prefix + id);
                    if (def != null) {
                        register(id, def);
                        loaded.add(id);
                    }
                } catch (RuntimeException broken) {
                    logger.log(Level.WARNING, "Could not load item '" + id + "' of " + itemsFile.name(), broken);
                }
            }
        }
        synchronized (defs) {
            fileSources.put(itemsFile, loaded);
        }
    }

    /**
     * Whether the items of a file sit under a root {@code items:} section instead of the top
     * level: the root has an {@code items} section that is not itself an item (no
     * {@code material}) and holds only sections.
     */
    static boolean wrapped(ConfigurationSection root) {
        ConfigurationSection items = root == null ? null : root.getConfigurationSection(ITEMS_SECTION);
        if (items == null || items.contains("material")) {
            return false;
        }
        for (String key : items.getKeys(false)) {
            if (!items.isConfigurationSection(key)) {
                return false;
            }
        }
        return true;
    }

    /** The path of item {@code id} in a file whose root is {@code root}: {@code items.<id>} when wrapped and there. */
    static String itemsPath(ConfigurationSection root, String id) {
        if (root != null && !root.isConfigurationSection(id) && wrapped(root)
                && root.isConfigurationSection(ITEMS_SECTION + "." + id)) {
            return ITEMS_SECTION + "." + id;
        }
        return id;
    }

    private ItemDef readSection(RsYml yml, String id, String path) {
        ConfigurationSection section = yml.getSection(path);
        Consumer<String> warn = ItemWarnings.of(yml, path);
        if (section == null) {
            warn.accept("There is no item section '" + id + "' in this file; not registered.");
            return null;
        }
        ItemAppearance appearance = ItemAppearance.parse(section, ItemWarnings.source(yml, path),
                ItemAppearance.TextResolver.of(yml), warn);
        return ItemDef.fromSection(section, appearance, warn);
    }

    private static String checkId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Item id is blank");
        }
        return id.trim();
    }

    /** How the requirement operands of {@code player} are resolved (PlaceholderAPI through the bound engine). */
    java.util.function.Function<String, String> requirementResolver(Player player) {
        return actions.resolver(player);
    }

    private Requirement parseCheck(String id, ItemDef def) {
        return actions.requirement(def.interactRequirements(),
                ItemWarnings.to(logger, "item " + id + " -> interact-requirements"));
    }

    /** The definition of {@code id}, or null. */
    public ItemDef def(String id) {
        if (id == null) {
            return null;
        }
        synchronized (defs) {
            return defs.get(id);
        }
    }

    /** Every registered id, in registration order. */
    public List<String> ids() {
        synchronized (defs) {
            return new ArrayList<>(defs.keySet());
        }
    }

    // ------------------------------------------------------------------ reload and tasks

    /**
     * Reads the YAML items again (the files themselves are re-read by the yml reload that runs
     * before), lets item warnings print again, restarts held effects and registers the recipes
     * again. Items registered in code stay.
     */
    public void reload() {
        RsItem.forgetWarnings();
        cancelTasks();
        Map<String, RsYml> singles;
        Map<RsYml, Set<String>> files;
        synchronized (defs) {
            singles = new LinkedHashMap<>(singleSources);
            files = new LinkedHashMap<>(fileSources);
            for (Set<String> ids : files.values()) {
                for (String id : ids) {
                    defs.remove(id);
                }
            }
            fileSources.clear();
        }
        for (RsYml file : files.keySet()) {
            loadAll(file);
        }
        for (Map.Entry<String, RsYml> entry : singles.entrySet()) {
            try {
                register(entry.getKey(), entry.getValue());
            } catch (RuntimeException broken) {
                logger.log(Level.WARNING, "Could not reload item '" + entry.getKey() + "'", broken);
            }
        }
        reloadRecipes();
        if (anyHeldEffects()) {
            startHeldEffects();
        }
    }

    /** Stops the held effects timer. */
    public void cancelTasks() {
        TaskHandle task = heldEffects;
        heldEffects = null;
        if (task != null) {
            task.cancel();
        }
    }

    /** Removes this plugin's recipes and adds the current ones again. */
    public void reloadRecipes() {
        removeRecipes();
        List<Map.Entry<String, ItemDef>> all;
        synchronized (defs) {
            all = new ArrayList<>(defs.entrySet());
        }
        for (Map.Entry<String, ItemDef> entry : all) {
            if (entry.getValue().recipe() != null) {
                addRecipe(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Takes everything down when the plugin stops: gives back equipment put on by
     * {@link #apply}, stops tasks and removes recipes. Called by the context.
     */
    public void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        cancelTasks();
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                restoreAll(player);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not give back the equipment of " + player.getName(), e);
            }
        }
        removeRecipes();
        BY_NAMESPACE.remove(namespace, this);
    }

    private boolean anyHeldEffects() {
        synchronized (defs) {
            return defs.values().stream().anyMatch(ItemDef::hasHeldEffects);
        }
    }

    private void startHeldEffects() {
        if (heldEffects != null || closed) {
            return;
        }
        try {
            heldEffects = rs.scheduler().timer(HELD_EFFECT_PERIOD, HELD_EFFECT_PERIOD, this::applyHeldEffects);
        } catch (RuntimeException notScheduled) {
            logger.warning("Held item effects could not start: " + notScheduled);
        }
    }

    /** Every 40 ticks: each online player's held effects, applied on that player's own thread. */
    private void applyHeldEffects() {
        RsScheduler scheduler = rs.scheduler();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (scheduler.ownsHere(player)) {
                applyHeldEffects(player);
            } else {
                try {
                    scheduler.entity(player, () -> applyHeldEffects(player));
                } catch (RuntimeException disabled) {
                    return;
                }
            }
        }
    }

    private void applyHeldEffects(Player player) {
        if (closed || !player.isOnline()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        held(player, inventory.getItemInMainHand(), Hold.MAINHAND);
        held(player, inventory.getItemInOffHand(), Hold.OFFHAND);
        for (ItemStack armour : inventory.getArmorContents()) {
            held(player, armour, Hold.ARMOR);
        }
    }

    private enum Hold { MAINHAND, OFFHAND, ARMOR }

    private void held(Player player, ItemStack item, Hold where) {
        String id = idOf(item);
        ItemDef def = def(id);
        if (def == null) {
            return;
        }
        List<String> lines = switch (where) {
            case MAINHAND -> def.heldEffectsMainhand();
            case OFFHAND -> def.heldEffectsOffhand();
            case ARMOR -> def.heldEffectsArmor();
        };
        for (String line : lines) {
            List<String> parts = ItemParts.tokens(List.of(line));
            if (parts.isEmpty()) {
                continue;
            }
            PotionEffectType type = ItemCompat.effect(parts.get(0));
            if (type == null) {
                ItemWarnings.to(logger, "item " + id + " -> held-effects").accept("Unknown effect '" + parts.get(0) + "'; skipped.");
                continue;
            }
            Integer amplifier = parts.size() > 1 ? ItemParts.intOrNull(parts.get(1)) : Integer.valueOf(0);
            player.addPotionEffect(new PotionEffect(type, HELD_EFFECT_TICKS, amplifier == null ? 0 : Math.max(0, amplifier),
                    true, false));
        }
    }

    // ------------------------------------------------------------------ creating and finding

    /**
     * The item {@code id} for {@code viewer} (may be null), placeholders {@code phs} in its
     * texts, stamped with its id and flags; null when {@code id} is not registered.
     */
    public ItemStack create(String id, Player viewer, Ph... phs) {
        ItemDef def = def(id);
        if (def == null) {
            return null;
        }
        ItemStack stack = def.render(viewer, ItemAppearance.locals(phs));
        if (stack.getType().isAir()) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyId, PersistentDataType.STRING, id.trim());
        flag(pdc, keyLocked, def.locked());
        flag(pdc, keyNoDrop, def.noDrop());
        flag(pdc, keyNoManualEquip, def.noManualEquip());
        flag(pdc, keyKeepOnDeath, def.keepOnDeath());
        if (def.obtainVia() == ObtainMode.COMMAND_ONLY) {
            pdc.set(keyObtainVia, PersistentDataType.STRING, def.obtainVia().name());
        }
        if (def.durabilityMax() > 0) {
            pdc.set(keyDurability, PersistentDataType.INTEGER, def.durabilityMax());
            if (!def.durabilityLoreFormat().isEmpty()) {
                List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                pdc.set(keyDurabilityLine, PersistentDataType.INTEGER, lore.size());
                lore.add(durabilityLine(def, def.durabilityMax()));
                meta.lore(lore);
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static void flag(PersistentDataContainer pdc, NamespacedKey key, boolean on) {
        if (on) {
            pdc.set(key, PersistentDataType.STRING, TRUE);
        }
    }

    private static Component durabilityLine(ItemDef def, int left) {
        String text = RsText.applyLocals(def.durabilityLoreFormat(), Ph.of("durability", left),
                Ph.of("max_durability", def.durabilityMax()));
        return RsItem.plain(RsText.colorUncached(text));
    }

    /** The registered id of {@code stack} under this plugin, or null. */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    /** True when {@code stack} is the registered item {@code id} of this plugin. */
    public boolean is(ItemStack stack, String id) {
        return id != null && id.equals(idOf(stack));
    }

    // ------------------------------------------------------------------ giving and taking

    /** Gives {@code amount} of {@code id}, in normal stacks; what does not fit drops at the player's feet. */
    public void give(Player player, String id, int amount) {
        if (player == null || amount <= 0) {
            return;
        }
        ItemStack one = create(id, player);
        if (one == null) {
            logger.warning("Cannot give unknown item '" + id + "' to " + player.getName());
            return;
        }
        int max = Math.max(1, one.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = amount; left > 0; left -= max) {
            ItemStack stack = one.clone();
            stack.setAmount(Math.min(left, max));
            stacks.add(stack);
        }
        InvUtil.giveItems(player, stacks.toArray(new ItemStack[0]));
    }

    /**
     * Takes up to {@code amount} of {@code id} from the player's storage, armour, off hand and
     * cursor, in that order, without events.
     *
     * @return how many were taken
     */
    public int take(Player player, String id, int amount) {
        if (player == null || id == null || amount <= 0) {
            return 0;
        }
        PlayerInventory inventory = player.getInventory();
        int taken = 0;
        for (int slot = 0; slot < inventory.getSize() && taken < amount; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (is(stack, id)) {
                int use = Math.min(stack.getAmount(), amount - taken);
                taken += use;
                inventory.setItem(slot, reduced(stack, use));
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (taken < amount && is(cursor, id)) {
            int use = Math.min(cursor.getAmount(), amount - taken);
            taken += use;
            player.setItemOnCursor(reduced(cursor, use));
        }
        return taken;
    }

    /** Takes every {@code id} the player carries (storage, armour, off hand, cursor); returns how many. */
    public int removeAll(Player player, String id) {
        return take(player, id, Integer.MAX_VALUE);
    }

    private static ItemStack reduced(ItemStack stack, int by) {
        if (stack.getAmount() <= by) {
            return null;
        }
        ItemStack rest = stack.clone();
        rest.setAmount(stack.getAmount() - by);
        return rest;
    }

    // ------------------------------------------------------------------ custom durability

    /** The custom durability left on {@code stack}, or -1 when it has none. */
    public int durability(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return -1;
        }
        ItemMeta meta = stack.getItemMeta();
        Integer left = meta == null ? null : meta.getPersistentDataContainer().get(keyDurability, PersistentDataType.INTEGER);
        return left == null ? -1 : left;
    }

    /**
     * Takes {@code amount} of custom durability from {@code stack} (changed in place) and updates
     * its lore line. Returns what is left (0 when broken; the caller removes it), or -1 when the
     * stack has no custom durability.
     */
    public int damage(ItemStack stack, int amount) {
        int left = durability(stack);
        if (left < 0) {
            return -1;
        }
        left = Math.max(0, left - Math.max(0, amount));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(keyDurability, PersistentDataType.INTEGER, left);
        ItemDef def = def(idOf(stack));
        Integer line = meta.getPersistentDataContainer().get(keyDurabilityLine, PersistentDataType.INTEGER);
        if (def != null && line != null && !def.durabilityLoreFormat().isEmpty() && meta.hasLore()) {
            List<Component> lore = new ArrayList<>(meta.lore());
            if (line >= 0 && line < lore.size()) {
                lore.set(line, durabilityLine(def, left));
                meta.lore(lore);
            }
        }
        stack.setItemMeta(meta);
        return left;
    }

    /**
     * {@link #damage(ItemStack, int)} for {@code player}: on the matching stack the player
     * carries (the same object first, then an equal stack), otherwise on {@code stack} itself
     * (the cursor, a copy). When this call breaks it (durability above 0 before, 0 after), its
     * {@code break-actions} run and, when it is in the inventory, it is removed. Returns what is
     * left, or -1 when the stack is not one of this plugin's or has no custom durability.
     */
    public int damage(Player player, ItemStack stack, int amount) {
        if (player == null || stack == null || idOf(stack) == null) {
            return -1;
        }
        PlayerInventory inventory = player.getInventory();
        int found = slotOf(inventory, stack);
        ItemStack working = found < 0 ? stack : inventory.getItem(found);
        int before = durability(working);
        int left = damage(working, amount);
        if (left < 0) {
            return -1;
        }
        boolean broke = breaks(before, left);
        if (found >= 0) {
            inventory.setItem(found, broke ? null : working);
        }
        if (broke) {
            ItemDef def = def(idOf(working));
            if (def != null) {
                runActions(player, def.breakActions(), null, null);
            }
        }
        return left;
    }

    /** The inventory slot holding {@code stack}: the same object first, then an equal stack; -1 when none. */
    static int slotOf(Inventory inventory, ItemStack stack) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == stack) {
                return slot;
            }
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (stack.equals(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** Whether a damage call took durability from above 0 to 0: only that call runs the break flow. */
    static boolean breaks(int before, int after) {
        return before > 0 && after == 0;
    }

    /**
     * Applies one successful use to the item in {@code hand}: durability goes down and, when this
     * use breaks it, the break actions run and the hand is emptied.
     */
    void used(Player player, EquipmentSlot hand, ItemDef def, ClickType click, Boolean onBlock) {
        if (def.durabilityMax() <= 0) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = inventory.getItem(hand);
        int before = durability(stack);
        int left = damage(stack, def.durabilityDamagePerUse());
        if (left < 0) {
            return;
        }
        if (breaks(before, left)) {
            inventory.setItem(hand, null);
            runActions(player, def.breakActions(), click, onBlock);
        } else {
            inventory.setItem(hand, stack);
        }
    }

    // ------------------------------------------------------------------ equipment

    /**
     * Puts the registered item {@code id} in {@code slot}, keeping what was there in
     * {@code data/equipment-backup.yml} until {@link #unapply}, the player leaving or the plugin
     * stopping. Applying over an item applied before keeps the original backup.
     *
     * @return false when {@code id} is not registered or the player is not online
     */
    public boolean apply(Player player, String id, EquipmentSlot slot) {
        if (player == null || !player.isOnline() || slot == null) {
            return false;
        }
        ItemDef def = def(id);
        ItemStack stack = create(id, player);
        if (def == null || stack == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        UUID uuid = player.getUniqueId();
        if (backup.get(uuid, slot) == null) {
            ItemStack real = inventory.getItem(slot);
            backup.put(uuid, slot, id.trim(), empty(real) ? null : ItemSerializer.serializeBase64(real));
        } else {
            EquipmentBackup.Saved saved = backup.get(uuid, slot);
            backup.put(uuid, slot, id.trim(), saved.item());
        }
        inventory.setItem(slot, stack);
        BiConsumer<Player, ItemStack> hook = def.onApply();
        if (hook != null) {
            hook.accept(player, stack);
        }
        return true;
    }

    /**
     * Takes off every applied {@code id} and gives back the real items.
     *
     * @return true when something was taken off
     */
    public boolean unapply(Player player, String id) {
        if (player == null || id == null) {
            return false;
        }
        boolean any = false;
        for (Map.Entry<EquipmentSlot, EquipmentBackup.Saved> entry : backup.all(player.getUniqueId()).entrySet()) {
            if (entry.getValue().id().equals(id.trim())) {
                restore(player, entry.getKey(), entry.getValue());
                any = true;
            }
        }
        return any;
    }

    /** Gives back every backed-up slot of {@code player} (leaving, plugin stopping, join after a crash). */
    void restoreAll(Player player) {
        for (Map.Entry<EquipmentSlot, EquipmentBackup.Saved> entry : backup.all(player.getUniqueId()).entrySet()) {
            restore(player, entry.getKey(), entry.getValue());
        }
    }

    private void restore(Player player, EquipmentSlot slot, EquipmentBackup.Saved saved) {
        PlayerInventory inventory = player.getInventory();
        ItemStack current = inventory.getItem(slot);
        ItemStack real = null;
        if (saved.item() != null) {
            try {
                real = ItemSerializer.deserializeBase64(saved.item());
            } catch (RuntimeException unreadable) {
                logger.warning("The backed-up " + slot + " item of " + player.getName() + " could not be read: " + unreadable);
                backup.remove(player.getUniqueId(), slot);
                return;
            }
        }
        boolean own = saved.id().equals(idOf(current)) || TRUE.equals(stamp(current, keyLocked));
        if (EquipmentBackup.target(empty(current), own) == EquipmentBackup.Target.SLOT) {
            inventory.setItem(slot, real);
        } else if (real != null) {
            InvUtil.giveItems(player, real);
        }
        backup.remove(player.getUniqueId(), slot);
        ItemDef def = def(saved.id());
        if (def != null && def.onRemove() != null && own) {
            def.onRemove().accept(player, current);
        }
    }

    void handleQuit(Player player) {
        try {
            restoreAll(player);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not give back the equipment of " + player.getName(), e);
        }
    }

    void handleJoin(Player player) {
        if (!backup.all(player.getUniqueId()).isEmpty()) {
            handleQuit(player);
        }
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    private String stamp(ItemStack stack, NamespacedKey key) {
        if (empty(stack) || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ redeeming

    /**
     * Makes {@code id} redeemable: any right click with it (air or block, sneaking or not, either
     * hand) is cancelled and consumes per {@code spec}, then {@code handler} runs.
     */
    public void redeemable(String id, RedeemSpec spec, RedeemHandler handler) {
        redeemables.put(checkId(id), new RedeemEntry(Objects.requireNonNull(spec, "spec"),
                Objects.requireNonNull(handler, "handler")));
    }

    public void removeRedeemable(String id) {
        if (id != null) {
            redeemables.remove(id.trim());
        }
    }

    /** The redeem settings of {@code id}, or null. */
    public RedeemEntry redeemEntry(String id) {
        return id == null ? null : redeemables.get(id.trim());
    }

    /**
     * Takes what one redeem click consumes and returns copies of it: one unit or the hand stack
     * from {@code hand}, or every {@code id} in the inventory then the cursor up to the limit.
     */
    List<ItemStack> consume(Player player, String id, EquipmentSlot hand, RedeemSpec spec) {
        List<ItemStack> taken = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        switch (spec.mode()) {
            case SINGLE, HAND_STACK -> {
                ItemStack stack = inventory.getItem(hand);
                if (!is(stack, id)) {
                    return taken;
                }
                int use = spec.mode() == RedeemSpec.Mode.SINGLE ? 1 : stack.getAmount();
                ItemStack copy = stack.clone();
                copy.setAmount(use);
                taken.add(copy);
                inventory.setItem(hand, reduced(stack, use));
            }
            case ALL_MATCHING -> {
                int limit = spec.limit() <= 0 ? Integer.MAX_VALUE : spec.limit();
                int count = 0;
                for (int slot = 0; slot < inventory.getSize() && count < limit; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (is(stack, id)) {
                        int use = Math.min(stack.getAmount(), limit - count);
                        ItemStack copy = stack.clone();
                        copy.setAmount(use);
                        taken.add(copy);
                        count += use;
                        inventory.setItem(slot, reduced(stack, use));
                    }
                }
                ItemStack cursor = player.getItemOnCursor();
                if (count < limit && is(cursor, id)) {
                    int use = Math.min(cursor.getAmount(), limit - count);
                    ItemStack copy = cursor.clone();
                    copy.setAmount(use);
                    taken.add(copy);
                    player.setItemOnCursor(reduced(cursor, use));
                }
            }
        }
        return taken;
    }

    // ------------------------------------------------------------------ recipes

    /** Whether the recipe of {@code def} may be registered: not for a {@link ObtainMode#COMMAND_ONLY} item. */
    static boolean craftable(ItemDef def) {
        return def.obtainVia() != ObtainMode.COMMAND_ONLY;
    }

    private void addRecipe(String id, ItemDef def) {
        NamespacedKey key = new NamespacedKey(plugin, RECIPE_PREFIX + ItemCompat.keyPart(id.toLowerCase(Locale.ROOT)));
        Consumer<String> warn = ItemWarnings.to(logger, "item " + id + " -> recipe");
        try {
            if (Bukkit.getRecipe(key) != null) {
                Bukkit.removeRecipe(key);
            }
            if (!craftable(def)) {
                warn.accept("Item '" + id + "' is command-only; its recipe was not registered.");
                return;
            }
            ItemStack result = create(id, null);
            org.bukkit.inventory.Recipe recipe = toBukkit(key, result, def.recipe(), warn);
            if (recipe != null && Bukkit.addRecipe(recipe)) {
                synchronized (recipes) {
                    recipes.add(key);
                }
            }
        } catch (RuntimeException refused) {
            warn.accept("The recipe could not be added: " + refused.getMessage());
        }
    }

    private void removeRecipes() {
        List<NamespacedKey> keys;
        synchronized (recipes) {
            keys = new ArrayList<>(recipes);
            recipes.clear();
        }
        for (NamespacedKey key : keys) {
            try {
                Bukkit.removeRecipe(key);
            } catch (RuntimeException ignored) {
                // The server is stopping or the recipe is already gone.
            }
        }
    }

    private static org.bukkit.inventory.Recipe toBukkit(NamespacedKey key, ItemStack result, ItemDef.Recipe recipe,
                                                      Consumer<String> warn) {
        switch (recipe.type()) {
            case "SHAPED" -> {
                ShapedRecipe shaped = new ShapedRecipe(key, result);
                shaped.shape(recipe.shape().toArray(new String[0]));
                for (Map.Entry<Character, String> entry : recipe.ingredients().entrySet()) {
                    Material material = material(entry.getValue(), warn);
                    if (material == null) {
                        return null;
                    }
                    shaped.setIngredient(entry.getKey(), material);
                }
                return shaped;
            }
            case "SHAPELESS" -> {
                ShapelessRecipe shapeless = new ShapelessRecipe(key, result);
                for (String name : recipe.shapelessIngredients()) {
                    Material material = material(name, warn);
                    if (material == null) {
                        return null;
                    }
                    shapeless.addIngredient(material);
                }
                return shapeless;
            }
            case "STONECUTTING" -> {
                Material input = material(recipe.input(), warn);
                return input == null ? null : new StonecuttingRecipe(key, result, input);
            }
            default -> {
                Material input = material(recipe.input(), warn);
                if (input == null) {
                    return null;
                }
                RecipeChoice choice = new RecipeChoice.MaterialChoice(input);
                float xp = (float) recipe.experience();
                int ticks = recipe.cookingTime();
                return switch (recipe.type()) {
                    case "SMOKING" -> new SmokingRecipe(key, result, choice, xp, ticks);
                    case "BLASTING" -> new BlastingRecipe(key, result, choice, xp, ticks);
                    case "CAMPFIRE" -> new CampfireRecipe(key, result, choice, xp, ticks);
                    default -> new FurnaceRecipe(key, result, choice, xp, ticks);
                };
            }
        }
    }

    private static Material material(String name, Consumer<String> warn) {
        Material material = name == null ? null : ItemParts.lookupMaterial(name);
        if (material == null) {
            warn.accept("Unknown recipe material '" + name + "'; the recipe is not added.");
        }
        return material;
    }

    // ------------------------------------------------------------------ shared listener access

    /** The plugin's namespace (its name in lower case). */
    String namespace() {
        return namespace;
    }

    Rs context() {
        return rs;
    }

    /** The id key of this plugin's items. */
    NamespacedKey keyId() {
        return keyId;
    }

    /** True when {@code pdc} carries the stamp {@code tag} ({@link #TAG_LOCKED} and the like) of this plugin. */
    boolean stamped(PersistentDataContainer pdc, String tag) {
        NamespacedKey key = switch (tag) {
            case TAG_LOCKED -> keyLocked;
            case TAG_NO_DROP -> keyNoDrop;
            case TAG_NO_MANUAL_EQUIP -> keyNoManualEquip;
            case TAG_KEEP_ON_DEATH -> keyKeepOnDeath;
            case TAG_OBTAIN_VIA -> keyObtainVia;
            default -> null;
        };
        if (key == null) {
            return false;
        }
        String value = pdc.get(key, PersistentDataType.STRING);
        return tag.equals(TAG_OBTAIN_VIA) ? ObtainMode.COMMAND_ONLY.name().equals(value) : TRUE.equals(value);
    }

    Logger logger() {
        return logger;
    }

    void runActions(Player player, List<String> list, ClickType click, Boolean onBlock) {
        if (list == null || list.isEmpty()) {
            return;
        }
        try {
            actions.run(player, list, click, onBlock);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Item actions " + list + " failed for " + player.getName(), e);
        }
    }

    /** The live registry of {@code namespace}, or null. */
    static ItemRegistry forNamespace(String namespace) {
        return BY_NAMESPACE.get(namespace);
    }

    /** True when no plugin on RsLib has an item registry (the listener can return at once). */
    static boolean none() {
        return BY_NAMESPACE.isEmpty();
    }

    static Collection<ItemRegistry> all() {
        return BY_NAMESPACE.values();
    }
}
