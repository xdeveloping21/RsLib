package com.rs.lib.bossbar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.scheduler.TaskHandle;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class BossBarUtilTest {

    private final List<String> warnings = new ArrayList<>();
    private final List<Timer> timers = new ArrayList<>();
    private long now;
    private BossBarUtil bars;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        bars = new BossBarUtil(logger, (period, task) -> {
            Timer timer = new Timer(period, task);
            timers.add(timer);
            return timer;
        }, () -> now);
    }

    @Test
    void buildUsesTheDefaultsAndStartsWithoutViewers() {
        BossBar bar = bars.create("a").build();

        assertEquals("", PlainTextComponentSerializer.plainText().serialize(bar.name()));
        assertEquals(1f, bar.progress());
        assertEquals(BossBar.Color.WHITE, bar.color());
        assertEquals(BossBar.Overlay.PROGRESS, bar.overlay());
        assertTrue(bars.exists("a"));
        assertFalse(bars.exists("b"));
    }

    @Test
    void builderSettingsAndColoredText() {
        BossBar bar = bars.create("a").text("&cRestart &lsoon").progress(0.25f)
                .color(BossBar.Color.RED).overlay(BossBar.Overlay.NOTCHED_10).build();

        assertEquals("Restart soon", PlainTextComponentSerializer.plainText().serialize(bar.name()));
        assertEquals(0.25f, bar.progress());
        assertEquals(BossBar.Color.RED, bar.color());
        assertEquals(BossBar.Overlay.NOTCHED_10, bar.overlay());
    }

    @Test
    void progressIsAlwaysWithinZeroAndOne() {
        assertEquals(1f, bars.create("a").progress(5f).build().progress());
        assertEquals(0f, bars.create("b").progress(-1f).build().progress());
        bars.setProgress("a", Float.NaN);
        assertEquals(0f, bars.bar("a").progress());
        bars.setProgress("a", 0.5f);
        assertEquals(0.5f, bars.bar("a").progress());
    }

    @Test
    void rebuildingAnIdHidesTheOldBarFromItsViewers() {
        TestPlayer steve = new TestPlayer();
        BossBar old = bars.create("a").build();
        bars.show(steve.player, "a");
        BossBar fresh = bars.create("a").build();

        assertNotSame(old, fresh);
        assertFalse(steve.showing.contains(old));
        assertFalse(steve.showing.contains(fresh), "the new bar starts without viewers");
    }

    @Test
    void showHideAndText() {
        TestPlayer steve = new TestPlayer();
        BossBar bar = bars.create("a").build();
        bars.show(steve.player, "a");
        assertTrue(steve.showing.contains(bar));
        bars.setText("a", "&a5 minutes");
        assertEquals("5 minutes", PlainTextComponentSerializer.plainText().serialize(bar.name()));
        bars.hide(steve.player, "a");
        assertFalse(steve.showing.contains(bar));
    }

    @Test
    void countdownTimerFollowsTheRealClockEveryTwoTicksAndStopsVisible() {
        TestPlayer steve = new TestPlayer();
        BossBar bar = bars.create("a").build();
        bars.show(steve.player, "a");
        bars.timer("a", Duration.ofSeconds(10), true);
        Timer timer = timers.get(0);
        assertEquals(BossBarUtil.TIMER_PERIOD_TICKS, timer.period);
        assertEquals(1f, bar.progress());

        now = Duration.ofSeconds(4).toNanos();
        timer.fire();
        assertEquals(0.6f, bar.progress(), 1e-6);
        now = Duration.ofSeconds(30).toNanos();
        timer.fire();
        assertEquals(0f, bar.progress());
        assertTrue(timer.cancelled, "stops itself at the end");
        assertTrue(steve.showing.contains(bar), "and leaves the bar visible");
    }

    @Test
    void countUpTimerGoesFromZeroToOne() {
        BossBar bar = bars.create("a").build();
        bars.timer("a", Duration.ofSeconds(2), false);
        assertEquals(0f, bar.progress());
        now = Duration.ofSeconds(1).toNanos();
        timers.get(0).fire();
        assertEquals(0.5f, bar.progress(), 1e-6);
    }

    @Test
    void aZeroDurationSetsTheEndAtOnce() {
        BossBar bar = bars.create("a").build();
        bars.timer("a", Duration.ZERO, true);
        assertEquals(0f, bar.progress());
        bars.timer("a", null, false);
        assertEquals(1f, bar.progress());
        assertTrue(timers.isEmpty());
    }

    @Test
    void trapSetProgressDuringATimerIsOverwrittenOnTheNextStep() {
        BossBar bar = bars.create("a").build();
        bars.timer("a", Duration.ofSeconds(10), true);
        now = Duration.ofSeconds(5).toNanos();
        bars.setProgress("a", 0.9f);
        assertEquals(0.9f, bar.progress());
        timers.get(0).fire();
        assertEquals(0.5f, bar.progress(), 1e-6);

        bars.cancelTimer("a");
        bars.setProgress("a", 0.9f);
        timers.get(0).fire();
        assertEquals(0.9f, bar.progress(), "a cancelled timer no longer moves it");
    }

    @Test
    void aNewTimerReplacesTheOldOne() {
        bars.create("a").build();
        bars.timer("a", Duration.ofSeconds(10), true);
        bars.timer("a", Duration.ofSeconds(5), false);
        assertTrue(timers.get(0).cancelled);
        assertFalse(timers.get(1).cancelled);
    }

    @Test
    void aPlayerWhoLeavesIsTakenOffEveryBar() {
        TestPlayer steve = new TestPlayer();
        TestPlayer alex = new TestPlayer();
        BossBar a = bars.create("a").build();
        BossBar b = bars.create("b").build();
        bars.show(steve.player, "a");
        bars.show(steve.player, "b");
        bars.show(alex.player, "a");

        bars.forget(steve.player);
        assertTrue(steve.showing.isEmpty());
        assertTrue(alex.showing.contains(a));
        bars.remove("a");
        assertFalse(alex.showing.contains(a));
        assertFalse(steve.showing.contains(b));
    }

    @Test
    void hideAllKeepsTheBarsButNotTheirViewersOrTimers() {
        TestPlayer steve = new TestPlayer();
        bars.create("a").build();
        bars.show(steve.player, "a");
        bars.timer("a", Duration.ofSeconds(10), true);

        bars.hideAll();
        assertTrue(steve.showing.isEmpty());
        assertTrue(timers.get(0).cancelled);
        assertTrue(bars.exists("a"));
    }

    @Test
    void removeForgetsTheBar() {
        bars.create("a").build();
        bars.remove("a");
        assertFalse(bars.exists("a"));
        assertNull(bars.bar("a"));
    }

    @Test
    void unknownIdsWarnOncePerId() {
        TestPlayer steve = new TestPlayer();
        bars.show(steve.player, "x");
        bars.setText("x", "a");
        bars.remove("x");
        bars.timer("y", Duration.ofSeconds(1), true);

        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(steve.showing.isEmpty());
    }

    @Test
    void fractionIsWithinZeroAndOne() {
        assertEquals(0f, BossBarUtil.Animation.fraction(-5, 10));
        assertEquals(0.5f, BossBarUtil.Animation.fraction(5, 10));
        assertEquals(1f, BossBarUtil.Animation.fraction(15, 10));
        assertEquals(1f, BossBarUtil.Animation.fraction(0, 0));
    }

    private static final class Timer implements TaskHandle {

        final long period;
        final Runnable task;
        boolean cancelled;

        Timer(long period, Runnable task) {
            this.period = period;
            this.task = task;
        }

        void fire() {
            if (!cancelled) {
                task.run();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static final class TestPlayer {

        final UUID id = UUID.randomUUID();
        final Set<BossBar> showing = new HashSet<>();
        final Player player;

        TestPlayer() {
            player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> id;
                        case "showBossBar" -> {
                            showing.add((BossBar) args[0]);
                            yield null;
                        }
                        case "hideBossBar" -> {
                            showing.remove((BossBar) args[0]);
                            yield null;
                        }
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
