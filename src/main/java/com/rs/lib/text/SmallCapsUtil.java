package com.rs.lib.text;

/**
 * Small caps: maps {@code a-z} and {@code A-Z} one to one to Unicode small capital glyphs.
 *
 * <p>{@code x} has no small glyph and stays {@code x}; {@code q} uses U+01EB. Accented vowels
 * (and {@code ü}) lose the accent and become the small vowel; {@code Ñ} and {@code ñ} keep
 * their own glyph. Legacy codes ({@code &} and {@code §}, including the 14 character hex
 * sequence), complete MiniMessage tags (so text inside {@code hover:show_text:'...'} is left
 * alone) and RsLib bracket tags are copied unchanged. The output has the same length as the input.</p>
 */
public final class SmallCapsUtil {

    /** Small glyphs for {@code a..z}. */
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟ"
            + "ᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
    /** Accented vowels and the vowel each one becomes. */
    private static final String ACCENTED = "áàâäéèêëíìîïóòôöúùûüÁÀÂÄÉÈÊËÍÌÎÏÓÒÔÖÚÙÛÜ";
    private static final String ACCENT_BASE = "aaaaeeeeiiiioooouuuuaaaaeeeeiiiioooouuuu";

    private SmallCapsUtil() {
    }

    /** Small caps for one character, or the character itself. */
    public static char toSmall(char c) {
        if (c >= 'a' && c <= 'z') {
            return SMALL.charAt(c - 'a');
        }
        if (c >= 'A' && c <= 'Z') {
            return SMALL.charAt(c - 'A');
        }
        int accented = ACCENTED.indexOf(c);
        if (accented >= 0) {
            return SMALL.charAt(ACCENT_BASE.charAt(accented) - 'a');
        }
        return c;
    }

    /** Whether {@code c} is one of the small capital glyphs this class writes. */
    public static boolean isSmallGlyph(char c) {
        return c != 'x' && SMALL.indexOf(c) >= 0;
    }

    /**
     * Converts the whole text to small caps, skipping codes and tags. Returns the same
     * instance when nothing changed.
     */
    public static String smallCaps(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        char[] out = null;
        int n = s.length();
        int i = 0;
        while (i < n) {
            int skip = skipLength(s, i);
            if (skip > 0) {
                i += skip;
                continue;
            }
            char c = s.charAt(i);
            char small = toSmall(c);
            if (small != c) {
                if (out == null) {
                    out = s.toCharArray();
                }
                out[i] = small;
            }
            i++;
        }
        return out == null ? s : new String(out);
    }

    /**
     * Applies small caps to every {@code [small]...[/small]} span (an unclosed span runs to the
     * end) and removes those tags; a stray {@code [/small]} or a repeated {@code [small]} is
     * dropped. Other text is unchanged.
     */
    public static String applySmallTag(String s) {
        if (s == null || s.indexOf('[') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        boolean inSmall = false;
        int segment = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) == '[') {
                int open = Markup.bracketAt(s, i, Markup.SMALL);
                int close = open > 0 ? 0 : Markup.bracketAt(s, i, Markup.SMALL_CLOSE);
                if (open > 0 || close > 0) {
                    String piece = s.substring(segment, i);
                    out.append(inSmall ? smallCaps(piece) : piece);
                    inSmall = open > 0;
                    i += open + close;
                    segment = i;
                    continue;
                }
            }
            i++;
        }
        String piece = s.substring(segment);
        out.append(inSmall ? smallCaps(piece) : piece);
        return out.toString();
    }

    /** Characters to copy unchanged at {@code i}: a code, a tag or an escape; 0 for a plain character. */
    private static int skipLength(String s, int i) {
        char c = s.charAt(i);
        if (c == '&' || c == '§') {
            int len = Markup.codeLength(s, i, c);
            if (len > 0) {
                return len;
            }
            if (c == '§' && i + 1 < s.length()) {
                return 2;
            }
            return 0;
        }
        if (c == '<') {
            int end = Markup.tagEnd(s, i);
            return end > 0 ? end - i : 0;
        }
        if (Markup.escapeAt(s, i)) {
            return 2;
        }
        if (c == '[') {
            for (String tag : Markup.BRACKET_TAGS) {
                int len = Markup.bracketAt(s, i, tag);
                if (len > 0) {
                    return len;
                }
            }
        }
        return 0;
    }
}
