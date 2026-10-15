package com.rs.lib.yml.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The key structure of a YAML file read line by line, without a YAML parser, so the config
 * updater can copy and remove whole blocks while every other line stays byte for byte.
 *
 * <p>A key line is a line that is not blank, not a comment, not a list item ({@code - }) and
 * has a {@code :} outside quotes followed by a space or the end of the line. Nesting follows the
 * number of leading spaces (pass tab-free lines; see {@code YamlPreprocessor}). The <em>block</em>
 * of a key is the comments and blank lines right above it, the key line and everything below it
 * up to the next key with the same or less indentation; the comments right above that next key
 * belong to the next key. Lines that cannot hold keys are skipped as content of the current
 * block: list items and the lines nested under them, block scalars ({@code |}, {@code >}),
 * flow collections spread over several lines and quoted values spread over several lines.
 * Inline maps ({@code a: {b: 1}}) and lists of maps are therefore never looked into.</p>
 *
 * <p>For the first key of the file, comments separated from it by a blank line are the file
 * header and stay out of its block, so inserting before or removing the first key never moves
 * the header.</p>
 */
public final class YamlOutline {

    private static final Pattern MARKER = Pattern.compile("^\\s*#\\s*(?:rs|sn):extensible\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOT_MARKER = Pattern.compile("^\\s*#\\s*(?:rs|sn):extensible-root\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_SCALAR = Pattern.compile(
            "^(?:[!&]\\S*\\s+)*[|>](?:[1-9][+-]?|[+-][1-9]?)?$");

    /** One key, or the file itself for the root. */
    public static final class Node {
        private final String key;
        private final String rawKey;
        private final int indent;
        private final int line;
        private final String value;
        private final Node parent;
        private final List<Node> children = new ArrayList<>();
        private int start;
        private int end;
        private boolean marked;
        private boolean listItems;

        Node(String rawKey, int indent, int line, String value, Node parent) {
            this.rawKey = rawKey;
            this.key = rawKey == null ? null : unquote(rawKey);
            this.indent = indent;
            this.line = line;
            this.value = value;
            this.parent = parent;
        }

        /** The key without surrounding quotes; null for the root. */
        public String key() {
            return key;
        }

        /** The key as written. */
        public String rawKey() {
            return rawKey;
        }

        /** Leading spaces of the key line; -1 for the root. */
        public int indent() {
            return indent;
        }

        /** Index of the key line; -1 for the root. */
        public int line() {
            return line;
        }

        /** Text after the colon without its comment, trimmed; empty when the value is below. */
        public String value() {
            return value;
        }

        public Node parent() {
            return parent;
        }

        public List<Node> children() {
            return Collections.unmodifiableList(children);
        }

        /** First line of the block (its leading comments), inclusive. */
        public int start() {
            return start;
        }

        /** End of the block, exclusive. */
        public int end() {
            return end;
        }

        /**
         * True when its comments carry {@code rs:extensible} (or the legacy {@code sn:extensible});
         * for the root, when the file header carries {@code rs:extensible-root} or {@code sn:extensible-root}.
         */
        public boolean marked() {
            return marked;
        }

        public boolean isRoot() {
            return parent == null;
        }

        /** True when the value is written below the key as list items. */
        public boolean hasListItems() {
            return listItems;
        }

        /** Holds (or may hold) child keys: nothing after the colon and no list items. */
        public boolean isSection() {
            return isRoot() || (value.isEmpty() && !listItems);
        }

        /** A single value: not a section, not a list and not an inline map or list. */
        public boolean isSimpleValue() {
            return !isRoot() && !value.isEmpty() && !value.startsWith("{") && !value.startsWith("[");
        }

        /** The first child with this key (compared without quotes), or null. */
        public Node child(String childKey) {
            for (Node child : children) {
                if (child.key.equals(childKey)) {
                    return child;
                }
            }
            return null;
        }

        /** Dotted path from the root, for messages. */
        public String path() {
            if (parent == null) {
                return "";
            }
            String up = parent.path();
            return up.isEmpty() ? key : up + "." + key;
        }

        @Override
        public String toString() {
            return isRoot() ? "<root>" : path() + "@" + line + "[" + start + "," + end + ")";
        }
    }

    private enum Kind { BLANK, COMMENT, KEY, LIST, OTHER, CONTENT }

    private YamlOutline() {
    }

    /** What the first pass learned about each line; keys and values are set on key lines only. */
    private record Classified(Kind[] kinds, String[] keys, String[] values, int[] indents) {
    }

    /** Builds the key tree of {@code lines}. */
    public static Node parse(List<String> lines) {
        return build(lines, classify(lines));
    }

    /**
     * First pass: the kind of every line, skipping as content what cannot hold keys (lines
     * under list items and block scalars, multi-line flow collections and quoted values).
     */
    private static Classified classify(List<String> lines) {
        int size = lines.size();
        Kind[] kinds = new Kind[size];
        String[] keys = new String[size];
        String[] values = new String[size];
        int[] indents = new int[size];

        int opaqueIndent = -1;
        int flowDepth = 0;
        char openQuote = 0;
        for (int i = 0; i < size; i++) {
            String line = lines.get(i);
            int indent = leadingSpaces(line);
            indents[i] = indent;
            boolean blank = line.isBlank();
            if (openQuote != 0) {
                kinds[i] = Kind.CONTENT;
                if (closesQuote(line, openQuote, 0) >= 0) {
                    openQuote = 0;
                }
                continue;
            }
            if (flowDepth > 0) {
                kinds[i] = Kind.CONTENT;
                flowDepth = Math.max(0, flowDepth + bracketBalance(line, 0));
                continue;
            }
            if (opaqueIndent >= 0) {
                if (blank || indent > opaqueIndent) {
                    kinds[i] = Kind.CONTENT;
                    continue;
                }
                opaqueIndent = -1;
            }
            if (blank) {
                kinds[i] = Kind.BLANK;
                continue;
            }
            String trimmed = line.substring(indent);
            if (trimmed.startsWith("#")) {
                kinds[i] = Kind.COMMENT;
                continue;
            }
            if (trimmed.equals("-") || trimmed.startsWith("- ") || trimmed.startsWith("-\t")) {
                kinds[i] = Kind.LIST;
                opaqueIndent = indent;
                continue;
            }
            int colon = keyColon(trimmed);
            if (colon < 0) {
                kinds[i] = Kind.OTHER;
                continue;
            }
            kinds[i] = Kind.KEY;
            keys[i] = trimmed.substring(0, colon).trim();
            String rest = trimmed.substring(colon + 1);
            int comment = commentStart(rest);
            String value = (comment < 0 ? rest : rest.substring(0, comment)).trim();
            values[i] = value;
            if (BLOCK_SCALAR.matcher(value).matches()) {
                opaqueIndent = indent;
            } else if (value.startsWith("{") || value.startsWith("[")) {
                flowDepth = Math.max(0, bracketBalance(value, 0));
            } else if (value.startsWith("\"") || value.startsWith("'")) {
                if (closesQuote(value, value.charAt(0), 1) < 0) {
                    openQuote = value.charAt(0);
                }
            }
        }
        return new Classified(kinds, keys, values, indents);
    }

    /** Second pass: nests the key lines by indentation and fixes where each block starts and ends. */
    private static Node build(List<String> lines, Classified c) {
        int size = lines.size();
        Kind[] kinds = c.kinds();
        Node root = new Node(null, -1, -1, "", null);
        root.start = 0;
        root.end = size;
        int firstKey = firstKey(kinds);
        int headerEnd = firstKey < 0 ? size : firstKey;
        for (int i = 0; i < headerEnd; i++) {
            if (kinds[i] == Kind.COMMENT && ROOT_MARKER.matcher(lines.get(i)).matches()) {
                root.marked = true;
            }
        }

        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        for (int i = 0; i < size; i++) {
            Kind kind = kinds[i];
            if (kind == Kind.LIST) {
                for (Node open : stack) {
                    if (open.indent <= c.indents()[i]) {
                        if (!open.isRoot()) {
                            open.listItems = true;
                        }
                        break;
                    }
                }
                continue;
            }
            if (kind != Kind.KEY) {
                continue;
            }
            int runStart = i;
            boolean marked = false;
            while (runStart > 0 && (kinds[runStart - 1] == Kind.BLANK || kinds[runStart - 1] == Kind.COMMENT)) {
                runStart--;
                if (kinds[runStart] == Kind.COMMENT && MARKER.matcher(lines.get(runStart)).matches()) {
                    marked = true;
                }
            }
            int start = runStart;
            if (i == firstKey) {
                for (int j = i - 1; j >= runStart; j--) {
                    if (kinds[j] == Kind.BLANK) {
                        start = j + 1;
                        break;
                    }
                }
            }
            while (stack.peek() != root && stack.peek().indent >= c.indents()[i]) {
                stack.pop().end = start;
            }
            Node parent = stack.peek();
            Node node = new Node(c.keys()[i], c.indents()[i], i, c.values()[i], parent);
            node.start = start;
            node.marked = marked;
            parent.children.add(node);
            stack.push(node);
        }
        while (stack.peek() != root) {
            stack.pop().end = size;
        }
        return root;
    }

    private static int firstKey(Kind[] kinds) {
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == Kind.KEY) {
                return i;
            }
        }
        return -1;
    }

    /** All nodes below {@code node} in document order. */
    public static List<Node> descendants(Node node) {
        List<Node> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<Node> out) {
        for (Node child : node.children) {
            out.add(child);
            collect(child, out);
        }
    }

    /** True for a comment line carrying {@code rs:extensible} or {@code sn:extensible}. */
    public static boolean isMarker(String line) {
        return MARKER.matcher(line).matches();
    }

    /** True for a comment line carrying {@code rs:extensible-root} or {@code sn:extensible-root}. */
    public static boolean isRootMarker(String line) {
        return ROOT_MARKER.matcher(line).matches();
    }

    /** True when {@code value} (a value without its comment) starts a block scalar. */
    public static boolean isBlockScalarIndicator(String value) {
        return BLOCK_SCALAR.matcher(value.trim()).matches();
    }

    public static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** Removes one pair of matching quotes around a key. */
    public static String unquote(String key) {
        if (key.length() >= 2) {
            char first = key.charAt(0);
            if ((first == '"' || first == '\'') && key.charAt(key.length() - 1) == first) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    /**
     * Index of the colon that ends the key in a line without its indentation, or -1 when the
     * line has no key.
     */
    public static int keyColon(String trimmed) {
        if (trimmed.isEmpty()) {
            return -1;
        }
        char first = trimmed.charAt(0);
        if (first == '#' || first == '-' && (trimmed.length() == 1 || Character.isWhitespace(trimmed.charAt(1)))) {
            return -1;
        }
        if (first == '"' || first == '\'') {
            int close = closesQuote(trimmed, first, 1);
            if (close < 0) {
                return -1;
            }
            int i = close + 1;
            while (i < trimmed.length() && (trimmed.charAt(i) == ' ' || trimmed.charAt(i) == '\t')) {
                i++;
            }
            if (i < trimmed.length() && trimmed.charAt(i) == ':' && endsKey(trimmed, i)) {
                return i;
            }
            return -1;
        }
        if (first == '{' || first == '[' || first == '?' || first == '!' || first == '&' || first == '*'
                || first == '|' || first == '>' || first == '%' || first == '@' || first == '`') {
            return -1;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '#' && i > 0 && Character.isWhitespace(trimmed.charAt(i - 1))) {
                return -1;
            }
            if (c == ':' && endsKey(trimmed, i)) {
                return i > 0 ? i : -1;
            }
        }
        return -1;
    }

    private static boolean endsKey(String s, int colon) {
        return colon + 1 >= s.length() || Character.isWhitespace(s.charAt(colon + 1));
    }

    /**
     * Index where a trailing comment starts in {@code text}, or -1. A {@code #} starts a comment
     * at the beginning or after whitespace, outside quotes; a quote opens a quoted part only at
     * the start of a value (after nothing, {@code :}, {@code -}, {@code ,}, {@code [} or {@code {}).
     */
    public static int commentStart(String text) {
        char quote = 0;
        char previous = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                previous = c;
                continue;
            }
            if ((c == '"' || c == '\'') && startsValue(previous)) {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(text.charAt(i - 1)))) {
                return i;
            }
            if (!Character.isWhitespace(c)) {
                previous = c;
            }
        }
        return -1;
    }

    private static boolean startsValue(char previous) {
        return previous == 0 || previous == ':' || previous == '-' || previous == ','
                || previous == '[' || previous == '{' || previous == '?';
    }

    /** Index of the closing quote in {@code s} from {@code from}, honoring escapes, or -1. */
    static int closesQuote(String s, char quote, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote == '"' && c == '\\') {
                i++;
            } else if (c == quote) {
                if (quote == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Opening minus closing brackets outside quotes, ignoring a trailing comment. */
    static int bracketBalance(String s, int from) {
        int depth = 0;
        char quote = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
                break;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return depth;
    }
}
