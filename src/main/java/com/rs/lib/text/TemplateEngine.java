package com.rs.lib.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/**
 * The engine behind {@link RsText.Template}: renders {@code color(applyLocals(raw, resolver))}
 * either the slow way (build the string, render it) or, when it is provably the same, by
 * parsing a skeleton once and only putting the values in.
 *
 * <p>The skeleton is the line after placeholder substitution with each value replaced by a
 * sentinel noncharacter (U+FDD6 onward). The fast path is used only when all of this holds,
 * otherwise the slow path runs:</p>
 * <ul>
 *   <li>The line has no {@code [rgb]}, {@code [small]} or {@code [center]} tag (they depend on
 *       the visible characters and the width) and no MiniMessage gradient, rainbow or pride
 *       (they color each character based on the length).</li>
 *   <li>Every value is non-empty and "plain": none of {@code & § < [ % { \} and no literal
 *       marker, so it cannot start a code, a tag, an escape or a bracket tag.</li>
 *   <li>No sentinel follows {@code & § \ <}, an unfinished {@code &#hex} or an unclosed
 *       {@code [} within ten characters, so no value can finish a construct begun before it.</li>
 *   <li>No tag scan of the legacy converter passes over a sentinel and MiniMessage's tokenizer
 *       would read each sentinel as plain text (outside any tag).</li>
 *   <li>The parsed skeleton holds each sentinel exactly once, in text content.</li>
 *   <li>After the values are put in, no text component that held a sentinel is blank
 *       (compaction treats blank text differently).</li>
 * </ul>
 * <p>Under these conditions MiniMessage tokenizes the skeleton and the real line identically
 * except for the text inside those components, and compaction does not depend on non-blank
 * text, so putting the values into the uncompacted skeleton and compacting gives the same
 * component as the slow path.</p>
 */
final class TemplateEngine {

    private static final Object UNSAFE = new Object();
    private static final int MAX_SLOTS = Markup.SENTINEL_LAST - Markup.SENTINEL_FIRST + 1;
    private static final int MAX_SKELETONS = 8;
    private static final String[] SLOW_WORDS = {
        "[rgb]", "[/rgb]", "[small]", "[/small]", "[center]", "gradient", "rainbow", "pride"
    };

    private final String raw;
    private final boolean slowOnly;
    private final Map<String, Object> skeletons = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > MAX_SKELETONS;
        }
    };

    TemplateEngine(String raw) {
        this.raw = raw;
        this.slowOnly = !Locals.mayHaveTokens(raw) || hasSentinel(raw) || needsSlowShape(raw);
    }

    /** Whether the fast path can ever be used for this line (for tests). */
    boolean fastCapable() {
        return !slowOnly;
    }

    Component render(Function<String, String> resolver) {
        if (resolver == null) {
            return RsText.color(raw);
        }
        if (slowOnly) {
            return RsText.color(Locals.apply(raw, resolver));
        }
        Pieces pieces = new Pieces(raw.length());
        Locals.scan(raw, resolver, pieces);
        String joined = pieces.joined.toString();
        if (pieces.values.isEmpty() || pieces.values.size() > MAX_SLOTS || !allPlain(pieces.values)) {
            return RsText.color(joined);
        }
        Component fast = fast(pieces.skeleton.toString(), pieces.values);
        return fast != null ? fast : RsText.color(joined);
    }

    /** The fast render, or null when this shape or these values need the slow path. */
    Component fast(String skeleton, List<String> values) {
        Object parsed;
        synchronized (skeletons) {
            parsed = skeletons.get(skeleton);
        }
        if (parsed == null) {
            parsed = analyze(skeleton, values.size());
            synchronized (skeletons) {
                skeletons.put(skeleton, parsed);
            }
        }
        if (parsed == UNSAFE) {
            return null;
        }
        Skeleton sk = (Skeleton) parsed;
        boolean[] blank = new boolean[1];
        Component filled = fill(sk.tree, values, blank);
        if (blank[0]) {
            return null;
        }
        return RsText.finish(filled, sk.markers);
    }

    // ------------------------------------------------------------------ analysis

    private static Object analyze(String skeleton, int slots) {
        String norm = LegacyConverter.normalizeSections(skeleton);
        for (int j = 0; j < norm.length(); j++) {
            if (Markup.isSentinel(norm.charAt(j)) && !safeContext(norm, j)) {
                return UNSAFE;
            }
        }
        LegacyConverter converter = new LegacyConverter();
        String prepared = converter.toMini(RsText.prefixPhase(norm));
        if (converter.crossedSentinel() || !plainTextForMiniMessage(prepared)) {
            return UNSAFE;
        }
        Component tree;
        try {
            tree = RsText.FULL_UNCOMPACTED.deserialize(prepared);
        } catch (RuntimeException unparsable) {
            return UNSAFE;
        }
        int[] counts = new int[MAX_SLOTS];
        count(tree, counts);
        for (int k = 0; k < MAX_SLOTS; k++) {
            if (counts[k] != (k < slots ? 1 : 0)) {
                return UNSAFE;
            }
        }
        return new Skeleton(tree, RsText.hasMarker(prepared));
    }

    /** No construct begun before {@code j} could be finished by the value at {@code j}. */
    static boolean safeContext(String s, int j) {
        if (j == 0) {
            return true;
        }
        char prev = s.charAt(j - 1);
        if (prev == '&' || prev == '§' || prev == '\\' || prev == '<') {
            return false;
        }
        int k = j - 1;
        int hex = 0;
        while (k >= 0 && hex < 6 && Markup.isHexDigit(s.charAt(k))) {
            k--;
            hex++;
        }
        if (hex < 6 && k >= 1 && s.charAt(k) == '#' && s.charAt(k - 1) == '&') {
            return false;
        }
        for (k = j - 1; k >= 0 && k >= j - 10; k--) {
            char c = s.charAt(k);
            if (c == ']') {
                break;
            }
            if (c == '[') {
                return false;
            }
        }
        return true;
    }

    /** Mirrors MiniMessage's tokenizer: true when every sentinel is read as plain text. */
    static boolean plainTextForMiniMessage(String s) {
        final int normal = 0;
        final int tag = 1;
        final int string = 2;
        int state = normal;
        char quote = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Markup.isSentinel(c) && state != normal) {
                return false;
            }
            if (c == '\\' && i + 1 < n) {
                char next = s.charAt(i + 1);
                if (state == normal && (next == '<' || next == '\\')) {
                    i++;
                    continue;
                }
                if (state == string && (next == quote || next == '\\')) {
                    i++;
                    continue;
                }
                if (state == tag && next == '<') {
                    state = normal;
                    i++;
                    continue;
                }
            }
            if (state == normal) {
                if (c == '<') {
                    state = tag;
                }
            } else if (state == tag) {
                if (c == '>') {
                    state = normal;
                } else if (c == '\'' || c == '"') {
                    state = string;
                    quote = c;
                }
            } else if (c == quote) {
                state = tag;
            }
        }
        return true;
    }

    private static void count(Component c, int[] counts) {
        if (c instanceof TextComponent text) {
            String content = text.content();
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (Markup.isSentinel(ch)) {
                    counts[ch - Markup.SENTINEL_FIRST]++;
                }
            }
        }
        for (Component child : c.children()) {
            count(child, counts);
        }
    }

    // ------------------------------------------------------------------ fill

    private static Component fill(Component c, List<String> values, boolean[] blank) {
        Component out = c;
        if (c instanceof TextComponent text && hasSentinel(text.content())) {
            String content = text.content();
            StringBuilder filled = new StringBuilder(content.length() + 16);
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (Markup.isSentinel(ch)) {
                    filled.append(values.get(ch - Markup.SENTINEL_FIRST));
                } else {
                    filled.append(ch);
                }
            }
            String result = filled.toString();
            if (allSpaces(result)) {
                blank[0] = true;
            }
            out = text.content(result);
        }
        List<Component> children = c.children();
        if (children.isEmpty()) {
            return out;
        }
        List<Component> mapped = null;
        for (int k = 0; k < children.size(); k++) {
            Component child = children.get(k);
            Component next = fill(child, values, blank);
            if (next != child) {
                if (mapped == null) {
                    mapped = new ArrayList<>(children);
                }
                mapped.set(k, next);
            }
        }
        return mapped == null ? out : out.children(mapped);
    }

    // ------------------------------------------------------------------ checks

    private static boolean allSpaces(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    private static boolean allPlain(List<String> values) {
        for (String v : values) {
            if (!isPlainValue(v)) {
                return false;
            }
        }
        return true;
    }

    /** Non-empty and free of {@code & § < [ % { \} and literal markers. */
    static boolean isPlainValue(String v) {
        if (v.isEmpty()) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&', '§', '<', '[', '%', '{', '\\':
                    return false;
                default:
                    if (Markup.isMarker(c)) {
                        return false;
                    }
            }
        }
        return true;
    }

    private static boolean hasSentinel(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Markup.isSentinel(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsSlowShape(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String word : SLOW_WORDS) {
            if (lower.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** A parsed skeleton: the uncompacted tree and whether it holds literal markers. */
    private record Skeleton(Component tree, boolean markers) {
    }

    /** Collects the scan: the joined line, the skeleton and the values. */
    private static final class Pieces implements Locals.Sink {
        final StringBuilder joined;
        final StringBuilder skeleton;
        final List<String> values = new ArrayList<>(4);

        Pieces(int size) {
            joined = new StringBuilder(size + 16);
            skeleton = new StringBuilder(size);
        }

        @Override
        public void text(String s, int from, int to) {
            joined.append(s, from, to);
            skeleton.append(s, from, to);
        }

        @Override
        public void value(String value) {
            joined.append(value);
            int slot = values.size();
            skeleton.append(slot < MAX_SLOTS ? (char) (Markup.SENTINEL_FIRST + slot) : Markup.SENTINEL_LAST);
            values.add(value);
        }
    }
}
