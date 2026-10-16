package com.rs.lib.cooldown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.scheduler.TaskHandle;

/** The hotbar sweep follows the cooldown: drawn with what is left, never deciding, drawn again on join. */
class HotbarCooldownsTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<String> drawn = new ArrayList<>();
    private final List<Runnable> later = new ArrayList<>();
    private final UUID id = UUID.randomUUID();
    private boolean online = true;
    private Player player;
    private Cooldowns cooldowns;
    private HotbarCooldowns hotbar;

    /** An item that needs no server. */
    static final class Wand extends ItemStack {

        @SuppressWarnings("deprecation")
        Wand() {
            super();
        }

        @Override
        public Material getType() {
            return Material.BLAZE_ROD;
        }

        @Override
        public ItemStack clone() {
            return this;
        }
    }

    @BeforeEach
    void setUp() {
        player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> online;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        cooldowns = new Cooldowns(Logger.getAnonymousLogger(), now::get, (period, task) -> new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        }, () -> null, "Test");
        hotbar = cooldowns.hotbar(new HotbarCooldowns.Display() {
            @Override
            public void draw(Player who, Material material, int ticks) {
                drawn.add(material + ":" + ticks);
            }

            @Override
            public void draw(Player who, ItemStack item, int ticks) {
                drawn.add("item " + item.getType() + ":" + ticks);
            }

            @Override
            public void later(Player who, Runnable task) {
                later.add(task);
            }
        });
    }

    @AfterEach
    void tearDown() {
        cooldowns.shutdown();
    }

    @Test
    void theSweepShowsWhatIsLeftOfTheCooldown() {
        assertTrue(hotbar.tryUse(player, "boost", Duration.ofSeconds(30), Material.FIREWORK_ROCKET));
        assertEquals(List.of("FIREWORK_ROCKET:600"), drawn);
        now.addAndGet(10_010);
        assertFalse(hotbar.tryUse(player, "boost", Duration.ofSeconds(30), Material.FIREWORK_ROCKET),
                "the cooldown decides, not the sweep");
        assertEquals("FIREWORK_ROCKET:400", drawn.get(1), "19.99 s left round up to 400 ticks");
        assertEquals(19_990, cooldowns.remainingMillis(id, "boost"));
    }

    @Test
    void anItemUsesItsOwnCooldownGroup() {
        assertTrue(hotbar.tryUse(player, "wand", Duration.ofMillis(1_000), new Wand()));
        assertEquals(List.of("item BLAZE_ROD:20"), drawn);
    }

    @Test
    void nothingIsDrawnWithoutACooldown() {
        hotbar.show(player, "never", Material.ENDER_PEARL);
        assertTrue(drawn.isEmpty(), "a vanilla pearl cooldown is not cleared");
        assertTrue(hotbar.tryUse(player, "zero", Duration.ZERO, Material.ENDER_PEARL));
        assertTrue(drawn.isEmpty());
    }

    @Test
    void joiningDrawsTheRunningSweepsAgain() {
        hotbar.tryUse(player, "long", Duration.ofMinutes(1), Material.FEATHER);
        hotbar.tryUse(player, "short", Duration.ofSeconds(2), new Wand());
        drawn.clear();
        now.addAndGet(30_000);
        HotbarCooldowns.joined(player);
        assertEquals(1, later.size(), "a tick after the join");
        later.get(0).run();
        assertEquals(List.of("FEATHER:600"), drawn, "the ended one is not drawn and is forgotten");
        assertEquals(1, hotbar.remembered());

        now.addAndGet(60_000);
        hotbar.purgeExpired();
        assertEquals(0, hotbar.remembered());
    }

    @Test
    void aPlayerWhoLeftAgainIsSkipped() {
        hotbar.tryUse(player, "long", Duration.ofMinutes(1), Material.FEATHER);
        drawn.clear();
        HotbarCooldowns.joined(player);
        online = false;
        later.forEach(Runnable::run);
        assertTrue(drawn.isEmpty());
    }

    @Test
    void ticksRoundUpAndStayInRange() {
        assertEquals(1, HotbarCooldowns.ticks(1));
        assertEquals(1, HotbarCooldowns.ticks(50));
        assertEquals(2, HotbarCooldowns.ticks(51));
        assertEquals(Integer.MAX_VALUE, HotbarCooldowns.ticks(Long.MAX_VALUE));
    }

    @Test
    void shutdownStopsDrawingOnJoin() {
        hotbar.tryUse(player, "long", Duration.ofMinutes(1), Material.FEATHER);
        cooldowns.shutdown();
        later.clear();
        HotbarCooldowns.joined(player);
        assertTrue(later.isEmpty());
    }
}
