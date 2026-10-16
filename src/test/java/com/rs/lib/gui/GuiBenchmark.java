package com.rs.lib.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.rs.lib.bench.Bench;
import com.rs.lib.gui.GuiKit.TestPlayer;
import com.rs.lib.item.ItemAppearance;
import com.rs.lib.text.SharedTicks;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlTestSupport;

/**
 * Menu repaints without PlaceholderAPI on a 54-cell menu (resolution and diffing; item building
 * is a fake), and a timed refresh of many viewers with and without shared placeholders.
 * Run with RSLIB_BENCH=1.
 */
@EnabledIf("com.rs.lib.bench.Bench#enabled")
class GuiBenchmark {

    private static String allCells() {
        StringJoiner cells = new StringJoiner(", ", "[", "]");
        for (int i = 0; i < 54; i++) {
            cells.add(String.valueOf(i));
        }
        return cells.toString();
    }

    @Test
    void menuRepaint() {
        Bench bench = Bench.table("Menu repaint, 54 cells, no PlaceholderAPI (GuiSession)");
        GuiKit kit = new GuiKit();
        TestPlayer steve = kit.player("Steve");

        Gui still = kit.gui("still", "rows: 6\nitems:\n  pane: {material: GLASS_PANE, slots: " + allCells() + "}\n");
        still.open(steve.player);
        GuiSession stillSession = still.session(steve.player);
        int writes = kit.writes;
        bench.run("54 declared cells, nothing changed", 1, stillSession::refreshMenu);
        bench.note("writes while nothing changed: " + (kit.writes - writes));

        Gui paged = kit.gui("paged", """
                rows: 6
                pagination: true
                templates:
                  e: {material: PAPER, display-name: '{v}'}
                """);
        TestPlayer alex = kit.player("Alex");
        paged.open(alex.player);
        GuiSession pagedSession = paged.session(alex.player);
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < 54; i++) {
            data.add(i);
        }
        int[] cells = new int[54];
        for (int i = 0; i < 54; i++) {
            cells[i] = i;
        }
        int[] offset = {0};
        int[] changing = {54};
        pagedSession.bindPaged("e", data, cells,
                (value, phs) -> phs.add("v", value < changing[0] ? value + offset[0] : value));
        bench.run("54 bound cells, all 54 changed", 1, () -> {
            offset[0]++;
            pagedSession.refreshPage();
        });
        changing[0] = 6;
        bench.run("54 bound cells, 6 changed", 1, () -> {
            offset[0]++;
            pagedSession.refreshPage();
        });
        changing[0] = 0;
        bench.run("54 bound cells, nothing changed", 1, pagedSession::refreshPage);
        bench.print();
    }

    @Test
    void sharedPlaceholders() throws IOException {
        Bench bench = Bench.table("Timed refresh of one menu open by 50 players (file placeholder in title + 6 items)");
        Path file = Files.createTempFile("rslib-bench", ".yml");
        try {
            Files.writeString(file, "title: x\n");
            RsYml yml = YmlTestSupport.open(file.toFile(), new YmlTestSupport.FakeHost());
            int[] computed = {0};
            yml.placeholder("online", () -> {
                computed[0]++;
                return String.format(Locale.ROOT, "%,d online", 1000 + computed[0] % 7);
            });
            GuiKit kit = new GuiKit();
            kit.resolver = ItemAppearance.TextResolver.of(yml);
            Gui gui = kit.gui("lobby", """
                    rows: 6
                    title: '&8Lobby &7- {online}'
                    update-interval: 20
                    items:
                      a: {material: STONE, slots: [0, 1, 2, 3, 4, 5]}
                    """);
            for (int i = 0; i < 50; i++) {
                gui.open(kit.player("P" + i).player);
            }
            long[] tick = {0};
            SharedTicks.use(() -> Long.MIN_VALUE);
            computed[0] = 0;
            kit.fireTimers();
            int unshared = computed[0];
            bench.run("50 viewers, each computes (before)", 1, kit::fireTimers);

            SharedTicks.use(() -> tick[0]);
            tick[0]++;
            computed[0] = 0;
            kit.fireTimers();
            int shared = computed[0];
            bench.run("50 viewers, shared per tick (after)", 1, () -> {
                tick[0]++;
                kit.fireTimers();
            });
            bench.note("placeholder computations per refresh tick: before " + unshared + ", after " + shared);
            bench.print();
        } finally {
            SharedTicks.use(null);
            Files.deleteIfExists(file);
        }
    }
}
