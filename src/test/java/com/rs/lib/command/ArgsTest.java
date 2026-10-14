package com.rs.lib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import com.rs.lib.command.Arg.ArgParseException;

class ArgsTest {

    private static final CommandSender SENDER = FakeSender.with();

    @Test
    void oneOfReturnsTheCanonicalOption() throws ArgParseException {
        assertEquals("Diamond", Args.oneOf(() -> List.of("Iron", "Diamond")).parse(SENDER, "dIaMoNd"));
        ArgParseException e = assertThrows(ArgParseException.class,
                () -> Args.oneOf(() -> List.of("Iron")).parse(SENDER, "gold"));
        assertEquals("rslib.invalid-value", e.langKey());
    }

    @Test
    void boolAcceptsTheUsualWords() throws ArgParseException {
        for (String yes : List.of("true", "YES", "on")) {
            assertTrue(Args.bool().parse(SENDER, yes));
        }
        for (String no : List.of("false", "No", "OFF")) {
            assertEquals(Boolean.FALSE, Args.bool().parse(SENDER, no));
        }
        assertThrows(ArgParseException.class, () -> Args.bool().parse(SENDER, "maybe"));
    }

    @Test
    void durationIsMillisAndPositive() throws ArgParseException {
        assertEquals(93_600_000L, Args.duration().parse(SENDER, "1d2h"));
        assertEquals(30_000L, Args.duration().parse(SENDER, "30s"));
        assertThrows(ArgParseException.class, () -> Args.duration().parse(SENDER, "soon"));
        assertThrows(ArgParseException.class, () -> Args.duration().parse(SENDER, "0s"));
    }

    @Test
    void doubleArgumentsUseSuffixes() throws ArgParseException {
        assertEquals(1500.0, Args.doubleMin(0).parse(SENDER, "1.5k"));
        assertEquals(-2.5, Args.doubleRange(-5, 5).parse(SENDER, "-2.5"));
        ArgParseException tooSmall = assertThrows(ArgParseException.class, () -> Args.doubleMin(1).parse(SENDER, "0.5"));
        assertEquals("rslib.number-too-small", tooSmall.langKey());
        assertThrows(ArgParseException.class, () -> Args.doubleMin(0).parse(SENDER, "NaN"));
        assertThrows(ArgParseException.class, () -> Args.doubleMin(0).parse(SENDER, "Infinity"));
    }

    @Test
    void suggestionsFilterTheWholeSetThenCap() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            many.add("p" + i);
        }
        Args.RsArg<String> arg = Args.suggesting(() -> many);
        List<String> all = arg.suggest(SENDER, "", "name");
        assertEquals(Args.MAX_SUGGESTIONS, all.size());
        List<String> late = arg.suggest(SENDER, "p99", "name");
        assertEquals(List.of("p99", "p990", "p991", "p992", "p993", "p994", "p995", "p996", "p997", "p998", "p999"), late);
    }

    @Test
    void suggestionOrderIsCurrentHintExamplesOptions() {
        Args.RsArg<String> arg = Args.string("amount");
        arg.suggestCurrent(() -> "64");
        assertEquals(List.of("64", "<amount>"), arg.suggest(SENDER, "", "ignored"));
        assertEquals(List.of("<amount>"), arg.suggest(SENDER, "<", "ignored"));
        assertEquals(List.of(), arg.suggest(SENDER, "x", "ignored"));
    }

    @Test
    void duplicatesAreDroppedIgnoringCase() {
        Args.RsArg<String> arg = Args.suggesting(() -> List.of("Alpha", "alpha", "beta"));
        assertEquals(List.of("Alpha", "beta"), arg.suggest(SENDER, "", "x"));
    }

    @Test
    void failingCurrentValueOnlyLosesItself() {
        Args.RsArg<Boolean> arg = Args.bool();
        arg.suggestCurrent(() -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(List.of("true", "false"), arg.suggest(SENDER, "", "x"));
    }

    @Test
    void parseExceptionCopiesPlaceholders() {
        ArgParseException e = new ArgParseException("k", com.rs.lib.Ph.of("a", 1));
        e.phs()[0] = null;
        assertEquals("a", e.phs()[0].key());
    }
}
