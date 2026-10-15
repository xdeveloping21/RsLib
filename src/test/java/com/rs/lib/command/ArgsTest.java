package com.rs.lib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

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

    @Test
    void durationRejectsWhatItWouldMisread() {
        for (String bad : List.of("1,5h", "-5m", "1e3s", "+5m", "2w", "999999999d")) {
            assertThrows(ArgParseException.class, () -> Args.duration().parse(SENDER, bad), bad);
        }
    }

    @Test
    void parseExceptionNeedsAKey() {
        assertThrows(NullPointerException.class, () -> new ArgParseException(null));
    }

    /** The suggestions as they were made before the bounded heap: filter, sort everything, add until full. */
    private static List<String> sortEverything(List<String> fixed, Collection<String> all, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : fixed) {
            addLikeBefore(out, seen, value, prefix);
        }
        List<String> matching = new ArrayList<>();
        for (String option : all) {
            if (option != null && option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matching.add(option);
            }
        }
        matching.sort(String.CASE_INSENSITIVE_ORDER);
        for (String option : matching) {
            if (out.size() >= Args.MAX_SUGGESTIONS) {
                break;
            }
            addLikeBefore(out, seen, option, prefix);
        }
        return out;
    }

    private static void addLikeBefore(List<String> out, Set<String> seen, String value, String prefix) {
        if (value != null && !value.isBlank() && value.toLowerCase(Locale.ROOT).startsWith(prefix)
                && seen.add(value.toLowerCase(Locale.ROOT))) {
            out.add(value);
        }
    }

    @Test
    void cappedSuggestionsMatchSortingEverything() {
        Random random = new Random(42);
        String letters = "aAbBcCdD";
        List<String> options = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            StringBuilder word = new StringBuilder();
            int length = 1 + random.nextInt(6);
            for (int j = 0; j < length; j++) {
                word.append(letters.charAt(random.nextInt(letters.length())));
            }
            options.add(word.toString());
        }
        options.add(null);
        options.add(" ");
        options.add("");
        List<String> examples = List.of("Abba", "cab", "d");
        Args.RsArg<String> arg = new Args.RsArg<>(examples, false, null) {
            @Override
            protected String read(CommandSender sender, String raw) {
                return raw;
            }

            @Override
            protected Collection<String> options(CommandSender sender) {
                return options;
            }
        };
        for (String typed : List.of("", "a", "B", "ab", "CaD", "dddd", "zz")) {
            assertEquals(sortEverything(examples, options, typed), arg.suggest(SENDER, typed, "x"), typed);
        }
        List<String> reversed = new ArrayList<>(options);
        java.util.Collections.reverse(reversed);
        options.clear();
        options.addAll(reversed);
        for (String typed : List.of("", "a", "Bc")) {
            assertEquals(sortEverything(examples, options, typed), arg.suggest(SENDER, typed, "x"), typed);
        }
    }
}
