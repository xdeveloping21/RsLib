package com.rs.lib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

/** A registered item's YAML and its rules; the listener that applies them needs a server. */
class ItemDefTest {

    private final List<String> warnings = new ArrayList<>();
    private final Consumer<String> warn = warnings::add;

    private ItemDef read(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        ItemAppearance appearance = ItemAppearance.parse(config, "items.yml -> x",
                ItemAppearance.TextResolver.of((com.rs.lib.papi.RsPapi) null), warn);
        return ItemDef.fromSection(config, appearance, warn);
    }

    @Test
    void defaults() {
        ItemDef def = read("material: STONE\n");
        assertTrue(def.droppable() && def.moveable() && def.placeable() && def.tradeable() && def.despawnable());
        assertFalse(def.keepOnDeath() || def.locked() || def.noDrop() || def.noManualEquip());
        assertEquals(ObtainMode.UNRESTRICTED, def.obtainVia());
        assertEquals(0, def.cooldownTicks());
        assertEquals(0, def.durabilityMax());
        assertEquals(1, def.durabilityDamagePerUse());
        assertTrue(def.shiftOverridesGeneric());
        assertTrue(def.rightClickActions().isEmpty());
        assertNull(def.recipe());
        assertNull(def.equipmentSlot());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /**
     * Trampa 7: COMMAND_ONLY means no manual equipping (only {@code apply} equips it); it does not
     * touch crafting, so its recipe is kept and registered like any other.
     */
    @Test
    void commandOnlyBlocksManualEquipAndKeepsItsRecipe() {
        ItemDef def = read("obtain-via: command-only\n"
                + "recipe:\n  type: SHAPELESS\n  ingredients: [DIRT, SAND]\n");
        assertEquals(ObtainMode.COMMAND_ONLY, def.obtainVia());
        assertTrue(def.noManualEquip());
        assertTrue(ItemListener.equipRefused(EquipmentSlot.HEAD, null, def.noManualEquip()));
        assertTrue(ItemListener.equipRefused(EquipmentSlot.OFF_HAND, EquipmentSlot.OFF_HAND, def.noManualEquip()));
        assertFalse(ItemListener.equipRefused(EquipmentSlot.HAND, null, def.noManualEquip()));
        assertEquals("SHAPELESS", def.recipe().type());
        assertEquals(List.of("DIRT", "SAND"), def.recipe().shapelessIngredients());
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertFalse(read("material: STONE\n").noManualEquip());
        assertTrue(def.droppable());
        assertTrue(def.tradeable());
        read("obtain-via: SOMETIMES\n");
        assertEquals(1, warnings.size());
    }

    @Test
    void lockModesDecideDropAndDeath() {
        assertTrue(read("no-drop: true\n").refusesDrop());
        assertTrue(read("droppable: false\n").refusesDrop());
        ItemDef locked = read("locked: true\n");
        assertTrue(locked.refusesDrop());
        assertTrue(locked.keptOnDeath());
        assertTrue(read("keep-on-death: true\n").keptOnDeath());
        assertFalse(read("material: STONE\n").keptOnDeath());
    }

    @Test
    void fullItem() {
        ItemDef def = read("""
                material: DIAMOND_PICKAXE
                display-name: "[rgb]&lPico minero"
                locked: true
                cooldown: 20
                equipment-slot: MAINHAND
                custom-durability:
                  max: 500
                  damage-per-use: 0
                  lore-format: "&7Durabilidad: &f%durability%/%max_durability%"
                  break-actions: ["[sound] ENTITY_ITEM_BREAK", "[message] &cSe rompio"]
                right-click-block-actions:
                  - "[click-block] [particle] FLAME 20 0.3 0.3 0.3 0.01"
                shift-overrides-generic: false
                interact-requirements: ["[permission] x.use"]
                deny-actions: "[message] no"
                held-effects:
                  mainhand: ["HASTE 1"]
                """);
        assertEquals(20, def.cooldownTicks());
        assertEquals(500, def.durabilityMax());
        assertEquals(1, def.durabilityDamagePerUse(), "at least 1");
        assertEquals(2, def.breakActions().size());
        assertEquals(1, def.rightClickBlockActions().size());
        assertTrue(def.active(ItemUse.RIGHT_CLICK_BLOCK));
        assertFalse(def.active(ItemUse.RIGHT_CLICK));
        assertFalse(def.shiftOverridesGeneric());
        assertEquals(List.of("[message] no"), def.denyActions(), "one text is a one-line list");
        assertEquals(List.of("HASTE 1"), def.heldEffectsMainhand());
        assertTrue(def.hasHeldEffects());
        assertEquals(EquipmentSlot.HAND, def.equipmentSlot());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void badValuesKeepDefaultsAndAreReported() {
        ItemDef def = read("droppable: maybe\ncooldown: soon\nright-click-actions:\n  - [close]\n");
        assertTrue(def.droppable());
        assertEquals(0, def.cooldownTicks());
        assertTrue(def.rightClickActions().isEmpty());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    /** Requirements that cannot be checked fail, so an item is never usable by mistake. */
    @Test
    void requirementsFailClosedUntilAnEngineIsBound() {
        assertTrue(read("material: STONE\n").interactRequirement().test(null, null));
        assertFalse(read("interact-requirements: ['[permission] x']\n").interactRequirement().test(null, null));
        ItemActions unbound = ItemActions.unbound(Logger.getLogger("test"));
        assertTrue(unbound.requirement(List.of(), warn).test(null, null));
        assertFalse(unbound.requirement(List.of("[permission] x"), warn).test(null, null));
        assertEquals(1, warnings.size());
    }

    @Test
    void recipes() {
        ItemDef shaped = read("""
                recipe:
                  type: shaped
                  shape: ["DDD", " S ", " S "]
                  ingredients:
                    D: DIAMOND
                    S: STICK
                """);
        assertEquals("SHAPED", shaped.recipe().type());
        assertEquals(List.of("DDD", " S ", " S "), shaped.recipe().shape());
        assertEquals(Map.of('D', "DIAMOND", 'S', "STICK"), shaped.recipe().ingredients());

        ItemDef shapeless = read("recipe:\n  type: SHAPELESS\n  ingredients: [DIRT, SAND]\n");
        assertEquals(List.of("DIRT", "SAND"), shapeless.recipe().shapelessIngredients());

        ItemDef smoking = read("recipe:\n  type: SMOKING\n  input: BEEF\n  experience: 0.35\n");
        assertEquals("SMOKING", smoking.recipe().type());
        assertEquals(200, smoking.recipe().cookingTime());
        assertEquals(0.35, smoking.recipe().experience(), 1e-9);

        assertEquals("STONECUTTING", read("recipe:\n  type: STONECUTTING\n  input: STONE\n").recipe().type());
        assertTrue(warnings.isEmpty(), warnings.toString());

        assertNull(read("recipe:\n  type: SHAPED\n  shape: [A]\n").recipe());
        assertNull(read("recipe:\n  type: BREWING\n").recipe());
        assertEquals(2, warnings.size());
    }
}
