package com.rs.lib.bossbar;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.rs.lib.Rs;
import com.rs.lib.scheduler.TaskHandle;
import com.rs.lib.text.RsText;

import net.kyori.adventure.bossbar.BossBar;

/**
 * Boss bars by id, reached with {@code rs().bossbars()}.
 *
 * <pre>
 * rs().bossbars().create("restart").text("&amp;cRestart in 5 minutes").color(BossBar.Color.RED).build();
 * rs().bossbars().timer("restart", Duration.ofMinutes(5), true);
 * Bukkit.getOnlinePlayers().forEach(p -&gt; rs().bossbars().show(p, "restart"));
 * </pre>
 *
 * <ul>
 *   <li>A bar starts with no viewers; {@link #show} and {@link #hide} add and take players.
 *   A player who leaves is taken off every bar of the plugin.</li>
 *   <li>Text goes through the full text pipeline ({@code &} codes, hex, MiniMessage).
 *   Progress is always kept within 0..1 (NaN counts as 0).</li>
 *   <li>{@link #timer} animates the progress against the real clock, every two ticks, and
 *   leaves the bar visible at its end value. {@link #setProgress} while a timer runs is
 *   overwritten on the timer's next step: cancel the timer first.</li>
 *   <li>An id that does not exist is reported once per id, as a warning.</li>
 * </ul>
 *
 * <p>Thread-safe. On shutdown {@link #hideAll()} takes every viewer off and stops the timers;
 * the bars stay registered.</p>
 */
public final class BossBarUtil {

    /** Ticks between two steps of a timer. */
    static final long TIMER_PERIOD_TICKS = 2L;

    /** Starts a repeating task on the main thread (global region on Folia); throws when it cannot. */
    @FunctionalInterface
    interface Timers {
        TaskHandle every(long periodTicks, Runnable task);
    }

    private final Map<String, Entry> bars = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Logger logger;
    private final Timers timers;
    private final LongSupplier nanoClock;

    /** Created by the context. */
    public BossBarUtil(Rs rs) {
        this(rs.plugin().getLogger(), (period, task) -> rs.scheduler().timer(period, period, task), System::nanoTime);
        rs.onQuit(this::forget);
    }

    BossBarUtil(Logger logger, Timers timers, LongSupplier nanoClock) {
        this.logger = logger;
        this.timers = timers;
        this.nanoClock = nanoClock;
    }

    /** Starts a bar; nothing exists until {@link Builder#build()}. */
    public Builder create(String id) {
        return new Builder(Objects.requireNonNull(id, "id"));
    }

    public boolean exists(String id) {
        return id != null && bars.containsKey(id);
    }

    /** Shows the bar to {@code player}; showing it twice is harmless. */
    public void show(Player player, String id) {
        Entry entry = known(id);
        if (entry != null && player != null) {
            entry.viewers.put(player.getUniqueId(), player);
            player.showBossBar(entry.bar);
        }
    }

    public void hide(Player player, String id) {
        Entry entry = known(id);
        if (entry != null && player != null) {
            entry.viewers.remove(player.getUniqueId());
            player.hideBossBar(entry.bar);
        }
    }

    /** New title, as written in a config (full text pipeline); null is empty. */
    public void setText(String id, String text) {
        Entry entry = known(id);
        if (entry != null) {
            entry.bar.name(RsText.colorUncached(text));
        }
    }

    /** New progress, kept within 0..1. A running timer overwrites it on its next step. */
    public void setProgress(String id, float progress) {
        Entry entry = known(id);
        if (entry != null) {
            entry.bar.progress(clamp(progress));
        }
    }

    /**
     * Animates the progress over {@code duration}: from 1 to 0 when {@code countdown}, from 0
     * to 1 otherwise. Replaces a timer already running on the bar. A zero, negative or null
     * duration sets the end value at once.
     */
    public void timer(String id, Duration duration, boolean countdown) {
        Entry entry = known(id);
        if (entry == null) {
            return;
        }
        long total = duration == null ? 0L : saturatedNanos(duration);
        Animation animation = new Animation(entry.bar, nanoClock, nanoClock.getAsLong(), total, countdown);
        synchronized (entry) {
            entry.stopTimer();
            if (total <= 0L) {
                entry.bar.progress(countdown ? 0f : 1f);
                return;
            }
            entry.bar.progress(countdown ? 1f : 0f);
            try {
                animation.handle = timers.every(TIMER_PERIOD_TICKS, animation);
            } catch (RuntimeException notScheduled) {
                logger.log(Level.WARNING, "Could not start the timer of boss bar '" + id + "'", notScheduled);
                return;
            }
            entry.timer = animation.handle;
        }
    }

    /** Stops the bar's timer; the progress stays where it is. */
    public void cancelTimer(String id) {
        Entry entry = known(id);
        if (entry != null) {
            synchronized (entry) {
                entry.stopTimer();
            }
        }
    }

    /** Hides the bar from everyone, stops its timer and forgets it. */
    public void remove(String id) {
        Entry entry = id == null ? null : bars.remove(id);
        if (entry == null) {
            warnUnknown(id);
            return;
        }
        entry.close();
    }

    /** Takes every viewer off every bar and stops every timer; the bars stay registered. */
    public void hideAll() {
        for (Entry entry : bars.values()) {
            entry.close();
        }
    }

    /** Takes a player who left off every bar. */
    void forget(Player player) {
        UUID id = player.getUniqueId();
        for (Entry entry : bars.values()) {
            if (entry.viewers.remove(id) != null) {
                player.hideBossBar(entry.bar);
            }
        }
    }

    /** The bar behind {@code id}, or null (null ids included). For tests and RsLib. */
    BossBar bar(String id) {
        Entry entry = id == null ? null : bars.get(id);
        return entry == null ? null : entry.bar;
    }

    /** Within 0..1; NaN is 0. */
    static float clamp(float progress) {
        if (!(progress > 0f)) {
            return 0f;
        }
        return Math.min(progress, 1f);
    }

    private Entry known(String id) {
        Entry entry = id == null ? null : bars.get(id);
        if (entry == null) {
            warnUnknown(id);
        }
        return entry;
    }

    private void warnUnknown(String id) {
        if (warned.add(String.valueOf(id))) {
            logger.warning("There is no boss bar '" + id + "' (create it with bossbars().create(id)...build()).");
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException huge) {
            return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /**
     * Builds a bar: text "", progress 1, color white, overlay progress unless set. A bar with
     * the same id is replaced and hidden from its viewers.
     */
    public final class Builder {

        private final String id;
        private String text = "";
        private float progress = 1f;
        private BossBar.Color color = BossBar.Color.WHITE;
        private BossBar.Overlay overlay = BossBar.Overlay.PROGRESS;

        private Builder(String id) {
            this.id = id;
        }

        /** Title as written in a config; null is empty. */
        public Builder text(String text) {
            this.text = text == null ? "" : text;
            return this;
        }

        /** Kept within 0..1. */
        public Builder progress(float progress) {
            this.progress = clamp(progress);
            return this;
        }

        public Builder color(BossBar.Color color) {
            this.color = Objects.requireNonNull(color, "color");
            return this;
        }

        public Builder overlay(BossBar.Overlay overlay) {
            this.overlay = Objects.requireNonNull(overlay, "overlay");
            return this;
        }

        /** Registers the bar under its id, with no viewers, and returns it. */
        public BossBar build() {
            BossBar bar = BossBar.bossBar(RsText.color(text), progress, color, overlay);
            Entry previous = bars.put(id, new Entry(bar));
            if (previous != null) {
                previous.close();
            }
            return bar;
        }
    }

    /** One registered bar, its viewers and its timer. */
    private static final class Entry {

        final BossBar bar;
        final Map<UUID, Player> viewers = new ConcurrentHashMap<>();
        /** Guarded by this entry. */
        TaskHandle timer;

        Entry(BossBar bar) {
            this.bar = bar;
        }

        /** Caller holds this entry's lock. */
        void stopTimer() {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
        }

        void close() {
            synchronized (this) {
                stopTimer();
            }
            for (Player viewer : viewers.values()) {
                viewer.hideBossBar(bar);
            }
            viewers.clear();
        }
    }

    /** One timer: progress from the elapsed real time, stopping itself at the end. */
    static final class Animation implements Runnable {

        private final BossBar bar;
        private final LongSupplier clock;
        private final long start;
        private final long total;
        private final boolean countdown;
        volatile TaskHandle handle;

        Animation(BossBar bar, LongSupplier clock, long start, long total, boolean countdown) {
            this.bar = bar;
            this.clock = clock;
            this.start = start;
            this.total = total;
            this.countdown = countdown;
        }

        @Override
        public void run() {
            float done = fraction(clock.getAsLong() - start, total);
            bar.progress(countdown ? 1f - done : done);
            if (done >= 1f) {
                TaskHandle own = handle;
                if (own != null) {
                    own.cancel();
                }
            }
        }

        /** Elapsed over total, within 0..1. */
        static float fraction(long elapsed, long total) {
            if (total <= 0L || elapsed >= total) {
                return 1f;
            }
            if (elapsed <= 0L) {
                return 0f;
            }
            return (float) ((double) elapsed / (double) total);
        }
    }
}
