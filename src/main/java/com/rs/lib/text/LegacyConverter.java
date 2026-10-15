package com.rs.lib.text;

import java.util.Locale;

/**
 * Legacy phase helpers: section-sign normalization, {@code &} codes to MiniMessage tags and
 * {@code &} codes to section-sign strings.
 *
 * <p>The MiniMessage conversion keeps the vanilla rule that a legacy COLOR code turns off
 * the decorations opened by earlier legacy FORMAT codes: the color tag is written, then the
 * negation of each decoration that is still on (unless the same run of codes turns it on
 * again). Tags the author wrote keep plain MiniMessage meaning. A {@code <} that cannot open
 * a tag is escaped. An instance remembers whether a tag scan passed over a template
 * sentinel, which the template fast path uses to reject unsafe shapes.</p>
 */
final class LegacyConverter {

    private static final String[] COLOR_TAGS = {
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
        "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };
    /** Decoration bits, in the order their tags are written. */
    private static final int BOLD = 1;
    private static final int ITALIC = 2;
    private static final int UNDERLINED = 4;
    private static final int STRIKETHROUGH = 8;
    private static final int OBFUSCATED = 16;
    private static final int[] DECO_BITS = {BOLD, ITALIC, UNDERLINED, STRIKETHROUGH, OBFUSCATED};
    private static final String[] DECO_TAGS = {"bold", "italic", "underlined", "strikethrough", "obfuscated"};

    private final boolean[] crossed = new boolean[1];
    private final boolean escapeLt;

    /** Decorations turned on by legacy format codes and not yet turned off. */
    private int active;

    LegacyConverter() {
        this(true);
    }

    private LegacyConverter(boolean escapeLt) {
        this.escapeLt = escapeLt;
    }

    /** Whether a tag scan passed over a template sentinel during the last conversions. */
    boolean crossedSentinel() {
        return crossed[0];
    }

    // ------------------------------------------------------------------ section signs

    /**
     * Turns every section-sign code into its {@code &} form: {@code §x§R§R§G§G§B§B} becomes
     * {@code &#RRGGBB} and {@code §X} becomes {@code &X}. Other section signs stay. Returns the
     * same instance when the text has no section sign.
     */
    static String normalizeSections(String s) {
        if (s == null || s.indexOf('§') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '§') {
                int len = Markup.codeLength(s, i, '§');
                if (Markup.isHexCode(len)) {
                    out.append("&#").append(Markup.hexOf(s, i, len));
                    i += len;
                    continue;
                }
                if (len == Markup.CODE_LENGTH) {
                    out.append('&').append(s.charAt(i + 1));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * {@code &} codes to section-sign codes: {@code &X} to {@code §x} (lower case) and both hex
     * forms to the BungeeCord form {@code §x§r§r§g§g§b§b} in lower case. MiniMessage tags and
     * everything else stay as written.
     */
    static String toSectionCodes(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        int n = s.length();
        int i = 0;
        while (i < n) {
            int len = Markup.codeLength(s, i, '&');
            if (Markup.isHexCode(len)) {
                out.append("§x");
                String hex = Markup.hexOf(s, i, len).toLowerCase(Locale.ROOT);
                for (int k = 0; k < 6; k++) {
                    out.append('§').append(hex.charAt(k));
                }
                i += len;
            } else if (len == Markup.CODE_LENGTH) {
                out.append('§').append(Character.toLowerCase(s.charAt(i + 1)));
                i += 2;
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ legacy to MiniMessage

    /** Converts legacy codes in {@code s} (already section-normalized) to MiniMessage tags. */
    String toMini(String s) {
        int n = s.length();
        StringBuilder out = new StringBuilder(n + 32);
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '&') {
                int end = run(s, i, out);
                if (end > i) {
                    i = end;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (Markup.escapeAt(s, i)) {
                out.append(c).append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                if (i + 1 < n && Markup.tagStart(s.charAt(i + 1))) {
                    int end = Markup.tagEnd(s, i, crossed);
                    if (end > 0) {
                        appendTag(s.substring(i, end), out);
                        i = end;
                        continue;
                    }
                    out.append(c);
                } else if (escapeLt) {
                    out.append("\\<");
                } else {
                    out.append(c);
                }
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Copies an author tag; a reset clears the legacy state, a hover gets its quoted text converted. */
    private void appendTag(String tag, StringBuilder out) {
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.equals("<reset>")) {
            active = 0;
            out.append(tag);
            return;
        }
        if (lower.startsWith("<hover:") && tag.indexOf('&') >= 0) {
            out.append(convertQuoted(tag));
            return;
        }
        out.append(tag);
    }

    /** Converts the legacy codes inside the quoted arguments of a tag (hover text). */
    private static String convertQuoted(String tag) {
        StringBuilder out = new StringBuilder(tag.length() + 16);
        int n = tag.length();
        int i = 0;
        while (i < n) {
            char c = tag.charAt(i);
            if (c != '\'' && c != '"') {
                out.append(c);
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n) {
                char d = tag.charAt(j);
                if (d == '\\' && j + 1 < n && (tag.charAt(j + 1) == c || tag.charAt(j + 1) == '\\')) {
                    j += 2;
                    continue;
                }
                if (d == c) {
                    break;
                }
                j++;
            }
            out.append(c);
            out.append(new LegacyConverter(false).toMini(tag.substring(i + 1, Math.min(j, n))));
            if (j < n) {
                out.append(c);
            }
            i = j + 1;
        }
        return out.toString();
    }

    /**
     * Reads the run of consecutive legacy codes at {@code i}, writes its tags and returns the
     * index after the run ({@code i} when there is no code).
     */
    private int run(String s, int i, StringBuilder out) {
        Run run = new Run();
        int end = run.read(s, i);
        if (end > i) {
            write(run, out);
        }
        return end;
    }

    /** Writes the tags of a run and updates the decorations that stay on. */
    private void write(Run run, StringBuilder out) {
        if (run.reset) {
            out.append("<reset>");
            active = 0;
        }
        if (run.color != null) {
            out.append('<').append(run.color).append('>');
            for (int k = 0; k < DECO_BITS.length; k++) {
                if ((active & DECO_BITS[k]) != 0 && (run.formats & DECO_BITS[k]) == 0) {
                    out.append("<!").append(DECO_TAGS[k]).append('>');
                }
            }
            active = 0;
        }
        for (int k = 0; k < run.order.length(); k++) {
            int bit = bitOf(run.order.charAt(k));
            out.append('<').append(DECO_TAGS[Integer.numberOfTrailingZeros(bit)]).append('>');
            active |= bit;
        }
    }

    private static int bitOf(char code) {
        return switch (code) {
            case 'l' -> BOLD;
            case 'o' -> ITALIC;
            case 'n' -> UNDERLINED;
            case 'm' -> STRIKETHROUGH;
            default -> OBFUSCATED;
        };
    }

    /**
     * What a run of consecutive legacy codes ends up meaning: a later color or {@code &r}
     * drops the formats written before it in the same run.
     */
    private static final class Run {
        boolean reset;
        String color;
        int formats;
        final StringBuilder order = new StringBuilder(4);

        /** Reads the codes from {@code i}; returns the index after the last one ({@code i} when none). */
        int read(String s, int i) {
            int j = i;
            while (j < s.length()) {
                int len = Markup.codeLength(s, j, '&');
                if (len == 0) {
                    break;
                }
                if (Markup.isHexCode(len)) {
                    setColor("#" + Markup.hexOf(s, j, len));
                } else {
                    code(Character.toLowerCase(s.charAt(j + 1)));
                }
                j += len;
            }
            return j;
        }

        private void code(char code) {
            if (code == 'r') {
                reset = true;
                setColor(null);
            } else if (Markup.isColorChar(code)) {
                setColor(COLOR_TAGS[Character.digit(code, 16)]);
            } else {
                int bit = bitOf(code);
                if ((formats & bit) == 0) {
                    formats |= bit;
                    order.append(code);
                }
            }
        }

        private void setColor(String value) {
            color = value;
            formats = 0;
            order.setLength(0);
        }
    }
}
