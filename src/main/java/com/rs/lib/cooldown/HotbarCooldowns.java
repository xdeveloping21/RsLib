package com.rs.lib.cooldown;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import com.rs.lib.Rs;
import com.rs.lib.compat.RsCompat;
import com.rs.lib.compat.RsVersion;

/**
 * The client's cooldown sweep over an item in the hotbar, kept in step with a {@link Cooldowns}
 * category, reached with {@code rs().cooldowns().hotbar()}.
 *
 * <pre>
 * if (!rs().cooldowns().hotbar().tryUse(player, "fly-boost", Duration.ofSeconds(30), item)) {
 *     return; // still on cooldown; the sweep is drawn again with what is left
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@link Cooldowns} stays the only truth: the sweep is drawn from its remaining time and
 *   never decides anything. A sweep the client lost is only cosmetic.</li>
 *   <li>By material, the sweep covers every item of that material (as vanilla does for ender
 *   pearls). With an item, on Paper 1.21.2 or newer the item's own cooldown group is used (its
 *   {@code use_cooldown} component, or its material); on older servers, its material.</li>
 *   <li>A sweep still running when the player leaves is drawn again when they come back, while
 *   the cooldown has time left (a session category is cleared on quit, so it is not). Nothing is
 *   drawn again after a restart; call {@link #show} on join for persistent categories.</li>
 *   <li>A material that is not an item, or a server without the API, makes it do nothing.</li>
 * </ul>
 *
 * <p>Thread-safe; the sweep is drawn on the player's thread.</p>
 */
public final class HotbarCooldowns {

    /** Live instances, for the shared join listener. */
    private static final Set<HotbarCooldowns> LIVE = ConcurrentHashMap.newKeySet();
    private static final Listener JOIN = new JoinListener();

    /** How the sweep reaches the client; tests record it. */
    interface Display {

        /** Draws {@code ticks} of sweep over {@code material} for {@code player}, on their thread. */
        void draw(Player player, Material material, int ticks);

        /** Draws {@code ticks} of sweep over {@code item}'s cooldown group, on the player's thread. */
        void draw(Player player, ItemStack item, int ticks);

        /** Runs {@code task} on the player's thread a tick from now. */
        void later(Player player, Runnable task);
    }

    /** What was drawn over which item, to draw it again on join. */
    private record Shown(Material material, ItemStack item, long until) {
    }

    private final Cooldowns cooldowns;
    private final Display display;
    private final LongSupplier clock;
    private final Map<UUID, Map<String, Shown>> shown = new ConcurrentHashMap<>();

    HotbarCooldowns(Cooldowns cooldowns, Display display, LongSupplier clock) {
        this.cooldowns = cooldowns;
        this.display = display;
        this.clock = clock;
        LIVE.add(this);
    }

    /** The join listener every plugin shares; RsLib registers it. */
    public static Listener joinListener() {
        return JOIN;
    }

    /**
     * {@link Cooldowns#tryUse} and then the sweep over {@code material} with the time left,
     * whether the cooldown started now or was already running.
     */
    public boolean tryUse(Player player, String category, Duration cooldown, Material material) {
        boolean started = cooldowns.tryUse(player.getUniqueId(), category, cooldown);
        show(player, category, material);
        return started;
    }

    /** Same as {@link #tryUse(Player, String, Duration, Material)} over {@code item}'s cooldown group. */
    public boolean tryUse(Player player, String category, Duration cooldown, ItemStack item) {
        boolean started = cooldowns.tryUse(player.getUniqueId(), category, cooldown);
        show(player, category, item);
        return started;
    }

    /** Draws the sweep over {@code material} with what is left of {@code category}; nothing when none is. */
    public void show(Player player, String category, Material material) {
        Objects.requireNonNull(material, "material");
        draw(player, category, new Shown(material, null, 0L));
    }

    /** Draws the sweep over {@code item}'s cooldown group with what is left of {@code category}. */
    public void show(Player player, String category, ItemStack item) {
        Objects.requireNonNull(item, "item");
        draw(player, category, new Shown(null, item.clone(), 0L));
    }

    private void draw(Player player, String category, Shown what) {
        Objects.requireNonNull(player, "player");
        long left = cooldowns.remainingMillis(player.getUniqueId(), category);
        if (left <= 0) {
            return;
        }
        Shown kept = new Shown(what.material(), what.item(), clock.getAsLong() + left);
        shown.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).put(category, kept);
        int ticks = ticks(left);
        if (kept.material() != null) {
            display.draw(player, kept.material(), ticks);
        } else {
            display.draw(player, kept.item(), ticks);
        }
    }

    /** Draws again, with the time left, every sweep of {@code player} whose cooldown still runs. */
    void restore(Player player) {
        Map<String, Shown> mine = shown.get(player.getUniqueId());
        if (mine == null) {
            return;
        }
        for (Map.Entry<String, Shown> entry : mine.entrySet()) {
            Shown what = entry.getValue();
            if (cooldowns.remainingMillis(player.getUniqueId(), entry.getKey()) <= 0) {
                mine.remove(entry.getKey(), what);
                continue;
            }
            draw(player, entry.getKey(), what);
        }
        if (mine.isEmpty()) {
            shown.remove(player.getUniqueId(), mine);
        }
    }

    /** Forgets the sweeps whose cooldowns ended; runs with the cooldown cleanup. */
    void purgeExpired() {
        long now = clock.getAsLong();
        for (Map<String, Shown> mine : shown.values()) {
            mine.values().removeIf(what -> what.until() <= now);
        }
        shown.values().removeIf(Map::isEmpty);
    }

    /** Stops drawing again on join; called with the cooldowns' shutdown. */
    void shutdown() {
        LIVE.remove(this);
        shown.clear();
    }

    /** Sweeps remembered for every player; for tests. */
    int remembered() {
        int count = 0;
        for (Map<String, Shown> mine : shown.values()) {
            count += mine.size();
        }
        return count;
    }

    /** Milliseconds as ticks, rounded up so the sweep never ends before the cooldown. */
    static int ticks(long millis) {
        long ticks = millis / Cooldowns.MILLIS_PER_TICK + (millis % Cooldowns.MILLIS_PER_TICK == 0 ? 0 : 1);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, ticks));
    }

    static void joined(Player player) {
        for (HotbarCooldowns live : LIVE) {
            live.display.later(player, () -> {
                if (player.isOnline()) {
                    live.restore(player);
                }
            });
        }
    }

    /** The real server: {@code setCooldown} on the player's thread. */
    static Display server(Rs rs) {
        return new Display() {

            @Override
            public void draw(Player player, Material material, int ticks) {
                onThread(player, () -> {
                    if (material.isItem()) {
                        player.setCooldown(material, ticks);
                    }
                });
            }

            @Override
            public void draw(Player player, ItemStack item, int ticks) {
                onThread(player, () -> {
                    Method byItem = ItemCooldownApi.METHOD;
                    if (byItem != null) {
                        try {
                            byItem.invoke(player, item, ticks);
                            return;
                        } catch (ReflectiveOperationException | RuntimeException failed) {
                            // Fall back to the material below.
                        }
                    }
                    if (item.getType().isItem()) {
                        player.setCooldown(item.getType(), ticks);
                    }
                });
            }

            @Override
            public void later(Player player, Runnable task) {
                rs.scheduler().entityLater(player, 1L, task);
            }

            private void onThread(Player player, Runnable task) {
                if (rs.scheduler().ownsHere(player)) {
                    task.run();
                } else {
                    rs.scheduler().entity(player, task);
                }
            }
        };
    }

    /** {@code HumanEntity#setCooldown(ItemStack, int)} (Paper 1.21.2+), looked up on first use; null before. */
    private static final class ItemCooldownApi {

        static final Method METHOD = RsVersion.supports(21, 2)
                ? RsCompat.probe(HumanEntity.class, "setCooldown", ItemStack.class, int.class) : null;

        private ItemCooldownApi() {
        }
    }

    /** Draws the sweeps again a tick after a player joins. */
    private static final class JoinListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            if (!LIVE.isEmpty()) {
                joined(event.getPlayer());
            }
        }
    }
}
