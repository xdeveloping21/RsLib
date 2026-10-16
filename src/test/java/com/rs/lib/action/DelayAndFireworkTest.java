package com.rs.lib.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

/** {@code [delay]}: reading the time, waiting, leaving and shutting down; {@code [firework]} options. */
class DelayAndFireworkTest {

    private FakeActionHost host;
    private ActionEngine engine;
    private TestPlayer steve;
    private final List<String> marks = new ArrayList<>();
    private final List<String> warned = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new FakeActionHost();
        engine = new ActionEngine(host);
        steve = new TestPlayer();
        engine.register("mark", (player, arg, context) -> marks.add(arg));
    }

    private void run(String... lines) {
        engine.run(steve.player, List.of(lines));
    }

    private long warningsWith(String text) {
        return host.warnings.stream().filter(w -> w.contains(text)).count();
    }

    // ---------------------------------------------------------------- reading the time

    @Test
    void aWholeNumberIsTicksAndAnythingElseADuration() {
        assertEquals(20, DelayAction.ticks("20", warned::add));
        assertEquals(20, DelayAction.ticks(" 1s ", warned::add));
        assertEquals(10, DelayAction.ticks("0.5s", warned::add));
        assertEquals(1800, DelayAction.ticks("1m30s", warned::add));
        assertEquals(10, DelayAction.ticks("10t", warned::add));
        assertEquals(1, DelayAction.ticks("1ms", warned::add));
        assertEquals(2, DelayAction.ticks("51ms", warned::add));
        assertEquals(0, DelayAction.ticks("0", warned::add));
        assertEquals(0, DelayAction.ticks("0s", warned::add));
        assertEquals(Long.MAX_VALUE, DelayAction.ticks("99999999999999999999", warned::add));
        assertEquals(List.of(), warned);
    }

    @Test
    void aTimeThatCannotBeReadIsReportedAndWaitsNothing() {
        for (String bad : List.of("", "soon", "5x", "-")) {
            warned.clear();
            assertEquals(0, DelayAction.ticks(bad, warned::add), bad);
            assertEquals(1, warned.size(), bad);
        }
    }

    // ---------------------------------------------------------------- waiting

    @Test
    void theLinesAfterADelayWaitForIt() {
        run("[mark] a", "[delay] 40", "[mark] b", "[delay] 1s", "[mark] c");
        assertEquals(List.of("a"), marks);
        assertEquals(1, host.delayed.size());
        assertEquals(40, host.delayed.get(0).ticks);
        assertEquals(steve.player, host.delayed.get(0).player);
        assertEquals(1, engine.waitingLists());
        host.delayed.remove(0).task.run();
        assertEquals(List.of("a", "b"), marks);
        assertEquals(20, host.delayed.get(0).ticks);
        host.runDelayed();
        assertEquals(List.of("a", "b", "c"), marks);
        assertEquals(0, engine.waitingLists());
    }

    @Test
    void placeholdersAndGuardsApplyToTheDelay() {
        engine.run(steve.player, List.of("[delay] {wait}", "[mark] x"), ActionContext.of(steve.player, null,
                Ph.of("wait", "3s")));
        assertEquals(60, host.delayed.get(0).ticks);
        host.runDelayed();
        // A delay whose guard does not pass does not wait.
        engine.run(steve.player, List.of("[right-click] [delay] 5", "[mark] y"),
                new ActionContext(steve.player, null, null, ClickType.LEFT, null, null));
        assertEquals(List.of("x", "y"), marks);
        assertEquals(0, host.delayed.size());
    }

    @Test
    void aZeroOrUnreadableOrLastDelaySchedulesNothing() {
        run("[mark] a", "[delay] 0", "[mark] b", "[delay] later", "[mark] c", "[delay] 20");
        assertEquals(List.of("a", "b", "c"), marks);
        assertEquals(0, host.delayed.size());
        run("[delay] later", "[mark] d");
        assertEquals(1, warningsWith("[delay] later is not a time"));
    }

    @Test
    void thePlayerLeavingDropsTheRest() {
        TestPlayer alex = new TestPlayer();
        run("[delay] 20", "[mark] steve");
        engine.run(alex.player, List.of("[delay] 20", "[mark] alex"));
        assertEquals(2, engine.waitingLists());
        host.quit(steve.player);
        assertEquals(1, engine.waitingLists());
        host.runDelayed();
        assertEquals(List.of("alex"), marks);
    }

    @Test
    void aPlayerWhoIsGoneWhenTheWaitEndsGetsNothing() {
        run("[delay] 20", "[mark] late");
        steve.online = false;
        host.runDelayed();
        assertEquals(List.of(), marks);
        assertEquals(0, engine.waitingLists());
    }

    @Test
    void shuttingDownDropsEveryWait() {
        run("[delay] 20", "[mark] late");
        engine.run(null, List.of("[delay] 20", "[mark] console"));
        assertEquals(2, engine.waitingLists());
        engine.shutdown();
        assertEquals(0, engine.waitingLists());
        assertEquals(0, host.runDelayed());
        assertEquals(List.of(), marks);
    }

    @Test
    void aWaitThatCannotBeScheduledIsDropped() {
        host.owns = true;
        host.refuseScheduling = true;
        run("[mark] a", "[delay] 20", "[mark] b");
        assertEquals(List.of("a"), marks);
        assertEquals(0, engine.waitingLists());
    }

    @Test
    void aPluginCanReplaceTheDelay() {
        engine.register("delay", (player, arg, context) -> marks.add("own " + arg));
        run("[delay] 20", "[mark] b");
        assertEquals(List.of("own 20", "b"), marks);
        assertEquals(0, host.delayed.size());
    }

    // ---------------------------------------------------------------- fireworks

    @Test
    void fireworkOptionsAreRead() {
        FireworkAction.Parsed p = FireworkAction.parse(
                "type=star colors=#FF0000,#00FF00 fade=0,0,255 power=2", warned::add);
        assertEquals(List.of(), warned);
        assertEquals(FireworkEffect.Type.STAR, p.effect().getType());
        assertEquals(List.of(Color.fromRGB(0xFF0000), Color.fromRGB(0x00FF00)), p.effect().getColors());
        assertEquals(List.of(Color.fromRGB(0, 0, 255)), p.effect().getFadeColors());
        assertEquals(2, p.power());
        p = FireworkAction.parse("TYPE=ball-large color=255,255,0;#000000 power=0", warned::add);
        assertEquals(FireworkEffect.Type.BALL_LARGE, p.effect().getType());
        assertEquals(List.of(Color.fromRGB(255, 255, 0), Color.BLACK), p.effect().getColors());
        assertEquals(0, p.power());
        assertEquals(List.of(), warned);
    }

    @Test
    void fireworkDefaults() {
        FireworkAction.Parsed p = FireworkAction.parse("", warned::add);
        assertEquals(FireworkEffect.Type.BALL, p.effect().getType());
        assertEquals(List.of(Color.WHITE), p.effect().getColors());
        assertEquals(List.of(), p.effect().getFadeColors());
        assertEquals(FireworkAction.DEFAULT_POWER, p.power());
        assertEquals(List.of(), warned);
    }

    @Test
    void badFireworkOptionsAreReportedAndKeepTheirDefaults() {
        FireworkAction.Parsed p = FireworkAction.parse(
                "big type=round colors=#GG0000 fade=1,2 power=300 sparkle=yes power=1.5", warned::add);
        assertEquals(FireworkEffect.Type.BALL, p.effect().getType());
        assertEquals(List.of(Color.WHITE), p.effect().getColors());
        assertEquals(List.of(), p.effect().getFadeColors());
        assertEquals(FireworkAction.DEFAULT_POWER, p.power());
        assertEquals(7, warned.size(), warned.toString());
        assertTrue(warned.get(0).contains("'big'"), warned.get(0));
    }

    @Test
    void colorListsSplitOnSemicolonsOrBetweenHexColors() {
        assertEquals(List.of("#a", "#b"), FireworkAction.colorParts("#a,#b"));
        assertEquals(List.of("1,2,3"), FireworkAction.colorParts("1,2,3"));
        assertEquals(List.of("1,2,3", "#b"), FireworkAction.colorParts("1,2,3;#b"));
    }

    @Test
    void aFireworkProblemIsReportedOncePerArgument() {
        TestPlayer nobody = new TestPlayer();
        // Without a world the rocket cannot spawn: the line fails, but parsing reported once.
        engine.run(nobody.player, List.of("[firework] type=round", "[firework] type=round"));
        assertEquals(1, warningsWith("type 'round'"));
    }
}
