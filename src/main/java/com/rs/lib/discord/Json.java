package com.rs.lib.discord;

/** The little JSON the webhooks need, written by hand. */
final class Json {

    /** What Discord shows for a field name or value that would otherwise be empty. */
    static final String ZERO_WIDTH_SPACE = "​";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    /**
     * Appends {@code ,"name":"value"} ({@code first} drops the comma) unless {@code value} is
     * null. Returns whether the next field is still the first.
     */
    static boolean field(StringBuilder out, boolean first, String name, String value) {
        if (value == null) {
            return first;
        }
        if (!first) {
            out.append(',');
        }
        out.append('"').append(name).append("\":");
        string(out, value);
        return false;
    }

    /** A JSON string: quotes, backslashes and control characters escaped. */
    static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append("\\u").append(HEX[c >> 12 & 0xF]).append(HEX[c >> 8 & 0xF])
                                .append(HEX[c >> 4 & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** At most {@code max} characters, never splitting a surrogate pair; null stays null. */
    static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /** Null for null or blank text, so the part is left out of the payload. */
    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** A zero-width space for null or blank text: Discord refuses empty field parts. */
    static String nonEmpty(String value) {
        return value == null || value.isBlank() ? ZERO_WIDTH_SPACE : value;
    }
}
