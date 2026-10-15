package com.rs.lib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.junit.jupiter.api.Test;

/** The pure parsing behind the appearance keys; applying the values to an item needs a server. */
class ItemPartsTest {

    private final List<String> warnings = new ArrayList<>();
    private final Consumer<String> warn = warnings::add;

    @Test
    void tokensSplitOnSpacesCommasAndSemicolons() {
        assertEquals(List.of("sharpness", "5", "unbreaking", "3", "mending"),
                ItemParts.tokens(List.of("sharpness 5", "unbreaking;3", " , mending ,")));
        assertEquals(List.of(), ItemParts.tokens(null));
    }

    @Test
    void intOrNullTakesWholeNumbersOnly() {
        assertEquals(5, ItemParts.intOrNull("5"));
        assertEquals(-3, ItemParts.intOrNull("-3"));
        assertNull(ItemParts.intOrNull("5.0"));
        assertNull(ItemParts.intOrNull("-"));
        assertNull(ItemParts.intOrNull("sharpness"));
        assertNull(ItemParts.intOrNull("99999999999"));
    }

    @Test
    void enchantmentsAreIdLevelPairsWithLevelOneByDefault() {
        List<ItemParts.Leveled> parsed = ItemParts.enchantments(
                List.of("sharpness", "5", "unbreaking", "mending", "3", "7"), warn);
        assertEquals(List.of(new ItemParts.Leveled("sharpness", 5), new ItemParts.Leveled("unbreaking", 1),
                new ItemParts.Leveled("mending", 3)), parsed);
        assertEquals(1, warnings.size(), "the lone 7 is reported");
        assertEquals(List.of(new ItemParts.Leveled("efficiency", 1)), ItemParts.enchantments(List.of("efficiency"), warn));
    }

    @Test
    void effectsDefaultToLevelOneFor200Ticks() {
        List<ItemParts.Effect> parsed = ItemParts.effects(List.of("speed", "2", "400", "regeneration", "haste 3"), warn);
        assertEquals(List.of(new ItemParts.Effect("speed", 2, 400), new ItemParts.Effect("regeneration", 1, 200),
                new ItemParts.Effect("haste", 3, 200)), parsed);
        assertTrue(warnings.isEmpty());
        ItemParts.effects(List.of("4", "speed", "1", "20", "30"), warn);
        assertEquals(2, warnings.size(), "a leading number and a third number are reported");
    }

    @Test
    void colorsInEveryWrittenForm() {
        assertEquals(0xEB4034, ItemParts.rgb("235, 64, 52"));
        assertEquals(0xEB4034, ItemParts.rgb("235 64 52"));
        assertEquals(0xFF0000, ItemParts.rgb("FF0000"));
        assertEquals(0xFF0000, ItemParts.rgb("#ff0000"));
        assertEquals(-1, ItemParts.rgb("256, 0, 0"));
        assertEquals(-1, ItemParts.rgb("red"));
        assertEquals(-1, ItemParts.rgb(""));
        assertEquals(-1, ItemParts.rgb(null));
    }

    @Test
    void attributeLines() {
        assertEquals(new ItemParts.AttributeLine("GENERIC_ATTACK_DAMAGE", "ADD_NUMBER", 5.0, "ANY"),
                ItemParts.attribute("GENERIC_ATTACK_DAMAGE ADD_NUMBER 5", warn));
        assertEquals(new ItemParts.AttributeLine("max_health", "ADD_SCALAR", 0.5, "HEAD"),
                ItemParts.attribute("max_health add_multiplied_base 0.5 head", warn));
        assertEquals("MULTIPLY_SCALAR_1", ItemParts.attribute("speed add-multiplied-total 1 FEET", warn).operation());
        assertTrue(warnings.isEmpty());
        assertNull(ItemParts.attribute("speed ADD_NUMBER", warn));
        assertNull(ItemParts.attribute("speed TIMES 2", warn));
        assertNull(ItemParts.attribute("speed ADD_NUMBER lots", warn));
        assertEquals(3, warnings.size());
        assertEquals("ANY", ItemParts.attribute("speed ADD_NUMBER 1 WINGS", warn).group());
    }

    /** Attribute keys lost their prefixes in 1.21.2: every spelling must find the attribute. */
    @Test
    void attributeKeysCoverEveryVersion() {
        assertEquals("attack_damage", ItemParts.attributeBase("GENERIC_ATTACK_DAMAGE"));
        assertEquals("attack_damage", ItemParts.attributeBase("minecraft:generic.attack_damage"));
        assertEquals("jump_strength", ItemParts.attributeBase("HORSE_JUMP_STRENGTH"));
        List<String> keys = ItemParts.attributeKeys("GENERIC_ATTACK_DAMAGE");
        assertEquals("generic.attack_damage", keys.get(0));
        assertTrue(keys.contains("attack_damage"));
        assertTrue(ItemParts.attributeKeys("attack_damage").contains("generic.attack_damage"));
        assertTrue(ItemParts.attributeKeys("HORSE_JUMP_STRENGTH").contains("generic.jump_strength"));
    }

    @Test
    void legacyEnchantmentAndEffectNames() {
        assertEquals("minecraft:sharpness", ItemParts.enchantmentKeys("DAMAGE_ALL").get(0));
        assertTrue(ItemParts.enchantmentKeys("sweeping").contains("minecraft:sweeping_edge"));
        assertTrue(ItemParts.enchantmentKeys("SWEEPING_EDGE").contains("minecraft:sweeping"));
        assertEquals(List.of("myplugin:frost"), ItemParts.enchantmentKeys("MyPlugin:Frost"));
        assertEquals("minecraft:strength", ItemParts.effectKeys("INCREASE_DAMAGE").get(0));
        assertEquals("minecraft:speed", ItemParts.effectKeys("minecraft:speed").get(0));
    }

    /** Trampa 9: HIDE_TOOLTIP hides the whole tooltip and is never one of HIDE_ALL's flags. */
    @Test
    void hideTooltipIsNotPartOfHideAll() {
        for (ItemFlag flag : ItemFlag.values()) {
            assertFalse(flag.name().equals(ItemParts.HIDE_TOOLTIP), "HIDE_ALL adds ItemFlag.values()");
        }
        assertEquals(ItemParts.HIDE_TOOLTIP, ItemParts.constant("hide-tooltip"));
        assertEquals(ItemParts.HIDE_ALL, ItemParts.constant("hide all"));
    }

    @Test
    void flagAliasesAcrossTheRename() {
        assertEquals(List.of("HIDE_POTION_EFFECTS", "HIDE_ADDITIONAL_TOOLTIP"), ItemParts.flagNames("hide_potion_effects"));
        assertEquals(List.of("HIDE_ADDITIONAL_TOOLTIP", "HIDE_POTION_EFFECTS"), ItemParts.flagNames("HIDE_ADDITIONAL_TOOLTIP"));
        assertEquals(List.of("HIDE_ENCHANTS"), ItemParts.flagNames("hide-enchants"));
    }

    @Test
    void equipmentSlotNames() {
        assertEquals(EquipmentSlot.HAND, ItemParts.equipmentSlot("MAINHAND"));
        assertEquals(EquipmentSlot.HAND, ItemParts.equipmentSlot("main_hand"));
        assertEquals(EquipmentSlot.OFF_HAND, ItemParts.equipmentSlot("offhand"));
        assertEquals(EquipmentSlot.HEAD, ItemParts.equipmentSlot(" head "));
        assertEquals(EquipmentSlot.FEET, ItemParts.equipmentSlot("FEET"));
        assertNull(ItemParts.equipmentSlot("BODY"));
        assertNull(ItemParts.equipmentSlot(""));
        assertNull(ItemParts.equipmentSlot(null));
    }

    @Test
    void linesSplitOnLineBreaks() {
        assertEquals(List.of("a", "b", ""), ItemParts.lines("a\nb\n"));
        assertEquals(List.of("a", "b"), ItemParts.lines("a\r\nb"));
        assertEquals(List.of(""), ItemParts.lines(""));
        assertEquals(List.of(""), ItemParts.lines(null));
    }

    /** Trampa 4: a block with no item form, or an unknown name, gives STONE instead of failing. */
    @Test
    void blockOnlyAndUnknownMaterialsBecomeStone() {
        assertEquals(Material.DIAMOND_SWORD, ItemParts.material("diamond_sword", m -> true, warn).material());
        assertEquals(Material.DIAMOND_SWORD, ItemParts.material("minecraft:diamond_sword", m -> true, warn).material());
        assertTrue(warnings.isEmpty());
        assertEquals(Material.STONE, ItemParts.material("WATER", m -> m != Material.WATER, warn).material());
        assertEquals(Material.STONE, ItemParts.material("NOT_A_THING", m -> true, warn).material());
        assertEquals(Material.STONE, ItemParts.material("LEGACY_STONE", m -> true, warn).material());
        assertEquals(3, warnings.size());
        assertEquals(Material.STONE, ItemParts.material("", m -> true, warn).material());
    }

    @Test
    void headTextureGivesPlayerHead() {
        String value = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWJjIn19fQ==";
        ItemParts.MaterialSpec spec = ItemParts.material("basehead-" + value, m -> true, warn);
        assertEquals(Material.PLAYER_HEAD, spec.material());
        assertEquals(value, spec.texture());
    }

    @Test
    void modifierKeyPartsAndLegacySlots() {
        assertEquals("attack_damage", ItemCompat.keyPart("attack_damage"));
        assertEquals("a_b_c", ItemCompat.keyPart("a b:c"));
        assertEquals(EquipmentSlot.HAND, ItemCompat.legacySlot("MAINHAND"));
        assertNull(ItemCompat.legacySlot("ARMOR"), "a group of several slots means any slot before 1.20.5");
        assertNull(ItemCompat.legacySlot("ANY"));
    }
}
