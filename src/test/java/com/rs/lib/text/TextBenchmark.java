package com.rs.lib.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.rs.lib.Ph;
import com.rs.lib.bench.Bench;

/** Text rendering: the shared render cache, a full parse, and local placeholders. Run with RSLIB_BENCH=1. */
@EnabledIf("com.rs.lib.bench.Bench#enabled")
class TextBenchmark {

    private static final String LINE = "&6&lVote crate &7- &#55FF55reward <gradient:#ff0000:#0000ff>ready</gradient> &f!";
    private static final String TEMPLATE = "&7Player &f{player} &7has &a{coins} &7coins &8(&e{rank}&8)";

    @Test
    void textRendering() {
        Bench bench = Bench.table("Text rendering (RsText)");
        RsText.color(LINE);
        bench.run("color, render cache hit", 1, () -> Bench.consume(RsText.color(LINE)));
        bench.run("color, cache miss (full parse)", 1, () -> Bench.consume(RsText.colorUncached(LINE)));

        Ph[] phs = {Ph.of("player", "Steve"), Ph.of("coins", "1,250"), Ph.of("rank", "VIP")};
        bench.run("applyLocals + color (cache miss)", 1,
                () -> Bench.consume(RsText.colorUncached(RsText.applyLocals(TEMPLATE, phs))));
        RsText.Template compiled = RsText.compile(TEMPLATE);
        bench.run("compiled template render", 1, () -> Bench.consume(compiled.render(phs)));
        bench.run("applyLocals only (3 tokens)", 1, () -> Bench.consume(RsText.applyLocals(TEMPLATE, phs)));
        bench.print();
    }
}
