package com.rs.lib.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.text.internal.SharedPlaceholders;
import com.rs.lib.text.internal.SharedTicks;

/** While menus share values, a global placeholder answers every viewer from one computation per tick. */
class SharedGlobalsTest {

    private final FakePapiHost host = new FakePapiHost();
    private final long[] tick = {1};
    private final AtomicInteger globals = new AtomicInteger();
    private final AtomicInteger perPlayer = new AtomicInteger();
    private final List<OfflinePlayer> viewers = List.of(FakePapiHost.player("A"), FakePapiHost.player("B"),
            FakePapiHost.player("C"));

    private PapiHolder holder() {
        return new RsPapi(host).expansion("shop")
                .global("online", () -> "on" + globals.incrementAndGet())
                .globalPrefixed("top_", rest -> "top" + rest + ":" + globals.incrementAndGet())
                .placeholder("name", p -> p.getName() + perPlayer.incrementAndGet())
                .build();
    }

    @AfterEach
    void tearDown() {
        SharedTicks.use(null);
    }

    @Test
    void globalsAreComputedOncePerTickAndPlayerPlaceholdersPerViewer() {
        SharedTicks.use(() -> tick[0]);
        PapiHolder holder = holder();
        for (OfflinePlayer viewer : viewers) {
            SharedPlaceholders.during(() -> {
                assertEquals("on1", holder.resolve(viewer, "online"));
                assertEquals("on1", holder.resolve(viewer, "ONLINE"), "same name, any case");
                assertEquals("top1:2", holder.resolve(viewer, "top_1"));
                String name = holder.resolve(viewer, "name");
                assertEquals(viewer.getName() + perPlayer.get(), name);
            });
        }
        assertEquals(2, globals.get(), "two global values, three viewers");
        assertEquals(3, perPlayer.get(), "player placeholders are never shared");

        tick[0]++;
        SharedPlaceholders.during(() -> assertEquals("on3", holder.resolve(viewers.get(0), "online")));
    }

    @Test
    void outsideASharedRefreshNothingChanges() {
        SharedTicks.use(() -> tick[0]);
        PapiHolder holder = holder();
        for (OfflinePlayer viewer : viewers) {
            holder.resolve(viewer, "online");
        }
        assertEquals(3, globals.get());
        assertNull(holder.resolve(null, "name"), "a player placeholder without a player stays unresolved");
    }

    @Test
    void aFailingGlobalIsLoggedOnceAndStaysUnresolvedForEveryViewer() {
        SharedTicks.use(() -> tick[0]);
        AtomicInteger calls = new AtomicInteger();
        PapiHolder holder = new RsPapi(host).expansion("shop").global("bad", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }).build();
        for (OfflinePlayer viewer : viewers) {
            SharedPlaceholders.during(() -> assertNull(holder.resolve(viewer, "bad")));
        }
        assertEquals(1, calls.get());
    }
}
