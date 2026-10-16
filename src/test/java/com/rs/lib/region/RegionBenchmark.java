package com.rs.lib.region;

import java.util.Random;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.rs.lib.bench.Bench;

/** Region lookups by block through the chunk index ({@code RegionRegistry#at}). Run with RSLIB_BENCH=1. */
@EnabledIf("com.rs.lib.bench.Bench#enabled")
class RegionBenchmark {

    private static final int POINTS = 4096;

    @Test
    void lookups() {
        Bench bench = Bench.table("Region lookup, RegionRegistry.at(location)");
        World world = Fakes.world("world");
        RegionTracker tracker = new RegionTracker();
        RegionRegistry mine = new RegionRegistry("Bench", tracker);
        RegionRegistry other = new RegionRegistry("Other", tracker);
        Random random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            int x = random.nextInt(4000) - 2000;
            int z = random.nextInt(4000) - 2000;
            int size = 8 + random.nextInt(56);
            (i % 2 == 0 ? mine : other).register("r" + i, Cuboid.of("world", x, 0, z, x + size, 255, z + size));
        }
        mine.register("spawn", Cuboid.of("world", -100, 0, -100, 100, 255, 100));
        mine.register("map", Cuboid.of("world", -5000, -64, -5000, 5000, 320, 5000));

        Location[] anywhere = new Location[POINTS];
        Location[] atSpawn = new Location[POINTS];
        for (int i = 0; i < POINTS; i++) {
            anywhere[i] = new Location(world, random.nextInt(4000) - 2000, 64, random.nextInt(4000) - 2000);
            atSpawn[i] = new Location(world, random.nextInt(200) - 100, 64, random.nextInt(200) - 100);
        }
        int[] next = {0};
        bench.run("1002 regions, random block", 1,
                () -> Bench.consume(mine.at(anywhere[next[0]++ & (POINTS - 1)]).size()));
        bench.run("1002 regions, inside spawn + map", 1,
                () -> Bench.consume(mine.at(atSpawn[next[0]++ & (POINTS - 1)]).size()));
        int found = 0;
        for (Location at : anywhere) {
            found += mine.at(at).size();
        }
        bench.note("average regions of this plugin per random block: " + String.format(java.util.Locale.ROOT,
                "%.2f", found / (double) POINTS) + " (half of the 1000 belong to another plugin)");
        bench.print();
        mine.shutdown();
        other.shutdown();
    }
}
