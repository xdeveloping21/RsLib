package com.rs.lib.text;

import java.util.ArrayList;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Test helper: the text pieces of a component with their effective (inherited) style. */
record Styled(String text, Style style) {

    static List<Styled> flatten(Component root) {
        List<Styled> out = new ArrayList<>();
        walk(root, Style.empty(), out);
        return out;
    }

    private static void walk(Component c, Style parent, List<Styled> out) {
        Style effective = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (c instanceof TextComponent text && !text.content().isEmpty()) {
            out.add(new Styled(text.content(), effective));
        }
        for (Component child : c.children()) {
            walk(child, effective, out);
        }
    }

    /** The style of the first piece whose text contains {@code needle}. */
    static Style styleOf(Component root, String needle) {
        for (Styled s : flatten(root)) {
            if (s.text().contains(needle)) {
                return s.style();
            }
        }
        throw new AssertionError("no text '" + needle + "' in " + root);
    }

    /** The color of the first piece whose text is exactly {@code text}. */
    static TextColor colorExact(Component root, String text) {
        for (Styled s : flatten(root)) {
            if (s.text().equals(text)) {
                return s.style().color();
            }
        }
        throw new AssertionError("no piece '" + text + "' in " + root);
    }

    static boolean bold(Component root, String needle) {
        return styleOf(root, needle).decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
    }

    static TextColor color(Component root, String needle) {
        return styleOf(root, needle).color();
    }
}
