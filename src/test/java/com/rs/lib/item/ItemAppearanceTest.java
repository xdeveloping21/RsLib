package com.rs.lib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Parsing the appearance block and resolving it per viewer into a builder. Building the
 * ItemStack itself (base clone, meta) needs a server.
 */
class ItemAppearanceTest {

    private final List<String> warnings = new ArrayList<>();
    private final Consumer<String> warn = warnings::add;
    private static final ItemAppearance.TextResolver LOCALS_ONLY = ItemAppearance.TextResolver.of((com.rs.lib.papi.RsPapi) null);

    private ItemAppearance parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException(e);
        }
        return ItemAppearance.parse(config, "test.yml -> item", LOCALS_ONLY, warn);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Trampa 2: custom-model-data 0 is set to 0; leaving the key out leaves the data unset. */
    @Test
    void modelDataZeroIsNotAbsent() {
        ItemAppearance zero = parse("material: PAPER\ncustom-model-data: 0\n");
        ItemAppearance none = parse("material: PAPER\n");
        assertTrue(zero.has("custom-model-data"));
        assertFalse(none.has("custom-model-data"));
        assertEquals(0, zero.toBuilder(null, null).modelDataValue());
        assertNull(none.toBuilder(null, null).modelDataValue());
    }

    /** Trampa 3: skull-owner wins over a head texture, with a warning. */
    @Test
    void skullOwnerWinsOverTexture() {
        ItemAppearance both = parse("material: 'basehead-eyJ0ZXh0dXJlcyI6e319'\nskull-owner: Notch\n");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("skull-owner wins")), warnings.toString());
        RsItem item = both.toBuilder(null, null);
        assertEquals(Material.PLAYER_HEAD, item.material());
        assertEquals("Notch", item.skullOwnerValue());
        assertFalse(both.isStatic(), "a skull owner is resolved per render");
    }

    @Test
    void skullOwnerResolvesPlaceholdersPerViewer() {
        ItemAppearance head = parse("material: PLAYER_HEAD\nskull-owner: '%target%'\n");
        assertEquals("Alex", head.toBuilder(null, ItemAppearance.locals(Ph.of("target", "Alex"))).skullOwnerValue());
        assertEquals("Steve", head.toBuilder(null, ItemAppearance.locals(Ph.of("target", "Steve"))).skullOwnerValue());
    }

    /** Trampa 4: a block-only or unknown material never breaks the item. */
    @Test
    void unknownMaterialIsStone() {
        ItemAppearance bad = parse("material: NOT_A_MATERIAL\n");
        assertEquals(Material.STONE, bad.toBuilder(null, null).material());
        assertEquals(1, warnings.size());
        assertEquals(Material.STONE, parse("display-name: x\n").toBuilder(null, null).material());
    }

    /** Trampa 8: a number or boolean in a text key is used as text, with a warning (more tolerant than SnLib). */
    @Test
    void nonTextScalarIsUsedAsTextWithWarning() {
        ItemAppearance numbered = parse("material: PAPER\ndisplay-name: 123\n");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("display-name"));
        assertEquals("123", plain(numbered.toBuilder(null, null).nameValue()));
        warnings.clear();
        parse("material: PAPER\namount: 5\nglow: true\n");
        assertTrue(warnings.isEmpty(), "numbers and booleans are fine where they are expected");
    }

    @Test
    void namesAndLoreHaveNoItalicsUnlessAsked() {
        RsItem item = parse("display-name: '&aHello'\nlore: ['one', '&oleaning']\n").toBuilder(null, null);
        assertEquals(TextDecoration.State.FALSE, item.nameValue().decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.FALSE, item.loreValue().get(0).decoration(TextDecoration.ITALIC));
        assertEquals("leaning", plain(item.loreValue().get(1)));
    }

    @Test
    void loreValuesSplitOnLineBreaks() {
        ItemAppearance appearance = parse("lore: ['%stats%', 'end']\n");
        assertFalse(appearance.isStatic());
        List<Component> lore = appearance.toBuilder(null, ItemAppearance.locals(Ph.of("stats", "a\nb"))).loreValue();
        assertEquals(List.of("a", "b", "end"), lore.stream().map(ItemAppearanceTest::plain).toList());
    }

    @Test
    void staticAppearanceIsDetected() {
        assertTrue(parse("material: PAPER\ndisplay-name: Close\nlore: [a, b]\nglow: true\n").isStatic());
        assertFalse(parse("material: PAPER\ndisplay-name: '{page}'\n").isStatic());
        assertFalse(parse("material: '%icon%'\n").isStatic());
        assertTrue(parse("attributes: ['GENERIC_ARMOR ADD_NUMBER 2 %slot%']\n").isStatic(),
                "attribute lines are fixed values: no placeholders in them");
    }

    @Test
    void variableMaterialIsResolvedPerRender() {
        ItemAppearance appearance = parse("material: '%icon%'\n");
        assertEquals(Material.DIAMOND, appearance.toBuilder(null, ItemAppearance.locals(Ph.of("icon", "DIAMOND"))).material());
        assertEquals(Material.EMERALD, appearance.toBuilder(null, ItemAppearance.locals(Ph.of("icon", "emerald"))).material());
    }

    @Test
    void localsLastPairWins() {
        Function<String, String> locals = ItemAppearance.locals(Ph.of("a", "1"), Ph.of("a", "2"));
        assertEquals("2", locals.apply("a"));
        assertNull(locals.apply("b"));
        assertNull(ItemAppearance.locals());
    }

    @Test
    void readersReportWhatIsWritten() {
        ItemAppearance appearance = parse("material: IRON_HELMET\nlore: []\nequipment-slot: head\namount: 3\n");
        assertTrue(appearance.declaresLore());
        assertFalse(appearance.declaresName());
        assertEquals("head", appearance.equipmentSlot());
        assertEquals("test.yml -> item", appearance.source());
        assertEquals(3, appearance.toBuilder(null, null).amountValue());
        assertEquals("head", appearance.toBuilder(null, null).equipmentSlotName());
    }

    @Test
    void amountIsKeptBetweenOneAnd99() {
        assertEquals(1, parse("amount: 0\n").toBuilder(null, null).amountValue());
        assertEquals(99, parse("amount: 500\n").toBuilder(null, null).amountValue());
    }

    @Test
    void listsAcceptOneTextAndReportNestedEntries() {
        RsItem single = parse("lore: 'only line'\n").toBuilder(null, null);
        assertEquals(List.of("only line"), single.loreValue().stream().map(ItemAppearanceTest::plain).toList());
        assertTrue(warnings.isEmpty());
        parse("lore:\n  - [close]\n  - fine\n");
        assertEquals(1, warnings.size(), "an unquoted [close] is a nested list");
    }

    @Test
    void singleValueKeysRefuseLists() {
        parse("display-name: [a, b]\n");
        assertEquals(1, warnings.size());
    }
}
