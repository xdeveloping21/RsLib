package com.rs.lib.scheduler;

import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.rs.lib.bench.Bench;

/** What the shared ticker costs per server tick for many jobs. Run with RSLIB_BENCH=1. */
@EnabledIf("com.rs.lib.bench.Bench#enabled")
class TickerBenchmark {

    @Test
    void ticks() {
        Bench bench = Bench.table("Shared ticker, one server tick");
        Logger logger = Logger.getAnonymousLogger();
        for (int jobs : new int[] {100, 1000}) {
            SharedTickerTest.Server server = new SharedTickerTest.Server();
            SharedTicker ticker = new SharedTicker(logger, "Bench", server::repeatFromNextTick);
            int[] runs = {0};
            long[] periods = {10, 20, 40};
            for (int i = 0; i < jobs; i++) {
                ticker.add(periods[i % 3], () -> runs[0]++);
            }
            bench.run(jobs + " jobs, periods 10/20/40", 1, server::advance);
            bench.note(jobs + " jobs: " + server.tasks.size() + " scheduled task (one per job before: " + jobs + ")");
            ticker.cancelAll();
        }
        bench.print();
    }
}
