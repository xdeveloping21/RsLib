package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import com.rs.lib.action.Requirement;

/** Reading menu files: defaults, layout and keys, click lists, navigation arrows, warnings. */
class GuiParserTest {

    private final GuiKit kit = new GuiKit();

    private GuiDef parse(String yaml) {
        return kit.parse("test", yaml);
    }

    private void assertNoWarnings() {
        assertTrue(kit.allWarnings().isEmpty(), kit.allWarnings().toString());
    }

    private boolean warned(String part) {
        return kit.allWarnings().stream().anyMatch(w -> w.contains(part));
    }

    /** Trampa 11 (and the defaults): no pagination, title Menu, 3 rows, locked inventory, lax clicks. */
    @Test
    void defaults() {
        GuiDef def = parse("items: {}\n");
        assertEquals("Menu", def.title());
        assertEquals(3, def.rows());
        assertEquals(27, def.size());
        assertNull(def.inventoryTypeName());
        assertFalse(def.pagination());
        assertFalse(def.strictClicks());
        assertEquals(PlayerInventoryPolicy.LOCKED, def.playerInventory());
        assertEquals(0, def.updateInterval());
        assertTrue(def.closeActions().isEmpty());
        assertNoWarnings();
    }

    /** Trampa 15: the layout decides the rows; rows written differently is reported. */
    @Test
    void layoutDecidesRows() {
        GuiDef def = parse("""
                rows: 5
                layout:
                  - 'aaaaaaaaa'
                  - 'a   b   a'
                items:
                  border: {material: GLASS_PANE, key: a}
                  button: {material: STONE, key: b}
                """);
        assertEquals(2, def.rows());
        assertEquals(18, def.size());
        assertTrue(warned("'rows' is 5 but the layout has 2 rows"), kit.allWarnings().toString());
        assertArrayEquals(new int[] {13}, def.items().get(1).slots());
        assertEquals(11, def.items().get(0).slots().length);
    }

    @Test
    void rowsOutOfRangeAndNotNumbers() {
        assertEquals(3, parse("rows: 9\n").rows());
        assertTrue(warned("'rows' must be 1 to 6"));
        kit.warnings.clear();
        assertEquals(3, parse("rows: six\n").rows());
        assertTrue(warned("'rows' must be a whole number"));
    }

    /** Trampa 15: a non-chest inventory type gives its own size; with a layout it is reported. */
    @Test
    void inventoryTypes() {
        GuiDef dropper = parse("inventory-type: DROPPER\nitems:\n  a: {material: STONE, slots: [4]}\n");
        assertEquals("DROPPER", dropper.inventoryTypeName());
        assertEquals(9, dropper.size());
        assertNoWarnings();
        assertEquals(5, parse("inventory-type: hopper\nlayout: ['a']\n").size());
        assertTrue(warned("with a layout"));
        kit.warnings.clear();
        assertNull(parse("inventory-type: NOPE\n").inventoryTypeName());
        assertTrue(warned("is not an inventory type"));
        kit.warnings.clear();
        assertNull(parse("inventory-type: chest\n").inventoryTypeName());
        assertNoWarnings();
    }

    /** Trampa 14: a key that is not in the layout is silent (that removes a button); a bad key or no layout warns. */
    @Test
    void keysMissingFromTheLayoutAreSilent() {
        GuiDef def = parse("""
                layout: ['a        ']
                items:
                  gone: {material: STONE, key: z}
                """);
        assertNoWarnings();
        assertFalse(def.items().get(0).hasSlots());
        parse("layout: ['a']\nitems:\n  bad: {material: STONE, key: ab}\n");
        assertTrue(warned("must be one character"));
        kit.warnings.clear();
        parse("items:\n  nolayout: {material: STONE, key: a}\n");
        assertTrue(warned("needs a layout"));
        kit.warnings.clear();
        parse("items:\n  nowhere: {material: STONE}\n");
        assertTrue(warned("has no 'slots' or 'key'"));
        kit.warnings.clear();
        parse("templates:\n  row: {material: STONE}\n");
        assertNoWarnings();
    }

    @Test
    void slotsWinOverKey() {
        GuiDef def = parse("layout: ['a']\nitems:\n  both: {material: STONE, key: a, slots: [5]}\n");
        assertArrayEquals(new int[] {5}, def.items().get(0).slots());
        assertTrue(warned("'slots' wins"));
    }

    /** Trampa 12: navigation arrows are found by the text of any of their lists, in items only. */
    @Test
    void navigationArrowsAreFoundByText() {
        GuiDef def = parse("""
                pagination: true
                items:
                  next: {material: ARROW, slots: [8], click-actions: ['[sound] x', '[next-page]']}
                  back: {material: ARROW, slots: [0], right-click-actions: ['[PREVIOUS-PAGE]']}
                  denied: {material: ARROW, slots: [1], deny-actions: ['[next-page]']}
                  plain: {material: STONE, slots: [2], click-actions: ['[close]']}
                templates:
                  t: {material: ARROW, click-actions: ['[next-page]']}
                """);
        GuiItemDef next = def.items().get(0);
        GuiItemDef back = def.items().get(1);
        assertTrue(next.navNext());
        assertFalse(next.navPrevious());
        assertTrue(back.navPrevious());
        assertTrue(def.items().get(2).navNext());
        assertFalse(def.items().get(3).navNext() || def.items().get(3).navPrevious());
        assertFalse(def.template("t").item().navNext(), "templates are never arrows");
    }

    /** Trampa 23: "- [close]" without quotes is a nested list for YAML: reported at load. */
    @Test
    void unquotedActionLinesAreReported() {
        GuiDef def = parse("""
                items:
                  close:
                    material: BARRIER
                    slots: [0]
                    click-actions:
                      - [close]
                """);
        assertTrue(warned("write it in quotes"), kit.allWarnings().toString());
        assertTrue(def.items().get(0).clickActions().isEmpty());
    }

    /** A requirement list written wrong is reported once (by the requirement reader) and always fails. */
    @Test
    void requirementWrittenWrongIsReportedOnceAndFails() {
        GuiDef def = parse("""
                items:
                  vip:
                    material: STONE
                    slots: [0]
                    click-requirements: '[permission] vip'
                """);
        assertEquals(1, kit.allWarnings().size(), kit.allWarnings().toString());
        assertTrue(warned("items.vip.click-requirements"), kit.allWarnings().toString());
        assertSame(Requirement.NEVER, def.items().get(0).clickRequirement());
    }

    /** The click lists are picked field by field: exact shift list, side list, drop list, generic. */
    @Test
    void clickListsArePickedFieldByField() {
        GuiDef def = parse("""
                items:
                  b:
                    material: STONE
                    slots: [0]
                    click-actions: ['[message] generic']
                    click-requirements: ['%a% == 1']
                    deny-actions: ['[message] generic-deny']
                    right-click-actions: ['[message] right']
                    shift-right-click-requirements: ['%a% == 2']
                    left-click-deny-actions: ['[message] left-deny']
                    drop-click-actions: ['[message] drop']
                    middle-click-actions: []
                """);
        GuiItemDef b = def.items().get(0);
        assertEquals(List.of("[message] right"), b.clickActionsFor(ClickType.RIGHT));
        assertEquals(List.of("[message] right"), b.clickActionsFor(ClickType.SHIFT_RIGHT));
        assertSame(b.clickRequirement(), b.clickRequirementFor(ClickType.RIGHT), "right declares no requirement");
        assertFalse(b.clickRequirementFor(ClickType.SHIFT_RIGHT) == b.clickRequirement());
        assertEquals(List.of("[message] generic-deny"), b.denyActionsFor(ClickType.RIGHT));
        assertEquals(List.of("[message] left-deny"), b.denyActionsFor(ClickType.SHIFT_LEFT));
        assertEquals(List.of("[message] left-deny"), b.denyActionsFor(ClickType.DOUBLE_CLICK));
        assertEquals(List.of("[message] generic"), b.clickActionsFor(ClickType.LEFT));
        assertEquals(List.of("[message] drop"), b.clickActionsFor(ClickType.CONTROL_DROP));
        assertEquals(List.of("[message] generic"), b.clickActionsFor(ClickType.MIDDLE), "an empty list is not declared");
        assertEquals(List.of("[message] generic"), b.clickActionsFor(ClickType.NUMBER_KEY));
        assertEquals(List.of("[message] generic"), b.clickActionsFor(null));
        assertNoWarnings();
    }

    @Test
    void strictClicksLetThroughWhatIsDeclared() {
        GuiDef def = parse("""
                strict-clicks: true
                items:
                  plain: {material: STONE, slots: [0], click-actions: ['[close]']}
                  rich:
                    material: STONE
                    slots: [1]
                    middle-click-actions: ['[close]']
                    left-click-requirements: ['%a% == 1']
                    drop-click-deny-actions: ['[close]']
                """);
        assertTrue(def.strictClicks());
        GuiItemDef plain = def.items().get(0);
        GuiItemDef rich = def.items().get(1);
        for (ClickType always : new ClickType[] {ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT,
                ClickType.SHIFT_RIGHT}) {
            assertTrue(plain.strictAllows(always));
        }
        for (ClickType never : new ClickType[] {ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.UNKNOWN}) {
            assertFalse(rich.strictAllows(never));
        }
        assertFalse(plain.strictAllows(ClickType.MIDDLE));
        assertTrue(rich.strictAllows(ClickType.MIDDLE));
        assertFalse(plain.strictAllows(ClickType.DOUBLE_CLICK));
        assertTrue(rich.strictAllows(ClickType.DOUBLE_CLICK));
        assertTrue(rich.strictAllows(ClickType.CREATIVE));
        assertFalse(plain.strictAllows(ClickType.DROP));
        assertTrue(rich.strictAllows(ClickType.CONTROL_DROP));
    }

    @Test
    void menuKeysWrittenWrong() {
        GuiDef def = parse("""
                pagination: maybe
                player-inventory: closed
                update-interval: -5
                strict-clicks: 'TRUE'
                """);
        assertFalse(def.pagination());
        assertTrue(def.strictClicks());
        assertEquals(PlayerInventoryPolicy.LOCKED, def.playerInventory());
        assertEquals(0, def.updateInterval());
        assertTrue(warned("'pagination' must be true or false"));
        assertTrue(warned("'player-inventory' must be locked or open"));
        assertTrue(warned("'update-interval' cannot be negative"));
        assertEquals(3, kit.allWarnings().size(), kit.allWarnings().toString());
    }

    @Test
    void inputNeedsAnOpenPlayerInventory() {
        parse("items:\n  in: {material: HOPPER, slots: [0], input: true}\n");
        assertTrue(warned("'input: true' needs 'player-inventory: open'"));
        kit.warnings.clear();
        GuiDef open = parse("player-inventory: open\nitems:\n  in: {material: HOPPER, slots: [0], input: true}\n");
        assertNoWarnings();
        assertTrue(open.declaredInput(0));
        assertFalse(open.declaredInput(1));
    }

    @Test
    void pagedKeyAndFlow() {
        GuiDef def = parse("""
                pagination: true
                layout:
                  - 'xxx      '
                  - 'xxx      '
                paged-key: x
                paged-flow:
                  - 'vvv'
                  - '>> '
                """);
        assertArrayEquals(new int[] {0, 9, 1, 10, 2, 11}, def.pagedSlots());
        assertNoWarnings();
        parse("layout: ['xx']\npaged-key: x\n");
        assertTrue(warned("'pagination' is not true"));
        kit.warnings.clear();
        parse("pagination: true\nlayout: ['xx']\npaged-key: q\n");
        assertTrue(warned("is not in the layout"));
    }

    @Test
    void regions() {
        GuiDef def = parse("""
                layout:
                  - 'aaab     '
                regions:
                  first: a
                  second:
                    slots: [2, 3]
                  third:
                    key: b
                    flow: ['   <']
                  empty: ''
                  broken:
                    note: nothing
                """);
        assertArrayEquals(new int[] {0, 1, 2}, def.regionSlots("first"));
        assertArrayEquals(new int[] {2, 3}, def.regionSlots("second"));
        assertArrayEquals(new int[] {3}, def.regionSlots("third"));
        assertArrayEquals(new int[0], def.regionSlots("empty"));
        assertArrayEquals(new int[0], def.regionSlots("nope"));
        assertTrue(warned("shares cells with region 'first'"));
        assertTrue(warned("shares cells with region 'second'"));
        assertTrue(warned("needs 'key:' or 'slots:'"));
        assertEquals(List.of("first", "second", "third", "empty", "broken"), List.copyOf(def.regionIds()));
    }

    @Test
    void oneBrokenEntryDoesNotHideTheOthers() {
        GuiDef def = parse("""
                items:
                  odd: just text
                  fine: {material: STONE, slots: [1]}
                """);
        assertEquals(1, def.items().size());
        assertEquals("fine", def.items().get(0).id());
        assertTrue(warned("is not a section"));
    }

    @Test
    void closeActionsAndSounds() {
        GuiDef def = parse("""
                title: '&aShop {page}'
                open-sound: BLOCK_CHEST_OPEN
                close-sound: BLOCK_CHEST_CLOSE
                close-actions: ['[message] bye']
                """);
        assertEquals("&aShop {page}", def.title());
        assertEquals("BLOCK_CHEST_OPEN", def.openSound());
        assertEquals("BLOCK_CHEST_CLOSE", def.closeSound());
        assertEquals(List.of("[message] bye"), def.closeActions());
        assertNotNull(def.closeActionList());
        assertNoWarnings();
    }
}
