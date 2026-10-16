package com.rs.lib.hologram;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.rs.lib.bench.Bench;
import com.rs.lib.hologram.FakeHologramHost.FakeWorld;

/**
 * Hologram refreshes with a placeholder, with players near and with nobody near (the refresh
 * then skips its render). Run with RSLIB_BENCH=1.
 */
@EnabledIf("com.rs.lib.bench.Bench#enabled")
class HologramBenchmark {

    private static final int HOLOGRAMS = 200;

    @Test
    void refreshes() {
        Bench bench = Bench.table("Refresh of " + HOLOGRAMS + " holograms, 3 lines with a placeholder");
        FakeHologramHost host = new FakeHologramHost("HoloBench" + UUID.randomUUID().toString().substring(0, 8));
        FakeWorld world = host.addWorld("world");
        int[] online = {0};
        int[] renders = {0};
        host.papi = line -> {
            if (line.indexOf('%') < 0) {
                return line;
            }
            renders[0]++;
            return line.replace("%online%", String.valueOf(online[0]));
        };
        HologramUtil holograms = new HologramUtil(host);
        try {
            for (int i = 0; i < HOLOGRAMS; i++) {
                int chunk = i * 10;
                world.load(chunk, 0);
                holograms.spawn("h" + i, new Location(world.world, chunk * 16 + 8, 64, 8),
                        List.of("&6&lCrate #" + i, "&7%online% players online", "&eClick to open"));
                holograms.refreshEvery("h" + i, 20);
            }
            List<FakeHologramHost.Timer> timers = new ArrayList<>(host.timers);
            // 30 players spread over the world: a few holograms have someone near.
            for (int p = 0; p < 30; p++) {
                host.stand("world", p * 67, 0);
            }
            Runnable refreshAll = () -> {
                online[0]++;
                for (FakeHologramHost.Timer timer : timers) {
                    timer.fire();
                }
            };
            int seen = 0;
            for (int i = 0; i < HOLOGRAMS; i++) {
                seen += host.anyoneNear("world", i * 10, 0, HologramUtil.radiusChunks(0f), java.util.Set.of()) ? 1 : 0;
            }
            renders[0] = 0;
            refreshAll.run();
            int rendersSkipping = renders[0];
            bench.run("30 players, " + seen + " holograms near one (after)", HOLOGRAMS, refreshAll);

            List<FakeHologramHost.Standing> saved = host.standing;
            host.standing = null;
            renders[0] = 0;
            refreshAll.run();
            int rendersAll = renders[0];
            bench.run("every hologram near a player (before)", HOLOGRAMS, refreshAll);
            host.standing = saved;

            bench.note("placeholder renders per refresh round: " + rendersAll + " before, " + rendersSkipping
                    + " after");
            bench.note("ns/op is per hologram refresh; the fake host's proximity check walks 30 players");
            bench.print();
        } finally {
            holograms.shutdown();
        }
    }
}
