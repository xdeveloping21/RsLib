package com.rs.lib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.command.RootCommand.Resolution;

class ResolutionTest {

    private static final CommandSender ADMIN = FakeSender.with("rk.admin", "rk.give", "rk.top");
    private static final CommandSender NOBODY = FakeSender.with();

    private static RootCommand kits() {
        return Trees.root("rk.admin", null, null,
                Trees.sub("give").aliases("g").permission("rk.give").description("Give a kit")
                        .arg("player", Args.string())
                        .argOptional("amount", Args.intMin(1))
                        .executes(ctx -> { }),
                Trees.sub("say").arg("text", Args.greedy()).executes(ctx -> { }),
                Trees.sub("admin")
                        .sub("reset", s -> s.permission("rk.reset").executes(ctx -> { }))
                        .sub("top", s -> s.permission("rk.top").executes(ctx -> { })),
                Trees.sub("panel").groupHelp()
                        .sub("open", s -> s.description("Open it").executes(ctx -> { })),
                Trees.sub("secret").visible(false).executes(ctx -> { }),
                Trees.sub("pick").arg("n", Args.intRange(1, 5)).executes(ctx -> { }),
                Trees.sub("noop"));
    }

    private static Map<String, String> phs(Resolution result) {
        Ph[] phs = ((Resolution.Message) result).phs();
        return Arrays.stream(phs).collect(Collectors.toMap(Ph::key, Ph::value));
    }

    private static String key(Resolution result) {
        return assertInstanceOf(Resolution.Message.class, result).key();
    }

    private static Resolution run(CommandSender sender, String line) {
        return kits().resolve(sender, "rk", line.isEmpty() ? new String[0] : line.split(" "));
    }

    @Test
    void rootPermissionComesFirst() {
        assertEquals("rslib.no-permission", key(run(NOBODY, "")));
        assertEquals("rslib.no-permission", key(run(NOBODY, "give Steve")));
    }

    @Test
    void emptyLineIsEmpty() {
        assertInstanceOf(Resolution.Empty.class, run(ADMIN, ""));
    }

    @Test
    void unknownSubcommandShowsTheWordLiterally() {
        Resolution result = run(ADMIN, "<red>nope");
        assertEquals("rslib.unknown-subcommand", key(result));
        Ph value = Arrays.stream(((Resolution.Message) result).phs()).filter(p -> p.key().equals("value")).findFirst()
                .orElseThrow();
        assertEquals("<red>nope", value.value());
        assertTrue(value.literal());
    }

    @Test
    void missingArgumentShowsGeneratedUsage() {
        Resolution result = run(ADMIN, "give");
        assertEquals("rslib.usage", key(result));
        assertEquals("/rk give <player> [amount]", phs(result).get("usage"));
    }

    @Test
    void aliasesAndCaseResolve() {
        Resolution.Run run = assertInstanceOf(Resolution.Run.class, run(ADMIN, "G Steve 3"));
        assertEquals("Steve", run.context().get("player"));
        assertEquals(3, run.context().getInt("amount"));
        assertEquals("rk", run.context().label());
    }

    @Test
    void optionalArgumentAbsentIsNotInTheMap() {
        Resolution.Run run = assertInstanceOf(Resolution.Run.class, run(ADMIN, "give Steve"));
        assertFalse(run.context().has("amount"));
        assertNull(run.context().raw(1));
        assertThrows(IllegalArgumentException.class, () -> run.context().get("amount"));
    }

    @Test
    void extraWordsAreIgnoredButReadable() {
        Resolution.Run run = assertInstanceOf(Resolution.Run.class, run(ADMIN, "give Steve 2 extra"));
        assertEquals("extra", run.context().raw(2));
        assertEquals(3, run.context().rawCount());
    }

    @Test
    void numbersFailClosed() {
        assertEquals("rslib.number-too-small", key(run(ADMIN, "give Steve 0")));
        assertEquals("rslib.number-too-small", key(run(ADMIN, "give Steve -5")));
        assertEquals("rslib.invalid-number", key(run(ADMIN, "give Steve abc")));
        assertEquals("rslib.invalid-number", key(run(ADMIN, "give Steve 2.5")));
        assertEquals("rslib.invalid-number", key(run(ADMIN, "give Steve 1.500")));
        assertEquals("rslib.invalid-number", key(run(ADMIN, "give Steve 1e3")));
        assertEquals("rslib.invalid-number", key(run(ADMIN, "give Steve 99999999999")));
        Resolution.Run run = assertInstanceOf(Resolution.Run.class, run(ADMIN, "give Steve 2k"));
        assertEquals(2000, run.context().getInt("amount"));
        Resolution.Run grouped = assertInstanceOf(Resolution.Run.class, run(ADMIN, "give Steve 1,500,000"));
        assertEquals(1_500_000, grouped.context().getInt("amount"));
        Resolution.Run thousands = assertInstanceOf(Resolution.Run.class, run(ADMIN, "give Steve 1,500"));
        assertEquals(1500, thousands.context().getInt("amount"));
    }

    @Test
    void rangeReportsLimits() {
        Resolution result = run(ADMIN, "pick 9");
        assertEquals("rslib.out-of-range", key(result));
        assertEquals("1", phs(result).get("min"));
        assertEquals("5", phs(result).get("max"));
        assertEquals("9", phs(result).get("value"));
    }

    @Test
    void greedyTakesTheRest() {
        Resolution.Run run = assertInstanceOf(Resolution.Run.class, run(ADMIN, "say hello   big world"));
        assertEquals("hello   big world", run.context().get("text"));
    }

    @Test
    void groupWithoutWordListsAllowedChildren() {
        CommandSender onlyTop = FakeSender.with("rk.admin", "rk.top");
        Resolution result = kits().resolve(onlyTop, "rk", new String[] {"admin"});
        assertEquals("/rk admin <top>", phs(result).get("usage"));
        assertEquals("rslib.no-permission", key(kits().resolve(onlyTop, "rk", new String[] {"admin", "reset"})));
    }

    @Test
    void unknownWordInGroupNamesThePath() {
        Resolution result = run(ADMIN, "admin wipe");
        assertEquals("admin wipe", phs(result).get("value"));
    }

    @Test
    void groupHelpAnswersItsOwnHelp() {
        Resolution.GroupHelp bare = assertInstanceOf(Resolution.GroupHelp.class, run(ADMIN, "panel"));
        assertEquals(1, bare.page());
        Resolution.GroupHelp paged = assertInstanceOf(Resolution.GroupHelp.class, run(ADMIN, "panel help 2"));
        assertEquals(2, paged.page());
        assertEquals("panel", paged.group().name);
    }

    @Test
    void hiddenSubcommandsStillRun() {
        assertInstanceOf(Resolution.Run.class, run(ADMIN, "secret"));
    }

    @Test
    void leafWithoutExecutorShowsUsage() {
        assertEquals("rslib.usage", key(run(ADMIN, "noop")));
    }

    @Test
    void fallbackGetsEveryWord() {
        RootCommand trade = Trees.root(null, "request", null,
                Trees.sub("request").permission("trade.use").arg("player", Args.string()).executes(ctx -> { }),
                Trees.sub("accept").executes(ctx -> { }));
        CommandSender user = FakeSender.with("trade.use");
        Resolution.Run run = assertInstanceOf(Resolution.Run.class, trade.resolve(user, "trade", new String[] {"Steve"}));
        assertEquals("Steve", run.context().get("player"));
        assertEquals("Steve", run.context().raw(0));
        assertEquals("/trade request <player>", phs(trade.resolve(user, "trade", new String[] {"request"})).get("usage"));
        assertEquals("rslib.unknown-subcommand", key(trade.resolve(NOBODY, "trade", new String[] {"Steve"})));
    }

    @Test
    void fallbackUsageUsesTheShortPath() {
        RootCommand trade = Trees.root(null, "request", null,
                Trees.sub("request").arg("player", Args.string()).arg("item", Args.string()).executes(ctx -> { }));
        Resolution result = trade.resolve(NOBODY, "trade", new String[] {"Steve"});
        assertEquals("/trade <player> <item>", phs(result).get("usage"));
    }

    @Test
    void helpListsTheFallbackOnceInItsShortForm() {
        RootCommand trade = Trees.root(null, "request", null,
                Trees.sub("request").description("Sends a trade request").arg("player", Args.string())
                        .executes(ctx -> { }),
                Trees.sub("accept").arg("player", Args.string()).executes(ctx -> { }));
        List<String> usages = trade.helpEntries(NOBODY, "trade").stream().map(RootCommand.HelpEntry::usage).toList();
        assertEquals(List.of("/trade <player>", "/trade accept <player>"), usages);
    }

    @Test
    void fallbackMustBeADeclaredLeaf() {
        List<Node> declared = List.of(Trees.sub("request").arg("player", Args.string()).executes(ctx -> { }).build(""),
                Trees.sub("admin").sub("reset", s -> s.executes(ctx -> { })).build(""));
        assertEquals("request", RsCommands.fallbackOf("trade", "request", declared).name);
        assertNull(RsCommands.fallbackOf("trade", null, declared));
        for (String bad : List.of("help", "reload", "debug", "admin", "nope")) {
            assertThrows(IllegalStateException.class, () -> RsCommands.fallbackOf("trade", bad, declared), bad);
        }
    }

    @Test
    void lastDurationTakesSpacedWords() {
        RootCommand root = Trees.root(null, null, null,
                Trees.sub("ban").arg("player", Args.string()).arg("time", Args.duration()).executes(ctx -> { }),
                Trees.sub("delay").arg("time", Args.duration()).arg("player", Args.string()).executes(ctx -> { }));
        Resolution.Run run = assertInstanceOf(Resolution.Run.class,
                root.resolve(NOBODY, "rk", "ban Steve 1d 2h 30m".split(" ")));
        assertEquals(95_400_000L, run.context().<Long>get("time"));
        assertEquals("/rk ban <player> <time>", phs(root.resolve(NOBODY, "rk", new String[] {"ban"})).get("usage"));
        // Unknown words are skipped like in a config; only a duration of zero is refused.
        Resolution.Run skipped = assertInstanceOf(Resolution.Run.class,
                root.resolve(NOBODY, "rk", "ban Steve 1d please".split(" ")));
        assertEquals(86_400_000L, skipped.context().<Long>get("time"));
        assertEquals("rslib.invalid-value", key(root.resolve(NOBODY, "rk", "ban Steve some day".split(" "))));
        // Not last: one word, as before.
        Resolution.Run delay = assertInstanceOf(Resolution.Run.class,
                root.resolve(NOBODY, "rk", "delay 5m Steve".split(" ")));
        assertEquals(300_000L, delay.context().<Long>get("time"));
        assertEquals("Steve", delay.context().get("player"));
        assertEquals(List.of("30s", "5m", "1h", "1d"), root.suggestions(NOBODY, "ban Steve 1d ".split(" ", -1)));
    }

    @Test
    void explicitUsageReplacesLabel() {
        RootCommand root = Trees.root(null, null, null,
                Trees.sub("held").usage("/{label} held [player]").arg("player", Args.string()).executes(ctx -> { }));
        assertEquals("/c held [player]", phs(root.resolve(NOBODY, "c", new String[] {"held"})).get("usage"));
    }

    @Test
    void labelLosesPluginPrefixAndCase() {
        assertEquals("rsk", RootCommand.normalizeLabel("RsKills:RSK"));
        assertEquals("rsk", RootCommand.normalizeLabel("rsk"));
    }

    @Test
    void conditionsGateTheLeaf() {
        RootCommand root = Trees.root(null, null, null,
                Trees.sub("set").arg("mode", Args.string()).when(0, w -> w.startsWith("m")).executes(ctx -> { }));
        assertEquals("rslib.usage", key(root.resolve(NOBODY, "x", new String[] {"set", "zzz"})));
        assertInstanceOf(Resolution.Run.class, root.resolve(NOBODY, "x", new String[] {"set", "max"}));
    }

    @Test
    void builderRejectsBadShapes() {
        assertThrows(IllegalStateException.class,
                () -> Trees.sub("x").argOptional("a", Args.string()).arg("b", Args.string()));
        assertThrows(IllegalArgumentException.class, () -> Trees.sub("x").arg("a", Args.string()).arg("a", Args.string()));
        assertThrows(IllegalStateException.class, () -> Trees.sub("x")
                .sub("a", s -> { })
                .sub("A", s -> { })
                .build(""));
        assertThrows(IllegalStateException.class, () -> Trees.sub("x").sub("a", s -> s.and()));
        assertThrows(IllegalArgumentException.class, () -> Trees.sub("two words"));
        assertThrows(IllegalArgumentException.class, () -> Trees.sub("a.b"));
    }

    @Test
    void contextPlayerFailsForConsole() {
        Resolution.Run run = assertInstanceOf(Resolution.Run.class, run(ADMIN, "secret"));
        assertThrows(IllegalStateException.class, () -> run.context().player());
    }

    @Test
    void suggestionsUseNamesPermissionsAndOrder() {
        RootCommand root = kits();
        assertEquals(List.of("admin", "give", "noop", "panel", "pick", "say"), root.suggestions(ADMIN, new String[] {""}));
        assertEquals(List.of("give"), root.suggestions(ADMIN, new String[] {"g"}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {""}));
        assertEquals(List.of("top"), root.suggestions(FakeSender.with("rk.admin", "rk.top"), new String[] {"admin", ""}));
        assertEquals(List.of("help", "open"), root.suggestions(ADMIN, new String[] {"panel", ""}));
        assertEquals(List.of("<player>"), root.suggestions(ADMIN, new String[] {"give", ""}));
        assertEquals(List.of("1", "5"), root.suggestions(ADMIN, new String[] {"pick", ""}));
        assertEquals(List.of(), root.suggestions(ADMIN, new String[] {"pick", "3", ""}));
        assertEquals(List.of("<text>"), root.suggestions(ADMIN, new String[] {"say", "a", ""}));
    }

    @Test
    void fallbackSuggestionsJoinSubcommands() {
        RootCommand root = Trees.root(null, "go", null,
                Trees.sub("go").arg("where", Args.suggesting(() -> List.of("north", "accept-me"))).executes(ctx -> { }),
                Trees.sub("accept").executes(ctx -> { }));
        assertEquals(List.of("accept", "accept-me"), root.suggestions(NOBODY, new String[] {"acc"}));
        assertEquals(List.of("north"), root.suggestions(NOBODY, new String[] {"n"}));
    }

    @Test
    void lostBareKeyShowsThePrefixedLabel() {
        RootCommand root = kits();
        RootCommand other = kits();
        assertEquals("rk", RootCommand.shownLabel(root, "rk", "kits:", root));
        assertEquals("kits:rk", RootCommand.shownLabel(root, "rk", "kits:", other));
        assertEquals("kits:rk", RootCommand.shownLabel(root, "rk", "kits:", null));
        assertEquals("rk", root.shownLabel("rk"));
    }

    @Test
    void shownLabelReachesUsageButNotTheContext() {
        RootCommand root = kits();
        assertEquals("/kits:rk give <player> [amount]",
                phs(root.resolve(ADMIN, "rk", "kits:rk", new String[] {"give"})).get("usage"));
        assertEquals("/kits:rk admin <top>", phs(root.resolve(ADMIN, "rk", "kits:rk", new String[] {"admin"})).get("usage"));
        assertEquals("kits:rk", phs(root.resolve(ADMIN, "rk", "kits:rk", new String[] {"nope"})).get("label"));
        Resolution.Run run = assertInstanceOf(Resolution.Run.class,
                root.resolve(ADMIN, "rk", "kits:rk", new String[] {"give", "Steve"}));
        assertEquals("rk", run.context().label());
    }

    @Test
    void hiddenNodesSuggestNothingButStillRun() {
        RootCommand root = Trees.root(null, null, null,
                Trees.sub("vault").visible(false)
                        .sub("open", s -> s.arg("slot", Args.oneOf(() -> List.of("one", "two"))).executes(ctx -> { })),
                Trees.sub("peek").visible(false).arg("who", Args.oneOf(() -> List.of("alex", "sam"))).executes(ctx -> { }),
                Trees.sub("shop").sub("buy", s -> s.visible(false).arg("item", Args.oneOf(() -> List.of("apple")))
                        .executes(ctx -> { })));
        assertEquals(List.of("shop"), root.suggestions(NOBODY, new String[] {""}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"vault"}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"vault", ""}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"vault", "open", ""}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"peek", ""}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"shop", "buy", ""}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"shop", "b"}));
        assertInstanceOf(Resolution.Run.class, root.resolve(NOBODY, "rk", new String[] {"vault", "open", "one"}));
        assertInstanceOf(Resolution.Run.class, root.resolve(NOBODY, "rk", new String[] {"peek", "sam"}));
        assertInstanceOf(Resolution.Run.class, root.resolve(NOBODY, "rk", new String[] {"shop", "buy", "apple"}));
    }

    @Test
    void hiddenFallbackSuggestsNothing() {
        RootCommand root = Trees.root(null, "go", null,
                Trees.sub("go").visible(false).arg("where", Args.oneOf(() -> List.of("north"))).executes(ctx -> { }),
                Trees.sub("accept").executes(ctx -> { }));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"n"}));
        assertEquals(List.of(), root.suggestions(NOBODY, new String[] {"north", ""}));
        assertInstanceOf(Resolution.Run.class, root.resolve(NOBODY, "rk", new String[] {"north"}));
    }

    @Test
    void groupWithLeafPartsStaysAGroupAndIsReported() {
        List<String> ignored = new java.util.ArrayList<>();
        Node built = Trees.sub("admin").arg("x", Args.string()).executes(ctx -> { })
                .sub("reset", s -> s.when(0, w -> true).sub("all", a -> a.executes(ctx -> { })))
                .sub("top", s -> s.executes(ctx -> { }))
                .build("", ignored::add);
        assertTrue(built.group());
        assertEquals(List.of("admin reset", "admin"), ignored);
        Trees.sub("plain").arg("x", Args.string()).executes(ctx -> { }).build("", ignored::add);
        assertEquals(2, ignored.size());
    }
}
