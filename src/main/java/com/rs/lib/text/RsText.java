package com.rs.lib.text;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import com.rs.lib.Ph;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The text pipeline: turns config text ({@code &} codes, {@code &#RRGGBB}, MiniMessage and the
 * RsLib tags {@code [center]}, {@code [rgb]}, {@code [small]}, {@code [noprefix]}) into
 * Adventure components or legacy strings.
 *
 * <p>The full render ({@link #color}) always runs in this order:</p>
 * <ol>
 *   <li>Section signs: {@code §X} and {@code §x§R§R§G§G§B§B} become {@code &X} and
 *       {@code &#RRGGBB} (MiniMessage rejects a raw section code).</li>
 *   <li>Leading tags {@code [center] [rgb] [small] [noprefix]} (any order and case) are taken
 *       off; {@code [noprefix]} only matters to lang. {@code [small]} and {@code [rgb]} are
 *       spans that can also open mid-line and close with {@code [/small]} / {@code [/rgb]}
 *       (or run to the end); small caps are applied before the gradient. {@code [center]} and
 *       {@code [noprefix]} written mid-line are shown as written.</li>
 *   <li>{@code [center]} pads the legacy text, the last legacy step.</li>
 *   <li>Legacy to MiniMessage: a legacy color code turns off the decorations opened by
 *       earlier legacy format codes, like vanilla; a {@code <} that cannot open a tag is
 *       escaped.</li>
 *   <li>MiniMessage (every standard tag).</li>
 *   <li>Literal markers (see {@link #literal}) are turned back into their characters.</li>
 * </ol>
 *
 * <p>Full renders of lines up to 256 characters are kept in a shared LRU cache of 2048 lines.
 * Lines that already hold per-send values (a filled template, PlaceholderAPI output) are
 * rendered with {@link #colorUncached} instead, so they do not push config lines out. Every
 * method is static, pure and safe from any thread.</p>
 */
public final class RsText {

    /** Every standard tag, no post-processing (renders compact explicitly). */
    static final MiniMessage FULL_UNCOMPACTED = MiniMessage.builder()
            .postProcessor(UnaryOperator.identity())
            .build();
    private static final MiniMessage COSMETIC = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(),
                    StandardTags.rainbow(), StandardTags.reset()))
            .postProcessor(UnaryOperator.identity())
            .build();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private RsText() {
    }

    // ------------------------------------------------------------------ renders

    /** Full render to a component (cached). Null gives an empty component. */
    public static Component color(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        return RenderCache.get(s, RsText::render);
    }

    /**
     * The same component as {@link #color}, rendered without the shared cache: for text that
     * already holds per-player or one-off values and would only push config lines out of it.
     */
    public static Component colorUncached(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        return render(s);
    }

    /** MiniMessage only: no RsLib tags and no legacy conversion. */
    public static Component mini(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        return finish(parse(FULL_UNCOMPACTED, s), Markup.hasMarker(s));
    }

    /**
     * The full pipeline with a MiniMessage that only knows colors, decorations, gradient,
     * rainbow and reset: click, hover, insertion, font, keybind, translatable, selector and any
     * other tag stay as inert text. The safe render for text written by players. Not cached.
     */
    public static Component cosmetic(String s) {
        if (s == null || s.isEmpty()) {
            return Component.empty();
        }
        String prepared = toMiniMessage(s, new LegacyConverter());
        return finish(parse(COSMETIC, prepared), Markup.hasMarker(prepared));
    }

    /** The visible text of the full render, without any style. */
    public static String plain(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return PLAIN.serialize(color(s));
    }

    /** {@link #plain} without the shared cache, for text written by players. */
    static String plainUncached(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return PLAIN.serialize(render(s));
    }

    /** Number of code points of {@link #plain}. */
    public static int visibleLength(String s) {
        String p = plain(s);
        return p.codePointCount(0, p.length());
    }

    /**
     * The full render as a legacy string with section signs and BungeeCord hex
     * ({@code §x§r§r§g§g§b§b}); a gradient becomes one hex per glyph. For APIs that need a
     * String, such as PlaceholderAPI.
     */
    public static String section(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return SECTION.serialize(color(s));
    }

    /**
     * Only the legacy phase: RsLib tags applied, {@code &} codes to section signs and hex to
     * the lower-case BungeeCord form. MiniMessage tags are left as written; literal markers
     * are turned back into their characters.
     */
    public static String colorLegacy(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return unwrapLiterals(LegacyConverter.toSectionCodes(prefixPhase(LegacyConverter.normalizeSections(s))));
    }

    /** {@link #color} for each line; a null or empty list gives an empty mutable list. */
    public static List<Component> colorList(List<String> lines) {
        List<Component> out = new ArrayList<>(lines == null ? 0 : lines.size());
        if (lines != null) {
            for (String line : lines) {
                out.add(color(line));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ text helpers

    /**
     * Makes {@code raw} safe to insert into a template: each character a later stage could read
     * as an instruction ({@code < & % [ \ {}) becomes a Unicode noncharacter (U+FDD0..U+FDD5, in
     * that order), section signs are dropped and so are those noncharacters if the text already
     * had them (so nobody can forge an escape). Every render turns them back at the very end;
     * {@link #unwrapLiterals} does it for text that leaves the pipeline as a String.
     */
    public static String literal(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = null;
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            int marked = Markup.MARKED.indexOf(c);
            boolean drop = c == '§' || Markup.isMarker(c);
            if (marked < 0 && !drop) {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(n);
                out.append(raw, 0, i);
            }
            if (!drop) {
                out.append((char) (Markup.MARKER_FIRST + marked));
            }
        }
        return out == null ? raw : out.toString();
    }

    /** Turns literal markers back into the characters they stand for. */
    public static String unwrapLiterals(String s) {
        if (s == null || !Markup.hasMarker(s)) {
            return s;
        }
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (Markup.isMarker(chars[i])) {
                chars[i] = Markup.unmark(chars[i]);
            }
        }
        return new String(chars);
    }

    /** PlaceholderAPI output to pipeline form: section-sign codes become {@code &} codes. */
    public static String normalizePapiOutput(String s) {
        return LegacyConverter.normalizeSections(s);
    }

    /**
     * Replaces local placeholders {@code %key%} and {@code {key}} in one pass. The last pair
     * with a key wins; literal pairs are encoded with {@link #literal}. Unresolved tokens stay
     * intact and values are not scanned again. {@code :raw}, {@code :short} and
     * {@code :grouped} reformat a numeric value when the whole token does not resolve.
     */
    public static String applyLocals(String s, Ph... phs) {
        if (s == null || phs == null || phs.length == 0) {
            return s;
        }
        return Locals.apply(s, Locals.resolverOf(phs));
    }

    /** Same as {@link #applyLocals(String, Ph...)} with a resolver; null means "leave the token". */
    public static String applyLocals(String s, Function<String, String> resolver) {
        return Locals.apply(s, resolver);
    }

    /** Small caps over the whole text, codes and tags untouched. */
    public static String smallCaps(String s) {
        return SmallCapsUtil.smallCaps(s);
    }

    /**
     * The legacy phase of RsLib tags: leading {@code [center] [rgb] [small] [noprefix]} taken
     * off, {@code [small]} and {@code [rgb]} spans expanded and the line centered. Section
     * codes are normalized first; the result uses {@code &} codes.
     */
    public static String applyPrefixTags(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return prefixPhase(LegacyConverter.normalizeSections(s));
    }

    /**
     * Puts {@code prefix} after the leading {@code [center] [rgb] [small]} tags of {@code line},
     * so they apply to it too; a line with {@code [noprefix]} among them is returned as it is.
     */
    public static String insertPrefix(String line, String prefix) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '[') {
            String tag = Markup.leadingTagAt(line, i);
            if (tag == null) {
                break;
            }
            if (tag.equals(Markup.NOPREFIX)) {
                return line;
            }
            i += tag.length();
        }
        return line.substring(0, i) + prefix + line.substring(i);
    }

    /** Empties the shared render cache. */
    public static void clearCache() {
        RenderCache.clear();
    }

    // ------------------------------------------------------------------ templates

    /**
     * Prepares a line that will be rendered many times with different placeholder values.
     * {@code compile(raw).render(phs)} is always equal to {@code color(applyLocals(raw, phs))};
     * when the shape of the line allows it the line is parsed once and only the values are
     * put in on each render.
     */
    public static Template compile(String rawLine) {
        return new Template(rawLine);
    }

    /**
     * A compiled line, see {@link #compile}. Immutable for callers and safe from any thread.
     */
    public static final class Template {
        private final String raw;
        private final TemplateEngine engine;

        private Template(String raw) {
            this.raw = raw;
            this.engine = raw == null ? null : new TemplateEngine(raw);
        }

        /** The line as written. */
        public String raw() {
            return raw;
        }

        /**
         * Whether the line can hold a placeholder at all ({@code %} or <code>{</code>); a line
         * without one renders the same whatever the values, so its render can be kept.
         */
        public boolean hasPlaceholders() {
            return engine != null && engine.mayHaveTokens();
        }

        /** Same result as {@code color(applyLocals(raw(), phs))}. */
        public Component render(Ph... phs) {
            return render(Locals.resolverOf(phs));
        }

        /** Same result as {@code color(applyLocals(raw(), resolver))}. */
        public Component render(Function<String, String> resolver) {
            if (engine == null) {
                return Component.empty();
            }
            return engine.render(resolver);
        }
    }

    // ------------------------------------------------------------------ internals

    /** The uncached full render. */
    static Component render(String s) {
        String prepared = toMiniMessage(s, new LegacyConverter());
        return finish(parse(FULL_UNCOMPACTED, prepared), Markup.hasMarker(prepared));
    }

    /** Steps 1 to 4: the MiniMessage input for {@code s}. */
    static String toMiniMessage(String s, LegacyConverter converter) {
        return converter.toMini(prefixPhase(LegacyConverter.normalizeSections(s)));
    }

    /** Compacts and restores literal markers, the shared last step of every render. */
    static Component finish(Component parsed, boolean markers) {
        Component compact = parsed.compact();
        return markers ? restoreLiterals(compact) : compact;
    }

    private static Component parse(MiniMessage mm, String s) {
        try {
            return mm.deserialize(s);
        } catch (RuntimeException unparsable) {
            return Component.text(s);
        }
    }

    /** Leading tags, spans and centering on a section-normalized line. */
    static String prefixPhase(String s) {
        int i = 0;
        boolean center = false;
        boolean rgb = false;
        boolean small = false;
        while (i < s.length() && s.charAt(i) == '[') {
            String tag = Markup.leadingTagAt(s, i);
            if (tag == null) {
                break;
            }
            switch (tag) {
                case Markup.CENTER -> center = true;
                case Markup.RGB -> rgb = true;
                case Markup.SMALL -> small = true;
                default -> {
                    // [noprefix] only matters to lang.
                }
            }
            i += tag.length();
        }
        String rest = i == 0 ? s : s.substring(i);
        if (!center && !rgb && !small && !Markup.hasBracketTag(rest)) {
            return rest;
        }
        if (small) {
            rest = Markup.SMALL + rest;
        }
        if (rgb) {
            rest = Markup.RGB + rest;
        }
        rest = SmallCapsUtil.applySmallTag(rest);
        rest = RgbGradientUtil.applyRgbTag(rest);
        if (center) {
            rest = CenterUtil.center(rest);
        }
        return rest;
    }

    /** Click actions whose value is text; the same set on every Paper 1.21.x. */
    private static final Set<ClickEvent.Action> TEXT_CLICKS = EnumSet.of(ClickEvent.Action.OPEN_URL,
            ClickEvent.Action.RUN_COMMAND, ClickEvent.Action.SUGGEST_COMMAND, ClickEvent.Action.COPY_TO_CLIPBOARD);

    /**
     * Newer Adventure (Paper 1.21.6+) marks these String forms deprecated in favor of
     * {@code ClickEvent.Payload}, which Paper 1.21.4 does not have; the String forms work on both.
     */
    @SuppressWarnings("deprecation")
    private static String clickValue(ClickEvent click) {
        return click.value();
    }

    @SuppressWarnings("deprecation")
    private static ClickEvent textClick(ClickEvent.Action action, String value) {
        return ClickEvent.clickEvent(action, value);
    }

    /**
     * Turns literal markers back in text, hover text, click values and insertions. Returns the
     * same instance when nothing changed.
     */
    static Component restoreLiterals(Component c) {
        Component out = restoreContent(c);
        out = restoreHover(out);
        out = restoreClick(out);
        out = restoreInsertion(out);
        return restoreChildren(out);
    }

    private static Component restoreContent(Component c) {
        if (c instanceof TextComponent text) {
            String content = text.content();
            String restored = unwrapLiterals(content);
            if (!restored.equals(content)) {
                return text.content(restored);
            }
        }
        return c;
    }

    private static Component restoreHover(Component c) {
        HoverEvent<?> hover = c.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT) {
            Component shown = (Component) hover.value();
            Component restored = restoreLiterals(shown);
            if (restored != shown) {
                return c.hoverEvent(HoverEvent.showText(restored));
            }
        }
        return c;
    }

    private static Component restoreClick(Component c) {
        ClickEvent click = c.clickEvent();
        if (click != null && TEXT_CLICKS.contains(click.action())) {
            String value = clickValue(click);
            String restored = unwrapLiterals(value);
            if (!restored.equals(value)) {
                return c.clickEvent(textClick(click.action(), restored));
            }
        }
        return c;
    }

    private static Component restoreInsertion(Component c) {
        String insertion = c.insertion();
        if (insertion != null) {
            String restored = unwrapLiterals(insertion);
            if (!restored.equals(insertion)) {
                return c.insertion(restored);
            }
        }
        return c;
    }

    private static Component restoreChildren(Component out) {
        List<Component> children = out.children();
        List<Component> mapped = null;
        for (int k = 0; k < children.size(); k++) {
            Component child = children.get(k);
            Component restored = restoreLiterals(child);
            if (restored != child) {
                if (mapped == null) {
                    mapped = new ArrayList<>(children);
                }
                mapped.set(k, restored);
            }
        }
        return mapped == null ? out : out.children(mapped);
    }
}
