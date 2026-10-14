package com.rs.lib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.stream.IntStream;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PageAndLangTest {

    @Test
    void pagesClampAndSlice() {
        Page<Integer> page = Page.of(IntStream.rangeClosed(1, 23).boxed().toList(), 10);
        assertEquals(3, page.totalPages());
        assertEquals(List.of(21, 22, 23), page.page(3));
        assertEquals(List.of(21, 22, 23), page.page(99));
        assertEquals(1, page.clamp(-4));
        assertEquals(1, Page.of(List.of(), 10).totalPages());
        assertEquals(List.of(), Page.of(List.of(), 10).page(1));
    }

    @Test
    void langBlockIsValidYamlWithTheCodeTexts() throws Exception {
        RootCommand root = Trees.root(null, null, null,
                Trees.sub("give").description("Give \"a\" kit\\now").arg("player", Args.string()).executes(c -> { }),
                Trees.sub("yes").sub("inner", s -> s.description("Deep").executes(c -> { })));
        String text = CommandLang.block(List.of(root));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new StringReader(text));
        assertEquals("Main", yaml.getString("commands.rk.description"));
        assertEquals("Give \"a\" kit\\now", yaml.getString("commands.rk.subcommands.give.description"));
        assertEquals("player", yaml.getString("commands.rk.subcommands.give.args.player"));
        assertEquals("Deep", yaml.getString("commands.rk.subcommands.yes.subcommands.inner.description"));
        assertTrue(text.startsWith("# "));
    }

    @Test
    void translatedLabelsReachUsageAndHelp() {
        RootCommand root = Trees.root(null, null, null,
                Trees.sub("give").description("Give").arg("player", Args.string()).executes(c -> { }));
        Node give = root.top.get(0);
        give.labels(new Node.Labels("Dar un kit", java.util.Map.of("player", "jugador")));
        assertEquals("Dar un kit", give.description());
        assertEquals("/rk give <jugador>", root.usageOf(give, "rk", false));
        give.labels(new Node.Labels(" ", java.util.Map.of()));
        assertEquals("Give", give.description());
    }
}
