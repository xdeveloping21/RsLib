package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.gui.GuiKit.TestPlayer;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.text.SharedTicks;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlTestSupport;

/**
 * A menu's timed refresh computes the file's own placeholders once per tick for every viewer;
 * what each viewer sees does not change.
 */
class SharedMenuPlaceholdersTest {

    private final GuiKit kit = new GuiKit();
    private final long[] tick = {100};
    private final AtomicInteger computed = new AtomicInteger();
    private int online = 3;
    private RsYml yml;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws IOException {
        File file = dir.resolve("shop.yml").toFile();
        Files.writeString(file.toPath(), "title: x\n");
        yml = YmlTestSupport.open(file, new YmlTestSupport.FakeHost());
        yml.placeholder("online", () -> {
            computed.incrementAndGet();
            return String.valueOf(online);
        });
        kit.resolver = ItemAppearance.TextResolver.of(yml);
        SharedTicks.use(() -> tick[0]);
    }

    @AfterEach
    void tearDown() {
        SharedTicks.use(null);
    }

    @Test
    void everyViewerRefreshedInOneTickSharesOneComputation() {
        Gui gui = kit.gui("shop", """
                title: 'Online {online}'
                update-interval: 20
                """);
        List<TestPlayer> viewers = List.of(kit.player("A"), kit.player("B"), kit.player("C"), kit.player("D"));
        for (TestPlayer viewer : viewers) {
            gui.open(viewer.player);
        }
        assertEquals(viewers.size(), computed.get(), "opening is not a timed refresh: one per viewer, as before");

        computed.set(0);
        online = 7;
        kit.fireTimers();
        assertEquals(1, computed.get(), "four viewers, one computation");
        for (TestPlayer viewer : viewers) {
            assertEquals("Online 7", kit.title(viewer), "every viewer sees the current value");
        }

        online = 8;
        kit.fireTimers();
        assertEquals(1, computed.get(), "the same tick: nothing computed again");

        tick[0]++;
        kit.fireTimers();
        assertEquals(2, computed.get(), "the next tick computes once more");
        for (TestPlayer viewer : viewers) {
            assertEquals("Online 8", kit.title(viewer));
        }
    }

    @Test
    void withoutSharingEachViewerComputesItsOwn() {
        SharedTicks.use(() -> Long.MIN_VALUE);
        Gui gui = kit.gui("shop", """
                title: 'Online {online}'
                update-interval: 20
                """);
        List<TestPlayer> viewers = List.of(kit.player("A"), kit.player("B"), kit.player("C"));
        for (TestPlayer viewer : viewers) {
            gui.open(viewer.player);
        }
        computed.set(0);
        kit.fireTimers();
        assertEquals(3, computed.get(), "the count before this change: one per viewer and refresh");
    }
}
