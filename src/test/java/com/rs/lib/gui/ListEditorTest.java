package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.gui.GuiKit.TestPlayer;
import com.rs.lib.gui.GuiKit.TestStack;
import com.rs.lib.gui.ListEditor.End;

/** The list editor without a server: the working copy, save and cancel, reordering and every ending. */
class ListEditorTest {

    /** Entries in cells 0-3; prev 9, save 10, cancel 11, add 12, add-hand 13, next 17. */
    private static final String MENU = """
            title: 'Edit'
            pagination: true
            layout:
              - 'eeee     '
              - 'pscah   n'
            paged-key: e
            items:
              previous: {key: p, material: ARROW, click-actions: ['[previous-page]']}
              next: {key: n, material: ARROW, click-actions: ['[next-page]']}
              save: {key: s, material: LIME_DYE, click-actions: ['[list-editor] save']}
              cancel: {key: c, material: RED_DYE, click-actions: ['[list-editor] cancel']}
              add: {key: a, material: CHEST, click-actions: ['[list-editor] add']}
              hand: {key: h, material: HOPPER, click-actions: ['[list-editor] add-hand']}
            templates:
              entry:
                material: PAPER
                display-name: '{name}'
                left-click-actions: ['[list-editor] move-up {index}']
                shift-left-click-actions: ['[list-editor] move-down {index}']
                right-click-actions: ['[list-editor] remove {index}']
            """;

    private final GuiKit kit = new GuiKit();
    private TestPlayer steve;
    private final List<List<String>> saved = new ArrayList<>();
    private final List<End> ends = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kit.gui("edit", MENU);
        assertTrue(kit.allWarnings().isEmpty(), kit.allWarnings().toString());
        steve = kit.player("Steve");
    }

    private ListEditor<String> editor(List<String> list) {
        return kit.manager.listEditor("edit", list)
                .placeholders((value, phs) -> phs.add("name", value))
                .onSave(saved::add)
                .onEnd(ends::add);
    }

    private GuiSession session() {
        return kit.manager.get("edit").session(steve.player);
    }

    private void click(int slot, ClickType type) {
        GuiSession session = session();
        assertNotNull(session, "the editor menu is not open");
        session.handleClick(slot, type);
        kit.tick();
    }

    @Test
    void editsGoIntoACopyAndSaveHandsItOver() {
        List<String> original = new ArrayList<>(List.of("A", "B", "C"));
        ListEditor<String> editor = editor(original).open(steve.player);
        assertEquals("entry[index=1,name=A]", kit.shown(steve, 0));
        assertEquals("entry[index=3,name=C]", kit.shown(steve, 2));
        assertNull(kit.at(steve, 3));

        click(1, ClickType.LEFT);
        assertEquals(List.of("B", "A", "C"), editor.elements(), "move-up");
        assertEquals("entry[index=1,name=B]", kit.shown(steve, 0), "the menu shows the new order");
        click(2, ClickType.RIGHT);
        assertEquals(List.of("B", "A"), editor.elements(), "remove");
        click(0, ClickType.SHIFT_LEFT);
        assertEquals(List.of("A", "B"), editor.elements(), "move-down");
        assertEquals(List.of("A", "B", "C"), original, "the list handed in is never touched");
        assertTrue(saved.isEmpty());

        session().handleClick(10, ClickType.LEFT);
        assertEquals(List.of(List.of("A", "B")), saved);
        assertNotSame(original, saved.get(0));
        assertEquals(End.SAVED, editor.endReason());
        assertNull(kit.inventoryOf(steve), "the menu closes");
        assertTrue(ends.isEmpty(), "onEnd waits a tick (it may open another menu)");
        kit.tick();
        assertEquals(List.of(End.SAVED), ends);
        assertFalse(editor.save(), "it already ended");
        assertFalse(editor.cancel());
        assertEquals(1, saved.size());
        assertEquals(List.of(End.SAVED), ends);
    }

    @Test
    void cancelAndClosingKeepNothing() {
        List<String> original = new ArrayList<>(List.of("A", "B"));
        ListEditor<String> editor = editor(original).open(steve.player);
        click(1, ClickType.RIGHT);
        click(11, ClickType.LEFT);
        assertEquals(End.CANCELLED, editor.endReason());
        assertTrue(saved.isEmpty());
        assertEquals(List.of(End.CANCELLED), ends);
        assertEquals(List.of("A", "B"), original);

        ends.clear();
        ListEditor<String> closed = editor(original).open(steve.player);
        click(0, ClickType.RIGHT);
        kit.closeByPlayer(steve);
        assertEquals(End.CANCELLED, closed.endReason(), "closing without saving is a cancel");
        kit.tick();
        assertTrue(saved.isEmpty());
        assertEquals(List.of(End.CANCELLED), ends);
        assertEquals(List.of("A", "B"), original);
    }

    @Test
    void reorderingStaysInsideTheList() {
        ListEditor<String> editor = editor(new ArrayList<>(List.of("A", "B", "C"))).open(steve.player);
        assertFalse(editor.moveUp(0));
        assertFalse(editor.moveDown(2));
        assertFalse(editor.remove(3));
        assertFalse(editor.remove(-1));
        assertFalse(editor.moveUp(3));
        assertTrue(editor.moveDown(1));
        assertEquals(List.of("A", "C", "B"), editor.elements());
        assertTrue(editor.moveUp(2));
        assertEquals(List.of("A", "B", "C"), editor.elements());
        assertTrue(editor.add("D"));
        assertFalse(editor.add(null));
        assertEquals(List.of("A", "B", "C", "D"), editor.elements());
        editor.cancel();
        assertFalse(editor.add("E"), "an ended editor changes nothing");
        assertFalse(editor.moveUp(1));
    }

    @Test
    void pagesAndTheIndexPlaceholder() {
        ListEditor<String> editor = editor(new ArrayList<>(List.of("A", "B", "C", "D", "E", "F"))).open(steve.player);
        click(17, ClickType.LEFT);
        assertEquals(2, session().page());
        assertEquals("entry[index=5,name=E]", kit.shown(steve, 0));
        click(1, ClickType.RIGHT);
        assertEquals(List.of("A", "B", "C", "D", "E"), editor.elements(), "{index} is the place in the whole list");
        click(0, ClickType.RIGHT);
        assertEquals(1, session().page(), "a page that emptied goes back");
        assertEquals("entry[index=1,name=A]", kit.shown(steve, 0));
    }

    @Test
    void quitShutdownAndReplace() {
        ListEditor<String> first = editor(new ArrayList<>(List.of("A"))).open(steve.player);
        ListEditor<String> second = editor(new ArrayList<>(List.of("X"))).open(steve.player);
        assertEquals(End.REPLACED, first.endReason());
        assertFalse(second.isEnded());
        assertEquals("entry[index=1,name=X]", kit.shown(steve, 0));
        click(0, ClickType.RIGHT);
        assertEquals(List.of(), second.elements(), "the clicks reach the newer editor");

        ends.clear();
        kit.manager.quit(steve.player);
        assertEquals(List.of(End.LEFT), ends, "onEnd runs at once for a player who left");
        assertEquals(End.LEFT, second.endReason());

        ends.clear();
        ListEditor<String> third = editor(new ArrayList<>(List.of("A"))).open(steve.player);
        kit.manager.shutdown();
        assertEquals(End.SHUT_DOWN, third.endReason());
        assertEquals(List.of(End.SHUT_DOWN), ends);
        assertTrue(saved.isEmpty(), "nothing is saved on the way down");
        ListEditor<String> late = editor(new ArrayList<>()).open(steve.player);
        assertEquals(End.SHUT_DOWN, late.endReason());
    }

    @Test
    void aFactoryThatAsksStepsAsideAndComesBack() {
        CompletableFuture<String> asked = new CompletableFuture<>();
        ListEditor<String> editor = editor(new ArrayList<>(List.of("A", "B", "C", "D", "E")))
                .factory(player -> asked).open(steve.player);
        click(17, ClickType.LEFT);
        click(12, ClickType.LEFT);
        assertNull(kit.inventoryOf(steve), "the window comes down for the question");
        assertFalse(editor.isEnded(), "stepping aside is not a close");
        asked.complete("F");
        kit.tick();
        assertFalse(editor.isEnded());
        assertEquals(List.of("A", "B", "C", "D", "E", "F"), editor.elements());
        assertEquals(2, session().page(), "back at the same page");
        assertEquals("entry[index=6,name=F]", kit.shown(steve, 1));

        ListEditor<String> now = editor(new ArrayList<>()).factory(player -> CompletableFuture.completedFuture("Z"))
                .open(steve.player);
        GuiSession shown = session();
        click(12, ClickType.LEFT);
        assertSame(shown, session(), "a value made at once keeps the window");
        assertEquals(List.of("Z"), now.elements());
    }

    @Test
    void aFactoryAnswerAfterTheEndIsDropped() {
        CompletableFuture<String> asked = new CompletableFuture<>();
        ListEditor<String> editor = editor(new ArrayList<>()).factory(player -> asked).open(steve.player);
        click(12, ClickType.LEFT);
        kit.manager.quit(steve.player);
        assertEquals(End.LEFT, editor.endReason());
        asked.complete("late");
        kit.tick();
        assertEquals(List.of(), editor.elements());
        assertNull(kit.inventoryOf(steve), "nothing reopens for an ended editor");
    }

    @Test
    void itemsAreCopiesAndThePlayerKeepsTheirs() {
        List<ItemStack> kitItems = new ArrayList<>(List.of(new TestStack("bow")));
        List<List<ItemStack>> stored = new ArrayList<>();
        ListEditor<ItemStack> editor = kit.manager.itemEditor("edit", kitItems).onSave(stored::add).open(steve.player);
        assertEquals("entry[index=1]+over(bow)", kit.shown(steve, 0));
        assertTrue(kit.at(steve, 0).endsWith("@edit:0"), "the icon is stamped like any menu stack");
        assertNotSame(kitItems.get(0), editor.elements().get(0), "the working copy holds copies");

        click(13, ClickType.LEFT);
        assertEquals(1, editor.elements().size());
        assertTrue(steve.messages.contains("Hold the item you want to add in your hand."), steve.messages.toString());

        TestStack sword = new TestStack("sword");
        steve.mainHand = sword;
        click(13, ClickType.LEFT);
        assertSame(sword, steve.mainHand, "the hand is not touched");
        assertEquals("sword", ((TestStack) editor.elements().get(1)).label);
        assertNotSame(sword, editor.elements().get(1));

        session().handleOffer(new ItemOffer(steve.player, ItemOffer.Kind.CURSOR, 0, -1, new TestStack("apple"),
                ClickType.LEFT));
        assertEquals("entry[index=3]+over(apple)", kit.shown(steve, 2));
        click(10, ClickType.LEFT);
        assertEquals(1, stored.size());
        assertEquals(List.of("bow", "sword", "apple"),
                stored.get(0).stream().map(stack -> ((TestStack) stack).label).toList());
        assertEquals(1, kitItems.size());
    }

    @Test
    void aMissingOrUnfitMenuCancels() {
        ListEditor<String> missing = kit.manager.listEditor("nope", List.of("A")).onEnd(ends::add).open(steve.player);
        assertEquals(End.CANCELLED, missing.endReason());
        kit.tick();
        assertEquals(List.of(End.CANCELLED), ends);
        assertTrue(kit.logged.stream().anyMatch(line -> line.contains("there is no menu 'nope'")), kit.logged.toString());

        kit.gui("flat", "items:\n  a: {material: STONE, slots: [0]}\n");
        ListEditor<String> flat = kit.manager.listEditor("flat", List.of("A")).open(steve.player);
        assertEquals(End.CANCELLED, flat.endReason());
        assertTrue(kit.logged.stream().anyMatch(line -> line.contains("pagination: true")), kit.logged.toString());
        assertNull(kit.inventoryOf(steve));
    }
}
