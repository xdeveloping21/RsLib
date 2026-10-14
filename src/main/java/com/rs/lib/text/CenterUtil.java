package com.rs.lib.text;

/**
 * Centers a chat line by prepending spaces, measuring in pixels of the default font.
 *
 * <p>Widths: most ASCII characters 5 px; {@code i ! : ; ' . , |} 1 px; {@code l} and the
 * backtick 2 px; {@code I t [ ] "} and space 3 px; {@code f k ( ) { } < >} 4 px; {@code @}
 * 6 px; anything unknown 4 px. Every glyph adds 1 px of spacing and bold adds 1 px except on
 * spaces. Small capital glyphs measure as upper case letters (the small {@code ɪ} as
 * {@code I}). Legacy codes are not measured: {@code &l} turns bold on, any color code or
 * {@code &r} turns it off. Complete MiniMessage tags are not measured either. The text is
 * centered on 154 px, half of the chat window; a wider text is returned unchanged.</p>
 */
public final class CenterUtil {

    /** Half of the default chat width, in pixels. */
    public static final int CENTER_PX = 154;

    private CenterUtil() {
    }

    /** The text with the spaces that center it in chat; codes in {@code &} or {@code §} form. */
    public static String center(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int width = width(text);
        int toCompensate = CENTER_PX - width / 2;
        int space = charWidth(' ') + 1;
        StringBuilder pad = new StringBuilder();
        int compensated = 0;
        while (compensated < toCompensate) {
            pad.append(' ');
            compensated += space;
        }
        return pad.length() == 0 ? text : pad + text;
    }

    /** Width of the visible text in pixels, spacing included. */
    public static int width(String text) {
        int px = 0;
        boolean bold = false;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '&' || c == '§') {
                int len = Markup.codeLength(text, i, c);
                if (len > 0) {
                    char code = Character.toLowerCase(text.charAt(i + 1));
                    if (len == 2 && code == 'l') {
                        bold = true;
                    } else if (len != 2 || code == 'r' || Markup.isColorChar(code)) {
                        bold = false;
                    }
                    i += len;
                    continue;
                }
            }
            if (c == '<') {
                int end = Markup.tagEnd(text, i);
                if (end > 0) {
                    i = end;
                    continue;
                }
            }
            if (c == '\\' && i + 1 < n && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
                i++;
                c = text.charAt(i);
            }
            if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            }
            px += charWidth(c) + 1;
            if (bold && c != ' ') {
                px++;
            }
            i++;
        }
        return px;
    }

    /** Pixel width of one glyph without spacing. */
    public static int charWidth(char c) {
        if (Markup.isMarker(c)) {
            c = Markup.unmark(c);
        }
        if (c == 'ɪ') {
            return 3;
        }
        if (SmallCapsUtil.isSmallGlyph(c)) {
            return 5;
        }
        switch (c) {
            case 'i', '!', ':', ';', '\'', '.', ',', '|':
                return 1;
            case 'l', '`':
                return 2;
            case 'I', 't', '[', ']', '"', ' ':
                return 3;
            case 'f', 'k', '(', ')', '{', '}', '<', '>':
                return 4;
            case '@':
                return 6;
            default:
                return c > ' ' && c < 127 ? 5 : 4;
        }
    }
}
