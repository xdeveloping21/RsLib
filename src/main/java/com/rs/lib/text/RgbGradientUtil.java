package com.rs.lib.text;

import java.util.Locale;

/**
 * The {@code [rgb]} gradient: seven fixed anchor colors interpolated over the visible
 * characters that are not spaces, written as one {@code &#RRGGBB} per character.
 *
 * <p>The first character gets exactly the first anchor and the last character the last one.
 * Legacy color codes inside a span are dropped (the gradient decides the color) but clear the
 * accumulated formats; format codes ({@code &l &o &n &m &k}) accumulate and are written again
 * after every hex; {@code &r} clears them. Spaces are copied without taking a gradient step;
 * MiniMessage tags are copied as they are.</p>
 */
public final class RgbGradientUtil {

    /** The anchors, in order: magenta, blue, aqua, green, yellow, orange, red-orange. */
    private static final int[] ANCHORS = {0xF300F3, 0x5555FF, 0x55FFFF, 0x55FF55, 0xFCFF21, 0xFF9B00, 0xFF5327};

    private RgbGradientUtil() {
    }

    /**
     * Expands every {@code [rgb]...[/rgb]} span (an unclosed span runs to the end) into the
     * gradient. On {@code [/rgb]} the legacy color and formats active outside the span are
     * written again ({@code &r} plus the formats when there was no color), so the outer style
     * continues; a stray {@code [/rgb]} or a repeated {@code [rgb]} is dropped.
     */
    public static String applyRgbTag(String s) {
        if (s == null || s.indexOf('[') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() * 4);
        OuterStyle outer = new OuterStyle();
        StringBuilder span = null;
        int segment = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) == '[') {
                int open = Markup.bracketAt(s, i, "[rgb]");
                int close = open > 0 ? 0 : Markup.bracketAt(s, i, "[/rgb]");
                if (open > 0 || close > 0) {
                    if (span != null) {
                        span.append(s, segment, i);
                        if (close > 0) {
                            out.append(gradient(span.toString())).append(outer.codes());
                            span = null;
                        }
                    } else {
                        outer.track(s.substring(segment, i));
                        out.append(s, segment, i);
                        if (open > 0) {
                            span = new StringBuilder();
                        }
                    }
                    i += open + close;
                    segment = i;
                    continue;
                }
            }
            i++;
        }
        if (span != null) {
            span.append(s, segment, n);
            out.append(gradient(span.toString()));
        } else {
            out.append(s, segment, n);
        }
        return out.toString();
    }

    /** The gradient over all of {@code text}, which has no {@code [rgb]} tags. */
    public static String gradient(String text) {
        int total = countVisible(text);
        if (total == 0) {
            return stripColors(text);
        }
        StringBuilder out = new StringBuilder(text.length() * 10);
        StringBuilder formats = new StringBuilder(4);
        int step = 0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '&') {
                int len = Markup.codeLength(text, i, '&');
                if (len > 0) {
                    applyCode(text, i, len, formats);
                    i += len;
                    continue;
                }
            }
            if (c == '<') {
                int end = Markup.tagEnd(text, i);
                if (end > 0) {
                    out.append(text, i, end);
                    i = end;
                    continue;
                }
            }
            if (Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }
            int unit = unitLength(text, i);
            out.append("&#").append(hex(colorAt(step, total)));
            for (int k = 0; k < formats.length(); k++) {
                out.append('&').append(formats.charAt(k));
            }
            out.append(text, i, i + unit);
            step++;
            i += unit;
        }
        return out.toString();
    }

    /** The interpolated color for visible character {@code index} of {@code total}. */
    static int colorAt(int index, int total) {
        if (total <= 1 || index <= 0) {
            return ANCHORS[0];
        }
        if (index >= total - 1) {
            return ANCHORS[ANCHORS.length - 1];
        }
        double position = (double) index / (total - 1) * (ANCHORS.length - 1);
        int seg = Math.min((int) Math.floor(position), ANCHORS.length - 2);
        double f = position - seg;
        int a = ANCHORS[seg];
        int b = ANCHORS[seg + 1];
        int r = lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF, f);
        int g = lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF, f);
        int bl = lerp(a & 0xFF, b & 0xFF, f);
        return (r << 16) | (g << 8) | bl;
    }

    private static int lerp(int a, int b, double f) {
        return (int) Math.round(a + (b - a) * f);
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb);
    }

    private static void applyCode(String text, int i, int len, StringBuilder formats) {
        if (len != 2) {
            formats.setLength(0);
            return;
        }
        char code = Character.toLowerCase(text.charAt(i + 1));
        if (code == 'r' || Markup.isColorChar(code)) {
            formats.setLength(0);
        } else if (formats.indexOf(String.valueOf(code)) < 0) {
            formats.append(code);
        }
    }

    /** One visible unit at {@code i}: an escape pair, a surrogate pair or one character. */
    private static int unitLength(String text, int i) {
        char c = text.charAt(i);
        if (c == '\\' && i + 1 < text.length() && (text.charAt(i + 1) == '<' || text.charAt(i + 1) == '\\')) {
            return 2;
        }
        if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
            return 2;
        }
        return 1;
    }

    private static int countVisible(String text) {
        int count = 0;
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '&') {
                int len = Markup.codeLength(text, i, '&');
                if (len > 0) {
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
            if (!Character.isWhitespace(c)) {
                count++;
            }
            i += Character.isWhitespace(c) ? 1 : unitLength(text, i);
        }
        return count;
    }

    /** A span with nothing visible: its codes are dropped, the rest (spaces, tags) stays. */
    private static String stripColors(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int len = text.charAt(i) == '&' ? Markup.codeLength(text, i, '&') : 0;
            if (len > 0) {
                i += len;
            } else {
                out.append(text.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The legacy color and formats active outside the spans. */
    private static final class OuterStyle {
        private String color;
        private final StringBuilder formats = new StringBuilder(4);

        void track(String piece) {
            int i = 0;
            while (i < piece.length()) {
                int len = piece.charAt(i) == '&' ? Markup.codeLength(piece, i, '&') : 0;
                if (len == 0) {
                    i++;
                    continue;
                }
                char code = Character.toLowerCase(piece.charAt(i + 1));
                if (len != 2 || Markup.isColorChar(code)) {
                    color = len == 2 ? "&" + code : "&#" + Markup.hexOf(piece, i, len);
                    formats.setLength(0);
                } else if (code == 'r') {
                    color = null;
                    formats.setLength(0);
                } else if (formats.indexOf(String.valueOf(code)) < 0) {
                    formats.append(code);
                }
                i += len;
            }
        }

        String codes() {
            StringBuilder out = new StringBuilder(16);
            out.append(color == null ? "&r" : color);
            for (int k = 0; k < formats.length(); k++) {
                out.append('&').append(formats.charAt(k));
            }
            return out.toString();
        }
    }
}
