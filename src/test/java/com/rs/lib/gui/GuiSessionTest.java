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

import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.gui.GuiKit.TestPlayer;
import com.rs.lib.gui.GuiKit.TestStack;

/** Sessions without a server: what cells show, clicks, pages, closes, binds and the render cache. */
class GuiSessionTest {

    private final GuiKit kit = new GuiKit();
    private String vip = "yes";
    private TestPlayer steve;
    private TestPlayer alex;

    @BeforeEach
    void setUp() {
        kit.engine.papi(text -> text.replace("%vip%", vip));
        kit.engine.engine().menuOpener((player, id, context) -> {
            Gui gui = kit.manager.get(id.trim());
            if (gui == null) {
                return false;
            }
            gui.open(player);
            return true;
        });
        steve = kit.player("Steve");
        alex = kit.player("Alex");
    }

    private List<String> console() {
        return kit.engine.console();
    }

    private GuiSession open(Gui gui, TestPlayer player) {
        gui.open(player.player);
        return gui.session(player.player);
    }

    // ------------------------------------------------------------------ identity

    /** Trampas 1 and 2: one session and inventory per player, recognised by its holder; a new title keeps the holder. */
    @Test
    void oneSessionPerPlayerAndTheHolderSurvivesANewTitle() {
        Gui gui = kit.gui("shop", """
                title: 'Shop {n}'
                close-actions: ['[console] closed']
                items:
                  a: {material: STONE, slots: [0]}
                """);
        GuiSession mine = open(gui, steve);
        GuiSession theirs = open(gui, alex);
        assertNotSame(mine, theirs);
        assertNotSame(kit.inventoryOf(steve), kit.inventoryOf(alex));
        assertSame(mine, GuiListener.sessionOf(kit.inventoryOf(steve)));
        assertSame(theirs, GuiListener.sessionOf(kit.inventoryOf(alex)));
        assertEquals(2, gui.sessions().size());

        Inventory before = kit.inventoryOf(steve);
        mine.titlePlaceholders(Ph.of("n", "2"));
        Inventory after = kit.inventoryOf(steve);
        assertNotSame(before, after, "a new title needs a new inventory");
        assertEquals("Shop 2", kit.title(steve));
        assertSame(mine.holder(), after.getHolder(false));
        assertFalse(mine.closed(), "the close of the old inventory is a transition, not a close");
        kit.tick();
        assertTrue(console().isEmpty(), "no close-actions on a transition");
        assertEquals("a", kit.shown(steve, 0));

        mine.refreshMenu();
        assertSame(after, kit.inventoryOf(steve), "the same title keeps the inventory");
    }

    /** Trampa 3: opening a menu the player has open reuses the session: binds and timers stay. */
    @Test
    void openingAgainReusesTheSession() {
        Gui gui = kit.gui("shop", """
                open-sound: OPEN
                update-interval: 20
                templates:
                  t: {material: STONE, display-name: '{x}'}
                """);
        GuiSession session = open(gui, steve);
        session.bind(4, gui.template("t"), Ph.of("x", "1"));
        assertEquals(1, kit.liveTimers());
        assertEquals(List.of("OPEN"), kit.sounds);
        GuiSession again = open(gui, steve);
        assertSame(session, again);
        assertEquals("t[x=1]", kit.shown(steve, 4), "the bind is kept");
        assertEquals(1, kit.liveTimers(), "no second timer");
        assertEquals(1, kit.windowsOpened, "the window is already shown");
        assertEquals(List.of("OPEN"), kit.sounds, "no second open sound while viewing");
    }

    // ------------------------------------------------------------------ closes

    /** Trampa 6: a natural close plays the close sound now and runs close-actions on the next tick. */
    @Test
    void naturalClose() {
        Gui gui = kit.gui("shop", "close-sound: CLOSE\nclose-actions: ['[console] closed {x}']\n");
        GuiSession session = open(gui, steve);
        session.titlePlaceholders(Ph.of("x", "now"));
        kit.closeByPlayer(steve);
        assertTrue(session.closed());
        assertNull(gui.session(steve.player));
        assertEquals(List.of("CLOSE"), kit.sounds);
        assertTrue(console().isEmpty(), "close-actions wait for the next tick");
        kit.tick();
        assertEquals(List.of("closed now"), console());
    }

    /** Trampa 6: a programmatic close ends the session first, so the close event runs nothing. */
    @Test
    void programmaticClose() {
        Gui gui = kit.gui("shop", "close-sound: CLOSE\nclose-actions: ['[console] closed']\n");
        GuiSession session = open(gui, steve);
        session.close();
        assertTrue(session.closed());
        assertNull(kit.inventoryOf(steve), "the window was closed");
        kit.tick();
        assertTrue(console().isEmpty());
        assertTrue(kit.sounds.isEmpty());
        assertEquals(0, kit.liveTimers());
    }

    /** Trampa 6: going to another menu with [open] is a natural close of the first one. */
    @Test
    void openingAnotherMenuIsANaturalClose() {
        Gui first = kit.gui("first", """
                close-actions: ['[console] left first']
                items:
                  go: {material: STONE, slots: [0], click-actions: ['[open] second']}
                """);
        Gui second = kit.gui("second", "items:\n  b: {material: STONE, slots: [1]}\n");
        GuiSession session = open(first, steve);
        session.handleClick(0, ClickType.LEFT);
        assertTrue(session.closed());
        assertNotNull(second.session(steve.player));
        assertEquals("b", kit.shown(steve, 1));
        kit.tick();
        assertEquals(List.of("left first"), console());
    }

    /** Trampa 7: the close comes before the quit; close-actions do not run for a player who left. */
    @Test
    void closeActionsSkipAPlayerWhoLeft() {
        Gui gui = kit.gui("shop", "close-actions: ['[console] closed']\n");
        open(gui, steve);
        kit.closeByPlayer(steve);
        steve.online = false;
        kit.tick();
        assertTrue(console().isEmpty());
    }

    @Test
    void quitClosesWithoutCloseActions() {
        Gui gui = kit.gui("shop", "close-actions: ['[console] closed']\nupdate-interval: 5\n");
        GuiSession session = open(gui, steve);
        kit.manager.quit(steve.player);
        assertTrue(session.closed());
        kit.tick();
        assertTrue(console().isEmpty());
        assertEquals(0, kit.liveTimers());
        assertTrue(kit.manager.openSessions().isEmpty());
    }

    @Test
    void nothingOpensWhileShuttingDownOrWhenRefused() {
        Gui gui = kit.gui("shop", "items: {}\n");
        kit.shuttingDown = true;
        gui.open(steve.player);
        assertNull(gui.session(steve.player));
        kit.shuttingDown = false;
        kit.refuseNextOpen = true;
        gui.open(steve.player);
        assertNull(gui.session(steve.player), "another plugin cancelled the window");
        assertTrue(kit.manager.openSessions().isEmpty());
        kit.manager.shutdown();
        gui.open(steve.player);
        assertNull(gui.session(steve.player));
    }

    // ------------------------------------------------------------------ what a cell shows

    /** Trampa 8: view requirements are checked again at the click; an empty cell runs nothing and loses its ghost. */
    @Test
    void viewRequirementsAreCheckedAgainAtTheClick() {
        Gui gui = kit.gui("shop", """
                items:
                  vip:
                    material: GOLD_INGOT
                    slots: [0]
                    view-requirements: ['%vip% == yes']
                    click-actions: ['[console] vip']
                  everyone:
                    material: STONE
                    slots: [0]
                    click-actions: ['[console] everyone']
                  secret:
                    material: DIAMOND
                    slots: [1]
                    view-requirements: ['%vip% == yes']
                    click-actions: ['[console] secret']
                    deny-actions: ['[console] denied']
                """);
        GuiSession session = open(gui, steve);
        assertEquals("vip", kit.shown(steve, 0), "the first item whose requirements pass");
        assertEquals("secret", kit.shown(steve, 1));
        vip = "no";
        session.handleClick(0, ClickType.LEFT);
        assertEquals(List.of("everyone"), console(), "the click shows what the cell shows now");
        assertEquals("everyone", kit.shown(steve, 0));
        session.handleClick(1, ClickType.LEFT);
        assertEquals(List.of("everyone"), console(), "an empty cell runs nothing, not even deny-actions");
        assertNull(kit.at(steve, 1), "the ghost is cleared");
        assertSame(gui.def().items().get(1), session.itemAt(0));
        assertNull(session.itemAt(1));
    }

    @Test
    void clickRequirementsAndDenyActions() {
        Gui gui = kit.gui("shop", """
                items:
                  buy:
                    material: STONE
                    slots: [0]
                    click-requirements: ['%vip% == yes']
                    click-actions: ['[console] bought']
                    deny-actions: ['[console] no']
                    right-click-actions: ['[console] right']
                """);
        GuiSession session = open(gui, steve);
        session.handleClick(0, ClickType.LEFT);
        vip = "no";
        session.handleClick(0, ClickType.LEFT);
        session.handleClick(0, ClickType.RIGHT);
        assertEquals(List.of("bought", "no", "no"), console(), "right-click uses the generic requirement and deny");
        vip = "yes";
        session.handleClick(0, ClickType.RIGHT);
        assertEquals("right", console().get(3));
    }

    @Test
    void strictClicksDiscardOtherClicks() {
        Gui gui = kit.gui("shop", """
                strict-clicks: true
                items:
                  b: {material: STONE, slots: [0], click-actions: ['[console] hit']}
                """);
        GuiSession session = open(gui, steve);
        session.handleClick(0, ClickType.NUMBER_KEY);
        session.handleClick(0, ClickType.MIDDLE);
        session.handleClick(0, ClickType.DROP);
        assertTrue(console().isEmpty());
        session.handleClick(0, ClickType.SHIFT_LEFT);
        assertEquals(List.of("hit"), console());
    }

    /** Trampa 9: manual over paged over region over declared; the paged bind owns its cells even when short. */
    @Test
    void precedence() {
        Gui gui = kit.gui("shop", """
                pagination: true
                layout:
                  - 'ppp rr   '
                paged-key: p
                regions:
                  r: {slots: [4, 5]}
                items:
                  filler: {material: GLASS, slots: [0, 1, 2, 4, 5, 6]}
                templates:
                  entry: {material: PAPER, display-name: '{name}'}
                  cell: {material: CHEST, display-name: '{v}'}
                  manual: {material: DIAMOND}
                """);
        assertTrue(kit.warnings.isEmpty(), kit.warnings.toString());
        GuiSession session = open(gui, steve);
        assertEquals("filler", kit.shown(steve, 0));
        session.bindPaged("entry", List.of("A"), (value, phs) -> phs.add("name", value));
        assertEquals("entry[name=A]", kit.shown(steve, 0));
        assertNull(kit.at(steve, 1), "paged cells stay empty past the data");
        assertNull(kit.at(steve, 2));
        session.bindEach("r", List.of("x"), (value, entry) -> entry.template("cell").add("v", value));
        assertEquals("cell[v=x]", kit.shown(steve, 4));
        assertEquals("filler", kit.shown(steve, 5), "a region only owns the cells it fills");
        session.bind(1, gui.template("manual"));
        session.bind(4, gui.template("manual"));
        assertEquals("manual", kit.shown(steve, 1));
        assertEquals("manual", kit.shown(steve, 4));
        assertEquals("filler", kit.shown(steve, 6));
    }

    /** Trampa 10: regions are painted in file order, whatever order the plugin binds them in. */
    @Test
    void laterRegionsInTheFileWin() {
        Gui gui = kit.gui("shop", """
                regions:
                  first: {slots: [0, 1]}
                  second: {slots: [1, 2]}
                templates:
                  a: {material: STONE}
                  b: {material: DIRT}
                """);
        GuiSession session = open(gui, steve);
        session.bindEach("second", List.of(1, 2), (value, entry) -> entry.template("b"));
        session.bindEach("first", List.of(1, 2), (value, entry) -> entry.template("a"));
        assertEquals("a", kit.shown(steve, 0));
        assertEquals("b", kit.shown(steve, 1));
        assertEquals("b", kit.shown(steve, 2));
        session.bindEach("second", List.of(), (value, entry) -> entry.template("b"));
        assertEquals("a", kit.shown(steve, 1), "an emptied region gives the cell back");
        assertNull(kit.at(steve, 2));
    }

    @Test
    void regionEntriesCanSkipCellsAndFillersMayFail() {
        Gui gui = kit.gui("shop", """
                regions:
                  r: {slots: [0, 1, 2]}
                items:
                  under: {material: GLASS, slots: [1]}
                templates:
                  a: {material: STONE, view-requirements: ['%vip% == yes']}
                """);
        GuiSession session = open(gui, steve);
        vip = "no";
        session.bindEach("r", List.of("x", "skip", "boom"), (value, entry) -> {
            if (value.equals("boom")) {
                throw new IllegalStateException("filler bug");
            }
            if (!value.equals("skip")) {
                entry.template("a");
            }
        });
        assertNull(kit.at(steve, 0), "the template's view requirements fail");
        assertEquals("under", kit.shown(steve, 1));
        assertNull(kit.at(steve, 2));
        assertTrue(kit.logged.stream().anyMatch(w -> w.contains("filler bug")), kit.logged.toString());
    }

    // ------------------------------------------------------------------ pages

    /** Trampa 11: without pagination: true every page action is a silent no-op, refresh-menu included. */
    @Test
    void pageActionsNeedPagination() {
        Gui gui = kit.gui("shop", """
                title: 'Shop {n}'
                items:
                  next: {material: ARROW, slots: [0], click-actions: ['[next-page]']}
                  refresh: {material: CLOCK, slots: [1], click-actions: ['[refresh-menu]', '[set-page] 3']}
                """);
        GuiSession session = open(gui, steve);
        assertFalse(session.paginationEnabled());
        session.handleClick(0, ClickType.LEFT);
        session.handleClick(1, ClickType.LEFT);
        session.setPage(4);
        session.nextPage();
        assertEquals(1, session.page());
        int writes = kit.writes;
        session.refreshPage();
        assertEquals(writes, kit.writes, "refreshPage is blocked too");
        assertTrue(kit.engine.warnings().isEmpty(), kit.engine.warnings().toString());
    }

    /** Trampa 12: arrows are disabled at the ends; with an unknown total "next" never is. */
    @Test
    void navigationArrows() {
        Gui gui = kit.gui("shop", """
                pagination: true
                items:
                  back:
                    material: ARROW
                    slots: [0]
                    click-actions: ['[previous-page]', '[console] back']
                    nav-disabled: {material: GRAY_DYE}
                  next:
                    material: ARROW
                    slots: [8]
                    click-actions: ['[next-page]']
                    nav-disabled: {material: GRAY_DYE}
                """);
        GuiSession session = open(gui, steve);
        assertEquals("back/disabled", kit.shown(steve, 0));
        assertEquals("next", kit.shown(steve, 8));
        session.handleClick(0, ClickType.LEFT);
        assertTrue(console().isEmpty(), "a disabled arrow runs nothing");
        for (int i = 0; i < 5; i++) {
            session.handleClick(8, ClickType.LEFT);
        }
        assertEquals(6, session.page(), "unknown total: next is never disabled");
        assertEquals("next", kit.shown(steve, 8));
        assertEquals("back", kit.shown(steve, 0));
        session.setTotalPages(6);
        assertEquals("next/disabled", kit.shown(steve, 8));
        session.handleClick(8, ClickType.LEFT);
        assertEquals(6, session.page());
        session.handleClick(0, ClickType.LEFT);
        assertEquals(5, session.page());
        assertEquals(List.of("back"), console());
    }

    @Test
    void pagedBindPages() {
        Gui gui = kit.gui("shop", """
                pagination: true
                title: 'Page {page}'
                items:
                  next: {material: ARROW, slots: [8], click-actions: ['[next-page]']}
                templates:
                  e: {material: PAPER, display-name: '{v}'}
                """);
        GuiSession session = open(gui, steve);
        List<Integer> data = List.of(1, 2, 3, 4, 5);
        session.bindPaged("e", data, new int[] {0, 1}, (value, phs) -> phs.add("v", value));
        assertEquals("e[v=1]", kit.shown(steve, 0));
        session.handleClick(8, ClickType.LEFT);
        session.handleClick(8, ClickType.LEFT);
        assertEquals(3, session.page());
        assertEquals("e[v=5]", kit.shown(steve, 0));
        assertNull(kit.at(steve, 1));
        assertEquals("next", kit.shown(steve, 8), "no nav-disabled look: the arrow stays as it is");
        session.handleClick(8, ClickType.LEFT);
        assertEquals(3, session.page(), "the last page");
        session.setPage(99);
        assertEquals(3, session.page());
        session.bindPaged("e", List.of(1), new int[] {0, 1}, (value, phs) -> phs.add("v", value));
        assertEquals(1, session.page(), "a shorter list clamps the page");
        session.titlePlaceholders(Ph.of("page", "1"));
        assertEquals("Page 1", kit.title(steve));
    }

    @Test
    void bindPagedNeedsPagination() {
        Gui gui = kit.gui("shop", "templates:\n  e: {material: PAPER}\n");
        GuiSession session = open(gui, steve);
        session.bindPaged("e", List.of(1), new int[] {0}, (value, phs) -> { });
        assertNull(kit.at(steve, 0));
        assertTrue(kit.logged.stream().anyMatch(w -> w.contains("'pagination: true'")), kit.logged.toString());
    }

    // ------------------------------------------------------------------ inputs and offers

    /** Trampa 19: input templates make their cell an input; a normal template removes that, never a declared input. */
    @Test
    void inputCells() {
        Gui gui = kit.gui("trade", """
                player-inventory: open
                regions:
                  r: {slots: [5]}
                items:
                  declared: {material: HOPPER, slots: [0], input: true}
                templates:
                  in: {material: HOPPER, input: true}
                  plain: {material: STONE}
                """);
        assertTrue(kit.warnings.isEmpty(), kit.warnings.toString());
        GuiSession session = open(gui, steve);
        assertTrue(session.isInputSlot(0));
        session.bind(0, gui.template("plain"));
        assertTrue(session.isInputSlot(0), "a declared input stays");
        assertFalse(session.isInputSlot(3));
        session.bind(3, gui.template("in"));
        assertTrue(session.isInputSlot(3));
        session.bind(3, gui.template("plain"));
        assertFalse(session.isInputSlot(3));
        assertFalse(session.isInputSlot(5));
        session.bindEach("r", List.of(1), (value, entry) -> entry.template("in"));
        assertTrue(session.isInputSlot(5));
        session.bindEach("r", List.of(1), (value, entry) -> entry.template("plain"));
        assertFalse(session.isInputSlot(5));
        assertFalse(session.isInputSlot(-1));
        assertFalse(session.isInputSlot(99));
    }

    @Test
    void offersGoToTheHandler() {
        Gui gui = kit.gui("trade", "player-inventory: open\n");
        GuiSession session = open(gui, steve);
        List<ItemOffer> offers = new ArrayList<>();
        ItemOffer offer = new ItemOffer(steve.player, ItemOffer.Kind.CURSOR, 0, -1, new TestStack("gold"),
                ClickType.LEFT);
        session.handleOffer(offer);
        assertTrue(kit.debug.stream().anyMatch(d -> d.contains("no offer handler")), "dropped with a note");
        session.onOffer(offers::add);
        session.handleOffer(offer);
        assertEquals(List.of(offer), offers);
        session.onOffer(o -> {
            throw new IllegalStateException("handler bug");
        });
        session.handleOffer(offer);
        assertTrue(kit.logged.stream().anyMatch(w -> w.contains("offer handler")));
        session.onOffer(offers::add);
        session.close();
        session.handleOffer(offer);
        assertEquals(1, offers.size(), "a closed session takes nothing");
    }

    // ------------------------------------------------------------------ rendering

    /** A look that is the same for everyone is built once and shared; a refresh with no change writes nothing. */
    @Test
    void staticLooksAreBuiltOnceAndUnchangedCellsAreNotWritten() {
        Gui gui = kit.gui("shop", """
                items:
                  border: {material: GLASS, slots: [0, 1, 2, 3]}
                  named: {material: STONE, slots: [4], display-name: 'Hi %vip%'}
                """);
        GuiSession mine = open(gui, steve);
        open(gui, alex);
        assertEquals(1 + 2, kit.builds, "border once for everyone, named once per viewer");
        assertEquals(4 + 2, kit.stamps, "border stamps are cached per cell; named is stamped per viewer");
        int writes = kit.writes;
        mine.refreshMenu();
        assertEquals(writes, kit.writes, "nothing changed, nothing written");
        assertEquals("border@shop:2", kit.at(steve, 2));
        assertEquals("border@shop:2", kit.at(alex, 2));
    }

    /** A plugin that wrote into the inventory through the holder gets every cell rewritten on the next render. */
    @Test
    void directWritesAreRepaired() {
        Gui gui = kit.gui("shop", "items:\n  a: {material: STONE, slots: [0]}\n");
        GuiSession session = open(gui, steve);
        Inventory inventory = session.holder().getInventory();
        inventory.setItem(0, new TestStack("stolen"));
        inventory.setItem(5, new TestStack("junk"));
        session.refreshMenu();
        assertEquals("a@shop:0", kit.at(steve, 0));
        assertNull(kit.at(steve, 5));
    }

    /** Trampa 20 (menu side): a bound stack is handed to the template's look as the base. */
    @Test
    void boundStacksAreTheBase() {
        Gui gui = kit.gui("shop", "templates:\n  t: {display-name: 'Sell'}\n");
        GuiSession session = open(gui, steve);
        TestStack plugin = new TestStack("sword");
        session.bind(2, gui.template("t"), plugin, Ph.of("p", "5"));
        assertEquals("t[p=5]+over(sword)", kit.shown(steve, 2));
    }

    /** Trampa 24 (menu side): a head that waited for its skin is painted again when the skin arrives. */
    @Test
    void skinsArrivingRepaintTheirCell() {
        Gui gui = kit.gui("shop", "items:\n  head: {material: PLAYER_HEAD, slots: [3], skull-owner: '%vip%'}\n");
        open(gui, steve);
        assertEquals(1, kit.skinRefreshes.size());
        int builds = kit.builds;
        kit.skinRefreshes.get(0).accept("Notch");
        assertEquals(builds + 1, kit.builds, "the cell was rendered again");
        kit.closeByPlayer(steve);
        kit.skinRefreshes.get(0).accept("Notch");
        assertEquals(builds + 1, kit.builds, "nothing for a closed menu");
    }

    @Test
    void timers() {
        Gui gui = kit.gui("shop", """
                update-interval: 20
                items:
                  clock: {material: CLOCK, slots: [0], update-interval: 10, display-name: '%vip%'}
                """);
        GuiSession session = open(gui, steve);
        assertEquals(2, kit.liveTimers());
        int builds = kit.builds;
        kit.fireTimers();
        assertTrue(kit.builds > builds);
        kit.open.remove(steve.id);
        kit.fireTimers();
        assertTrue(session.closed(), "a menu that is no longer shown stops by itself");
        assertEquals(0, kit.liveTimers());
    }

    /** Calls from another thread are moved to the player's thread. */
    @Test
    void callsElsewhereAreMoved() {
        Gui gui = kit.gui("shop", "templates:\n  t: {material: STONE}\n");
        GuiSession session = open(gui, steve);
        kit.owns = false;
        session.bind(1, gui.template("t"));
        gui.open(alex.player);
        assertNull(kit.at(steve, 1));
        assertNull(gui.session(alex.player));
        kit.owns = true;
        kit.tick();
        assertEquals("t", kit.shown(steve, 1));
        assertNotNull(gui.session(alex.player));
    }

    /** Trampa 22: ids with a namespace work for the manager and in the stamp. */
    @Test
    void namespacedIds() {
        Gui gui = kit.gui("party:shop", "items:\n  a: {material: STONE, slots: [0]}\n");
        assertSame(gui, kit.manager.get("party:shop"));
        open(gui, steve);
        assertEquals("a@party:shop:0", kit.at(steve, 0));
        GuiSession session = gui.session(steve.player);
        assertEquals("party:shop", session.holder().guiId());
    }

    /** A reload swaps the definition under the same Gui; open sessions keep theirs until they reopen. */
    @Test
    void reloadKeepsTheGui() {
        Gui gui = kit.gui("shop", "items:\n  a: {material: STONE, slots: [0]}\n");
        GuiSession old = open(gui, steve);
        Gui same = kit.gui("shop", "items:\n  b: {material: DIRT, slots: [0]}\n");
        assertSame(gui, same);
        assertEquals("a", kit.shown(steve, 0));
        old.close();
        open(gui, steve);
        assertEquals("b", kit.shown(steve, 0));
    }

    /** Trampa 25: "no menus" waits one tick, so folders a plugin adds in its start still count. */
    @Test
    void theNoMenusWarningWaitsATick() {
        GuiKit empty = new GuiKit();
        empty.manager.checkNotEmpty();
        assertTrue(empty.logged.isEmpty());
        empty.tick();
        assertTrue(empty.logged.stream().anyMatch(w -> w.contains("No menus were loaded")), empty.logged.toString());

        GuiKit filled = new GuiKit();
        filled.manager.checkNotEmpty();
        filled.gui("late", "items: {}\n");
        filled.tick();
        filled.manager.checkNotEmpty();
        filled.tick();
        assertTrue(filled.logged.isEmpty(), filled.logged.toString());
    }

    @Test
    void bindMistakesAreReported() {
        Gui gui = kit.gui("shop", "templates:\n  free: {material: STONE}\n");
        GuiSession session = open(gui, steve);
        session.bind("nope");
        session.bind("free");
        session.bind(99, gui.template("free"));
        session.bindEach("nope", List.of(), (v, e) -> { });
        List<String> logged = kit.logged;
        assertTrue(logged.stream().anyMatch(w -> w.contains("no template with that id")), logged.toString());
        assertTrue(logged.stream().anyMatch(w -> w.contains("declares no slots or key")), logged.toString());
        assertTrue(logged.stream().anyMatch(w -> w.contains("has no cell 99")), logged.toString());
        assertTrue(logged.stream().anyMatch(w -> w.contains("no region with that id")), logged.toString());
    }
}
