package com.rs.lib.text;

import java.util.Locale;

/**
 * Low-level recognizers shared by every text stage: legacy codes, MiniMessage tags, the
 * bracket tags of RsLib and the reserved characters.
 *
 * <p>All methods are pure and work on plain {@code String}s, so they are safe from any
 * thread. Positions are {@code char} indexes.</p>
 */
final class Markup {

    /** First literal marker; {@code literal} maps {@code < & % [ \ {} to U+FDD0..U+FDD5. */
    static final char MARKER_FIRST = '﷐';
    /** Last literal marker. */
    static final char MARKER_LAST = '﷕';
    /** The characters the literal markers stand for, in marker order. */
    static final String MARKED = "<&%[\\{";

    /** First template sentinel (noncharacters right after the literal markers). */
    static final char SENTINEL_FIRST = '﷖';
    /** Last template sentinel. */
    static final char SENTINEL_LAST = '﷯';

    /** The bracket tags RsLib reads, lower case. */
    static final String[] BRACKET_TAGS = {"[center]", "[rgb]", "[small]", "[noprefix]", "[/rgb]", "[/small]"};

    private Markup() {
    }

    static boolean isMarker(char c) {
        return c >= MARKER_FIRST && c <= MARKER_LAST;
    }

    static boolean isSentinel(char c) {
        return c >= SENTINEL_FIRST && c <= SENTINEL_LAST;
    }

    /** Whether {@code c} can open a MiniMessage tag right after {@code <}. */
    static boolean tagStart(char c) {
        return c == '/' || c == '#' || c == '!' || c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** A legacy code character: a color {@code 0-9 a-f}, a format {@code k-o} or {@code r}. */
    static boolean isCodeChar(char c) {
        char l = Character.toLowerCase(c);
        return (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f') || (l >= 'k' && l <= 'o') || l == 'r';
    }

    /** A legacy color character {@code 0-9 a-f}. */
    static boolean isColorChar(char c) {
        char l = Character.toLowerCase(c);
        return (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f');
    }

    /**
     * Length of the legacy code that starts at {@code i} with the code character {@code sign}
     * ({@code &} or {@code §}): 14 for {@code &x&R&R&G&G&B&B}, 8 for {@code &#RRGGBB},
     * 2 for {@code &X}, 0 when there is no code.
     */
    static int codeLength(String s, int i, char sign) {
        int n = s.length();
        if (i + 1 >= n || s.charAt(i) != sign) {
            return 0;
        }
        char next = s.charAt(i + 1);
        if (next == '#') {
            if (i + 8 <= n) {
                for (int k = i + 2; k < i + 8; k++) {
                    if (!isHexDigit(s.charAt(k))) {
                        return 0;
                    }
                }
                return 8;
            }
            return 0;
        }
        if ((next == 'x' || next == 'X') && i + 14 <= n) {
            boolean hex = true;
            for (int k = i + 2; k < i + 14; k += 2) {
                if (s.charAt(k) != sign || !isHexDigit(s.charAt(k + 1))) {
                    hex = false;
                    break;
                }
            }
            if (hex) {
                return 14;
            }
        }
        return isCodeChar(next) ? 2 : 0;
    }

    /** The six hex digits of a 14 or 8 character hex code at {@code i}, upper case. */
    static String hexOf(String s, int i, int length) {
        StringBuilder hex = new StringBuilder(6);
        if (length == 8) {
            hex.append(s, i + 2, i + 8);
        } else {
            for (int k = i + 3; k < i + 14; k += 2) {
                hex.append(s.charAt(k));
            }
        }
        return hex.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * End (exclusive) of the complete MiniMessage tag that starts at {@code i}, or -1.
     *
     * <p>Follows MiniMessage's own tokenizer: quotes inside a tag open a string that may hold
     * {@code >}, a backslash escapes the quote or itself inside a string, and a new {@code <}
     * or an escaped {@code \<} before the closing {@code >} means this was not a tag.</p>
     */
    static int tagEnd(String s, int i) {
        return tagEnd(s, i, null);
    }

    /** Same as {@link #tagEnd(String, int)}; sets {@code crossed[0]} when the scan passes a sentinel. */
    static int tagEnd(String s, int i, boolean[] crossed) {
        int n = s.length();
        if (i + 1 >= n || s.charAt(i) != '<' || !tagStart(s.charAt(i + 1))) {
            return -1;
        }
        char quote = 0;
        for (int j = i + 1; j < n; j++) {
            char c = s.charAt(j);
            if (crossed != null && isSentinel(c)) {
                crossed[0] = true;
            }
            if (quote == 0) {
                if (c == '>') {
                    return j + 1;
                }
                if (c == '<' || (c == '\\' && j + 1 < n && s.charAt(j + 1) == '<')) {
                    return -1;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                }
            } else if (c == '\\' && j + 1 < n && (s.charAt(j + 1) == quote || s.charAt(j + 1) == '\\')) {
                j++;
                if (crossed != null && isSentinel(s.charAt(j))) {
                    crossed[0] = true;
                }
            } else if (c == quote) {
                quote = 0;
            }
        }
        return -1;
    }

    /** Length of the bracket tag {@code tag} (lower case) at {@code i}, ignoring case, or 0. */
    static int bracketAt(String s, int i, String tag) {
        return s.regionMatches(true, i, tag, 0, tag.length()) ? tag.length() : 0;
    }

    /** Whether {@code s} contains any RsLib bracket tag, ignoring case. */
    static boolean hasBracketTag(String s) {
        int from = s.indexOf('[');
        while (from >= 0) {
            for (String tag : BRACKET_TAGS) {
                if (bracketAt(s, from, tag) > 0) {
                    return true;
                }
            }
            from = s.indexOf('[', from + 1);
        }
        return false;
    }

    /** The original character a literal marker stands for. */
    static char unmark(char c) {
        return MARKED.charAt(c - MARKER_FIRST);
    }
}
