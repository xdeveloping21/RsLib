package com.rs.lib.lang;

import static com.rs.lib.text.Renders.assertSameRender;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.text.RsText;

import net.kyori.adventure.title.Title;

/** How a message line renders: PlaceholderAPI only when it can change something, kept static renders, titles. */
class LangRenderTest {

    /** A stand-in for PlaceholderAPI that records what it was asked and replaces {@code %player_name%}. */
    private static final class FakePapi implements UnaryOperator<String> {
        final List<String> seen = new ArrayList<>();

        @Override
        public String apply(String text) {
            seen.add(text);
            return text.replace("%player_name%", "§cSteve");
        }
    }

    @Test
    void papiRunsOnlyWhenATokenIsLeft() {
        FakePapi papi = new FakePapi();
        String[][] cases = {
            {"&7Discount: &a50%", null},
            {"&7Hi %who%, you have {n}", null},
            {"&7Rank: {r}", null},
            {"[rgb]Sale 50% {n}", null},
        };
        Ph[] phs = {Ph.of("who", "Alex"), Ph.of("n", "3"), Ph.literal("r", "%vault_rank%")};
        for (String[] c : cases) {
            String raw = c[0];
            assertSameRender(RsText.color(RsText.applyLocals(raw, phs)), new RsLang.Compiled(raw).render(papi, phs), raw);
        }
        assertEquals(List.of(), papi.seen);
    }

    @Test
    void papiResolvesWhatIsLeftAfterTheLocals() {
        FakePapi papi = new FakePapi();
        String raw = "&7Hello %player_name%, {n} new";
        Ph[] phs = {Ph.of("n", "2")};
        assertSameRender(RsText.color("&7Hello &cSteve, 2 new"), new RsLang.Compiled(raw).render(papi, phs), raw);
        assertEquals(List.of("&7Hello %player_name%, 2 new"), papi.seen);

        // A formatted value can carry a token of its own.
        papi.seen.clear();
        new RsLang.Compiled("Rank: {r}").render(papi, new Ph[] {Ph.of("r", "%player_name%")});
        assertEquals(List.of("Rank: %player_name%"), papi.seen);
    }

    @Test
    void withoutPapiTheTemplateRendersTheSame() {
        String raw = "&7Hello %player_name%, {n} new 100%";
        Ph[] phs = {Ph.of("n", "2")};
        assertSameRender(RsText.color(RsText.applyLocals(raw, phs)), new RsLang.Compiled(raw).render(null, phs), raw);
    }

    @Test
    void lineWithoutTokensIsRenderedOnceEvenWithValues() {
        RsLang.Compiled line = new RsLang.Compiled("&aWelcome [small]home");
        var first = line.render(null, null);
        assertEquals(RsText.color("&aWelcome [small]home"), first);
        assertSame(first, line.render(new FakePapi(), new Ph[] {Ph.of("x", "y")}));
        assertSame(first, line.render(null, new Ph[0]));
    }

    @Test
    void prefixGoesOnTheSingleLineOnly() {
        RsLang.Line line = RsLang.Line.of("[center]&aHi {p}", "&8[&6S&8] ");
        Ph[] phs = {Ph.of("p", "Steve")};
        assertSameRender(RsText.color("[center]&8[&6S&8] &aHi Steve"), line.prefixed.render(null, phs));
        assertSameRender(RsText.color("[center]&aHi Steve"), line.plain.render(null, phs));
        RsLang.Line none = RsLang.Line.of("[noprefix]x", "P ");
        assertSame(none.plain, none.prefixed);
        RsLang.Line noPrefixSet = RsLang.Line.of("x", "");
        assertSame(noPrefixSet.plain, noPrefixSet.prefixed);
    }

    @Test
    void titleIsSplitOnceWithTheSameMeaning() {
        RsLang.TitleParts plain = RsLang.Line.of("&6Welcome {p}", "P ").title;
        assertNull(plain.subtitle());
        assertEquals(Title.Times.times(ticks(10), ticks(70), ticks(20)), plain.times());
        assertEquals(RsText.color("&6Welcome Steve"), plain.title().render(null, new Ph[] {Ph.of("p", "Steve")}));

        RsLang.TitleParts full = RsLang.Line.of("&6Hi {p};&7sub;5;bad;-3;extra", "P ").title;
        assertEquals(RsText.color("&6Hi Steve"), full.title().render(null, new Ph[] {Ph.of("p", "Steve")}));
        assertEquals(RsText.color("&7sub"), full.subtitle().render(null, null));
        assertEquals(Title.Times.times(ticks(5), ticks(70), ticks(0)), full.times());

        RsLang.TitleParts emptySub = RsLang.Line.of("A;", null).title;
        assertEquals(RsText.color(""), emptySub.subtitle().render(null, null));
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }
}
