package com.rs.lib.notice;

import java.util.ArrayList;
import java.util.List;

import com.rs.lib.Ph;

/**
 * How the placeholder values of a notice are kept in one text column: one line per value,
 * {@code f} (formatted) or {@code l} (literal), the key, {@code =} and the value. Backslashes,
 * line breaks and {@code =} are escaped, so any key or value survives the trip.
 */
final class NoticeValues {

    private NoticeValues() {
    }

    static String encode(List<Ph> values) {
        StringBuilder out = new StringBuilder();
        for (Ph ph : values) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(ph.literal() ? 'l' : 'f');
            escape(ph.key(), out);
            out.append('=');
            escape(ph.value(), out);
        }
        return out.toString();
    }

    /** The values of {@code text}; a line that cannot be read is skipped. */
    static List<Ph> decode(String text) {
        List<Ph> values = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return values;
        }
        for (String line : text.split("\n", -1)) {
            if (line.length() < 2 || (line.charAt(0) != 'f' && line.charAt(0) != 'l')) {
                continue;
            }
            int split = -1;
            for (int i = 1; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == '=') {
                    split = i;
                    break;
                }
            }
            if (split < 0) {
                continue;
            }
            String key = unescape(line, 1, split);
            if (key.isEmpty()) {
                continue;
            }
            values.add(new Ph(key, unescape(line, split + 1, line.length()), line.charAt(0) == 'l'));
        }
        return values;
    }

    private static void escape(String s, StringBuilder out) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '=' -> out.append("\\e");
                default -> out.append(c);
            }
        }
    }

    private static String unescape(String s, int from, int to) {
        StringBuilder out = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= to) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 'e' -> out.append('=');
                default -> out.append(next);
            }
        }
        return out.toString();
    }
}
