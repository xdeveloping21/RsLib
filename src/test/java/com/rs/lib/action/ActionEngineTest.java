package com.rs.lib.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

import net.kyori.adventure.title.Title;

/** Line anatomy, guards, placeholders, built-in actions and the notes' traps, without a server. */
class ActionEngineTest {

    private FakeActionHost host;
    private ActionEngine engine;
    private TestPlayer steve;
    private final List<String> marks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new FakeActionHost();
        engine = new ActionEngine(host);
        steve = new TestPlayer();
        engine.register("mark", (player, arg, context) -> marks.add(arg));
    }

    private ActionContext click(ClickType type) {
        return new ActionContext(steve.player, null, null, type, null, null);
    }

    private void run(ActionContext context, String... lines) {
        engine.run(steve.player, List.of(lines), context);
    }

    private long warningsWith(String text) {
        return host.warnings.stream().filter(w -> w.contains(text)).count();
    }

    /** Trap 1: a line without a tag is a [message]. */
    @Test
    void untaggedLineIsAMessage() {
        assertEquals(List.of("message", "console", "sound", "ah-buy"), engine.effectiveTags(List.of(
                "&7Hello", "[right-click] [chance=50] [console] say hi", "", "[Sound] UI_BUTTON_CLICK", "[ah-buy] {id}")));
        engine.run(steve.player, List.of("&aHi {name}"), Ph.of("name", "Bob"));
        assertEquals(List.of("Hi Bob"), steve.texts());
    }

    /** Trap 3: [right-click]/[left-click] are inclusive; [drop-click] is DROP only. */
    @Test
    void inclusiveAndExactClickGuards() {
        String[] lines = {"[right-click] [mark] r", "[left-click] [mark] l", "[drop-click] [mark] d",
            "[right-click-only] [mark] ro", "[left-click-only] [mark] lo", "[shift-right-click] [mark] sr",
            "[shift-left-click] [mark] sl", "[middle-click] [mark] m", "[double-click] [mark] dc",
            "[number-key] [mark] n", "[swap-offhand] [mark] so"};
        expect(ClickType.RIGHT, lines, "r", "ro");
        expect(ClickType.SHIFT_RIGHT, lines, "r", "sr");
        expect(ClickType.LEFT, lines, "l", "lo");
        expect(ClickType.SHIFT_LEFT, lines, "l", "sl");
        expect(ClickType.DOUBLE_CLICK, lines, "l", "dc");
        expect(ClickType.CREATIVE, lines, "l");
        expect(ClickType.DROP, lines, "d");
        expect(ClickType.CONTROL_DROP, lines);
        expect(ClickType.MIDDLE, lines, "m");
        expect(ClickType.NUMBER_KEY, lines, "n");
        expect(ClickType.SWAP_OFFHAND, lines, "so");
        assertTrue(host.warnings.isEmpty(), host.warnings.toString());
    }

    private void expect(ClickType type, String[] lines, String... ran) {
        marks.clear();
        run(click(type), lines);
        assertEquals(List.of(ran), marks, type.name());
    }

    /** Trap 2: [click=...] fails closed, [chance=...] fails open; both are reported once. */
    @Test
    void clickNamesFailClosedAndChanceFailsOpen() {
        for (int i = 0; i < 3; i++) {
            run(click(ClickType.MIDDLE), "[click=shift-right, MIDDLE] [mark] a", "[click=PUNCH] [mark] b",
                    "[chance=lots] [mark] c", "[chance=0] [mark] z", "[chance=100] [mark] y", "[CHANCE=50%] [mark] h");
        }
        assertEquals(9, marks.stream().filter(m -> !m.equals("h")).count(), marks.toString());
        assertEquals(List.of("a", "c", "y"), marks.stream().filter(m -> !m.equals("h")).distinct().toList());
        assertEquals(1, warningsWith("'PUNCH'"), host.warnings.toString());
        assertEquals(1, warningsWith("[chance=lots]"), host.warnings.toString());
        assertEquals(2, host.warnings.size(), host.warnings.toString());
    }

    /** Trap 4: [click-block]/[click-air] never run inside a menu (no surface). */
    @Test
    void surfaceGuardsNeverRunInMenus() {
        run(click(ClickType.RIGHT), "[click-block] [mark] b", "[click-air] [mark] a", "[mark] always");
        assertEquals(List.of("always"), marks);
        marks.clear();
        run(new ActionContext(steve.player, null, null, ClickType.RIGHT, ClickSurface.BLOCK, null),
                "[click-block] [mark] b", "[right-click] [click-air] [mark] a");
        assertEquals(List.of("b"), marks);
    }

    @Test
    void clickGuardsSkipWithoutAClick() {
        run(ActionContext.of(steve.player, null), "[left-click] [mark] x", "[click=LEFT] [mark] y", "[mark] z");
        assertEquals(List.of("z"), marks);
        assertEquals(2, host.debug.size(), host.debug.toString());
    }

    @Test
    void guardsChain() {
        run(click(ClickType.RIGHT), "[right-click] [chance=100] [mark] x");
        run(click(ClickType.LEFT), "[right-click] [chance=100] [mark] y");
        assertEquals(List.of("x"), marks);
    }

    /** Trap 5: a failing line is reported once and the next line still runs. */
    @Test
    void aFailingLineIsReportedOnceAndTheNextRuns() {
        engine.register("boom", (player, arg, context) -> {
            throw new IllegalStateException("kaput");
        });
        for (int i = 0; i < 3; i++) {
            run(ActionContext.of(steve.player, null), "[boom] now", "[mark] after");
        }
        assertEquals(List.of("after", "after", "after"), marks);
        assertEquals(1, warningsWith("'[boom] now' failed"), host.warnings.toString());
    }

    @Test
    void unknownTagsAreReportedOnce() {
        run(ActionContext.of(steve.player, null), "[nope] x", "[mark] y");
        run(ActionContext.of(steve.player, null), "[NOPE] x");
        assertEquals(List.of("y"), marks);
        assertEquals(1, warningsWith("Unknown action [nope]"), host.warnings.toString());
    }

    /** Trap 6: local placeholders are filled in before PlaceholderAPI sees the text. */
    @Test
    void localsBeforePapi() {
        host.papi = text -> text.replace("%player_name%", "Steve").replace("%vault_rank%", "VIP");
        engine.run(steve.player, List.of("[mark] {id} %player_name%", "[mark] %rank%"),
                Ph.of("id", "5"), Ph.of("rank", "%vault_rank%"));
        assertEquals(List.of("5 %player_name%", "%vault_rank%"), host.papiCalls);
        assertEquals(List.of("5 Steve", "VIP"), marks);
        host.papiCalls.clear();
        engine.run(steve.player, List.of("[mark] 50% off", "[mark] plain"));
        assertTrue(host.papiCalls.isEmpty(), "a single % or no placeholder never reaches PlaceholderAPI");
    }

    @Test
    void literalValuesStayPlain() {
        Ph typed = Ph.literal("name", "&c<red>Bob %player_name%");
        engine.run(steve.player, List.of("[mark] {name}", "[message] Hi {name}"), typed);
        assertEquals(List.of("&c<red>Bob %player_name%"), marks);
        assertEquals(List.of("Hi &c<red>Bob %player_name%"), steve.texts());
        assertTrue(host.papiCalls.isEmpty(), host.papiCalls.toString());
    }

    /** Trap 7: [player-as-op] always takes the operator rights back. */
    @Test
    void playerAsOpAlwaysTakesOpBack() {
        steve.commandFailure = new IllegalStateException("command broke");
        run(ActionContext.of(steve.player, null), "[player-as-op] /spawn", "[mark] next");
        assertEquals(List.of("spawn (op)"), steve.commands);
        assertFalse(steve.op);
        assertEquals(List.of("next"), marks);
        steve.commandFailure = null;
        steve.op = true;
        run(ActionContext.of(steve.player, null), "[player-as-op] fly");
        assertTrue(steve.op, "an operator stays one");
    }

    @Test
    void commands() {
        run(ActionContext.of(steve.player, null), "[player] /home", "[player] ", "[console] /say hi {who}",
                "[console]");
        assertEquals(List.of("home"), steve.commands);
        assertEquals(List.of("say hi {who}"), host.console);
        assertEquals(1, warningsWith("[player] has no command"), host.warnings.toString());
        assertEquals(1, warningsWith("[console] has no command"), host.warnings.toString());
    }

    /** Trap 8: [open] opens at once, inside the click; nothing opens during shutdown. */
    @Test
    void openRunsInlineAndNeverDuringShutdown() {
        List<String> opened = new ArrayList<>();
        run(ActionContext.of(steve.player, null), "[open] shop");
        assertEquals(1, warningsWith("has no menus"), host.warnings.toString());
        engine.menuOpener((player, id, context) -> opened.add(id) && !id.equals("ghost"));
        run(click(ClickType.LEFT), "[open] party:shop", "[open] ghost", "[open] ghost");
        assertEquals(List.of("party:shop", "ghost", "ghost"), opened);
        assertEquals(1, warningsWith("no menu with that id"), host.warnings.toString());
        host.shuttingDown = true;
        run(click(ClickType.LEFT), "[open] shop");
        assertEquals(3, opened.size());
    }

    /** Trap 9: registered tags can replace built-in ones; tags are normalized. */
    @Test
    void registeredTagsReplaceBuiltins() {
        engine.register("[MESSAGE]", (player, arg, context) -> marks.add("custom:" + arg));
        run(ActionContext.of(steve.player, null), "&aplain line", "[Message] tagged");
        assertEquals(List.of("custom:&aplain line", "custom:tagged"), marks);
        assertTrue(steve.messages.isEmpty());
        assertTrue(engine.hasHandler("message"));
        assertTrue(engine.hasHandler("[Mark]"));
        assertFalse(engine.hasHandler("unknown"));
        assertFalse(engine.hasHandler(null));
        assertThrows(IllegalArgumentException.class, () -> engine.register(" [] ", (p, a, c) -> { }));
    }

    /** Raul's rule: [remove-item] with an amount of 0 or less warns and touches nothing. */
    @Test
    void removeItemWithoutAPositiveAmountDoesNothing() {
        run(ActionContext.of(steve.player, null), "[remove-item] 0", "[remove-item] -3 DIAMOND", "[remove-item] 1.5");
        assertTrue(steve.touched.isEmpty(), "the inventory was touched: " + steve.touched);
        assertEquals(2, warningsWith("must be 1 or more"), host.warnings.toString());
        assertEquals(1, warningsWith("not a whole amount"), host.warnings.toString());
        assertEquals(0, warningsWith("failed"), host.warnings.toString());
    }

    @Test
    void removeItemArguments() {
        List<String> warnings = new ArrayList<>();
        assertEquals(new RemoveItemAction.Spec(1, RemoveItemAction.Where.MAIN_HAND, null),
                RemoveItemAction.parse("", warnings::add));
        assertEquals(new RemoveItemAction.Spec(4, RemoveItemAction.Where.MAIN_HAND, null),
                RemoveItemAction.parse("4", warnings::add));
        assertEquals(new RemoveItemAction.Spec(1, RemoveItemAction.Where.OFF_HAND, null),
                RemoveItemAction.parse("OffHand", warnings::add));
        assertEquals(new RemoveItemAction.Spec(3, RemoveItemAction.Where.ITEM_ID, "magic_sword"),
                RemoveItemAction.parse("3 id:magic_sword", warnings::add));
        assertEquals(new RemoveItemAction.Spec(2, RemoveItemAction.Where.MATERIAL, "DIAMOND"),
                RemoveItemAction.parse("2 diamond", warnings::add));
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertNull(RemoveItemAction.parse("id:", warnings::add));
        assertNull(RemoveItemAction.parse("0 offhand", warnings::add));
        assertEquals(2, warnings.size());
    }

    @Test
    void runsOnThePlayersThread() {
        host.owns = false;
        run(ActionContext.of(steve.player, null), "[mark] later");
        assertTrue(marks.isEmpty(), "ran on the wrong thread");
        host.tick();
        assertEquals(List.of("later"), marks);
        host.refuseScheduling = true;
        run(ActionContext.of(steve.player, null), "[mark] never");
        assertEquals(1, warningsWith("were dropped"), host.warnings.toString());
        host.refuseScheduling = false;
        steve.online = false;
        run(ActionContext.of(steve.player, null), "[mark] gone");
        host.tick();
        assertEquals(List.of("later"), marks);
    }

    @Test
    void pageActionsNeedPagination() {
        FakePages pages = new FakePages();
        ActionContext context = new ActionContext(steve.player, null, pages, ClickType.LEFT, null);
        run(context, "[next-page]", "[previous-page]", "[set-page] 3", "[refresh-page]", "[refresh-menu]");
        assertTrue(pages.calls.isEmpty());
        pages.enabled = true;
        run(context, "[next-page]", "[previous-page]", "[set-page] 3", "[set-page] three", "[set-page] 0",
                "[refresh-page]", "[refresh-menu]");
        assertEquals(List.of("next", "previous", "set 3", "set 1", "set 1", "refresh-page", "refresh-menu"), pages.calls);
        assertEquals(2, warningsWith("is not a page number"), host.warnings.toString());
        pages.calls.clear();
        run(ActionContext.of(steve.player, null), "[next-page]");
        assertTrue(pages.calls.isEmpty());
    }

    @Test
    void titlesUseDefaultTimesForEmptyParts() {
        List<String> warnings = new ArrayList<>();
        Title title = Builtins.title("&aHi;&7Sub;5;;x", true, (id, message) -> warnings.add(message.get()));
        assertEquals(Duration.ofMillis(250), title.times().fadeIn());
        assertEquals(Duration.ofMillis(70 * 50), title.times().stay());
        assertEquals(Duration.ofMillis(20 * 50), title.times().fadeOut());
        assertEquals(1, warnings.size(), warnings.toString());
        run(ActionContext.of(steve.player, null), "[title] &eWelcome {name}");
        assertEquals(Duration.ofMillis(500), steve.title.times().fadeIn());
    }

    @Test
    void compiledListsLookUpTagsWhenTheyRun() {
        ActionList list = engine.compile(List.of("[later-tag] x", "  ", "[right-click] [mark] y"), "guis/test.yml, items.a");
        assertEquals(List.of("later-tag", "mark"), list.tags());
        assertEquals(3, list.lines().size());
        assertFalse(list.isEmpty());
        assertTrue(engine.compile(List.of(" ", "")).isEmpty());
        assertTrue(engine.compile(null).isEmpty());
        engine.register("later-tag", (player, arg, context) -> marks.add("late:" + arg));
        list.run(new ActionContext(steve.player, null, null, ClickType.RIGHT, null, null));
        assertEquals(List.of("late:x", "y"), marks);
    }

    @Test
    void compileProblemsNameTheSourceOnce() {
        for (int i = 0; i < 3; i++) {
            engine.compile(List.of("[click=nope] [mark] a", "[left-click]"), "guis/test.yml, items.a.click-actions");
            engine.run(steve.player, List.of("[click=nope] [mark] a"), ActionContext.of(steve.player, null));
        }
        // Each problem once per place it was read from: twice from the menu file, once from run().
        assertEquals(2, warningsWith("(in guis/test.yml, items.a.click-actions)"), host.warnings.toString());
        assertEquals(3, host.warnings.size(), host.warnings.toString());
    }

    @Test
    void nothingRunsAfterShutdown() {
        ActionList list = engine.compile(List.of("[mark] x"));
        engine.shutdown();
        list.run(steve.player);
        engine.run(steve.player, List.of("[mark] y"));
        assertTrue(marks.isEmpty());
    }

    @Test
    void particleArguments() {
        List<String> warnings = new ArrayList<>();
        ParticleAction.Parsed full = ParticleAction.parse("DUST 10 0.1 0.2 0.3 0.05 color=#FF0000 size=2", warnings::add);
        assertEquals(10, full.count());
        assertEquals(0.1, full.offX());
        assertEquals(0.3, full.offZ());
        assertEquals(0.05, full.extra());
        assertEquals("#FF0000", full.options().get("color"));
        ParticleAction.Parsed three = ParticleAction.parse("FLAME 5 1 2", warnings::add);
        assertEquals(0, three.offX(), "offsets need four numbers after the type");
        ParticleAction.Parsed four = ParticleAction.parse("FLAME 5 1 2 3", warnings::add);
        assertEquals(1, four.offX());
        assertEquals(3, four.offZ());
        assertEquals(0, four.extra(), "the extra needs five numbers");
        assertEquals(1, ParticleAction.parse("heart", warnings::add).count());
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertNull(ParticleAction.parse("  ", warnings::add));
        assertEquals(org.bukkit.Color.fromRGB(255, 0, 0), ParticleAction.color("255, 0,0", null));
        assertEquals(org.bukkit.Color.fromRGB(0x12AB34), ParticleAction.color("#12ab34", null));
        assertThrows(IllegalArgumentException.class, () -> ParticleAction.color("red", null));
    }

    @Test
    void particleNamesOldAndNew() {
        assertEquals(org.bukkit.Particle.valueOf("DUST"), ParticleAction.particle("REDSTONE"));
        assertEquals(org.bukkit.Particle.valueOf("DUST"), ParticleAction.particle("minecraft:dust"));
        assertEquals(org.bukkit.Particle.valueOf("BLOCK"), ParticleAction.particle("block_crack"));
        assertNull(ParticleAction.particle("NOT_A_PARTICLE"));
    }

    @Test
    void potionArguments() {
        List<String> warnings = new ArrayList<>();
        assertEquals(new PotionAction.Parsed("speed", 200, 0), PotionAction.parse("speed", warnings::add));
        assertEquals(new PotionAction.Parsed("SPEED", 50, 1), PotionAction.parse("SPEED 2.5 1", warnings::add));
        assertTrue(warnings.isEmpty());
        assertEquals(new PotionAction.Parsed("speed", 200, 0), PotionAction.parse("speed soon one", warnings::add));
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void connectMessage() throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Builtins.connectMessage("lobby-1")));
        assertEquals("Connect", in.readUTF());
        assertEquals("lobby-1", in.readUTF());
    }

    @Test
    void closeAndSound() {
        run(ActionContext.of(steve.player, null), "[close]");
        assertTrue(steve.closed);
    }

    /** A page target that records calls. */
    private static final class FakePages implements PageTarget {
        final List<String> calls = new ArrayList<>();
        boolean enabled;

        @Override
        public void nextPage() {
            calls.add("next");
        }

        @Override
        public void previousPage() {
            calls.add("previous");
        }

        @Override
        public void setPage(int page) {
            calls.add("set " + page);
        }

        @Override
        public void refreshPage() {
            calls.add("refresh-page");
        }

        @Override
        public void refreshMenu() {
            calls.add("refresh-menu");
        }

        @Override
        public boolean paginationEnabled() {
            return enabled;
        }
    }
}
