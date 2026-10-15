package com.rs.lib.action;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

/**
 * Parses {@code *-requirements} lists into a {@link Requirement}.
 *
 * <p>Each line is one expression and the lines of a list are joined with an implicit AND; a
 * null or empty list (blank lines are skipped) is always true. An expression compares two
 * operands with {@code >}, {@code <}, {@code >=}, {@code <=}, {@code =}, {@code ==} or
 * {@code !=} and joins comparisons with {@code &&} and {@code ||} (AND binds tighter) and
 * parentheses:</p>
 * <pre>
 * - "%vault_eco_balance% &gt;= 1000"
 * - "%player_level% &gt; 5 &amp;&amp; (%player_world% = world || %player_world% = world_nether)"
 * - "'%luckperms_primary_group%' != 'default'"
 * </pre>
 *
 * <p>An operand that starts with a single or double quote runs to the matching quote, and
 * inside it connectors, parentheses and operators are plain text; one pair of quotes that
 * wraps the whole operand is taken off, the spaces inside are kept. A quote that is never
 * closed runs to the end of the line. A quote in the middle of an operand is plain text.</p>
 *
 * <p>Lines are parsed once; placeholders stay as written and go through the caller's resolver
 * on every check. When both sides read as numbers they are compared as numbers. Otherwise
 * {@code =} and {@code ==} compare text ignoring case and {@code !=} is their negation, while
 * {@code >}, {@code <}, {@code >=} and {@code <=} on text are false (reported once).</p>
 *
 * <p>A line written wrong (a parenthesis left open, a dangling connector, text without an
 * operator, a missing operand) FAILS CLOSED: it is always false, so a mistake in the
 * requirement of a paid button never lets the click through, and it is reported once when
 * parsed. The result is immutable and safe from any thread.</p>
 */
public final class RequirementEngine {

    private static final Logger LOG = Logger.getLogger("RsLib");

    /** A line written wrong: never true. */
    private static final Node NEVER = resolver -> false;

    private RequirementEngine() {
    }

    /** Parses {@code lines}; problems are reported to the RsLib log. */
    public static Requirement parse(List<String> lines) {
        return parse(lines, message -> LOG.warning("[RsLib] " + message));
    }

    /**
     * Parses {@code lines}; problems go to {@code warn} (null: not reported), which should say
     * where the list was read from (file and key).
     */
    public static Requirement parse(List<String> lines, Consumer<String> warn) {
        if (lines == null || lines.isEmpty()) {
            return Requirement.ALWAYS;
        }
        Consumer<String> out = warn == null ? message -> { } : warn;
        List<Node> nodes = new ArrayList<>(lines.size());
        List<String> source = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                nodes.add(Parser.parseLine(line.trim(), out));
                source.add(line.trim());
            }
        }
        return nodes.isEmpty() ? Requirement.ALWAYS : new Compiled(nodes.toArray(Node[]::new), List.copyOf(source));
    }

    /** A parsed list: every line must hold. */
    private record Compiled(Node[] lines, List<String> source) implements Requirement {

        @Override
        public boolean test(Player player, Function<String, String> resolver) {
            for (Node line : lines) {
                if (!line.eval(resolver)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "Requirement" + source;
        }
    }

    /** One node of a parsed expression. */
    private interface Node {
        boolean eval(Function<String, String> resolver);
    }

    private record Or(Node[] parts) implements Node {
        @Override
        public boolean eval(Function<String, String> resolver) {
            for (Node part : parts) {
                if (part.eval(resolver)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record And(Node[] parts) implements Node {
        @Override
        public boolean eval(Function<String, String> resolver) {
            for (Node part : parts) {
                if (!part.eval(resolver)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The comparison operators. */
    private enum Op {
        GE(">="), LE("<="), EQEQ("=="), NE("!="), GT(">"), LT("<"), EQ("=");

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        boolean relational() {
            return this == GE || this == LE || this == GT || this == LT;
        }
    }

    /** One side of a comparison; only operands that can hold a placeholder are resolved. */
    private record Operand(String text, boolean dynamic) {

        static Operand of(String text) {
            return new Operand(text, text.indexOf('%') >= 0 || text.indexOf('{') >= 0);
        }

        String value(Function<String, String> resolver) {
            if (!dynamic || resolver == null) {
                return text;
            }
            String value = resolver.apply(text);
            return value == null ? text : value;
        }
    }

    private static final class Compare implements Node {

        private final Operand left;
        private final Op op;
        private final Operand right;
        private final String line;
        private final Consumer<String> warn;
        private final AtomicBoolean warned = new AtomicBoolean();

        Compare(Operand left, Op op, Operand right, String line, Consumer<String> warn) {
            this.left = left;
            this.op = op;
            this.right = right;
            this.line = line;
            this.warn = warn;
        }

        @Override
        public boolean eval(Function<String, String> resolver) {
            String a = left.value(resolver);
            String b = right.value(resolver);
            double x = number(a);
            double y = number(b);
            if (!Double.isNaN(x) && !Double.isNaN(y)) {
                return switch (op) {
                    case GT -> x > y;
                    case LT -> x < y;
                    case GE -> x >= y;
                    case LE -> x <= y;
                    case EQ, EQEQ -> x == y;
                    case NE -> x != y;
                };
            }
            if (op.relational()) {
                if (warned.compareAndSet(false, true)) {
                    warn.accept("Requirement '" + line + "' compares text with '" + op.symbol + "' ('" + a
                            + "' and '" + b + "'); only numbers compare that way, so it is false.");
                }
                return false;
            }
            boolean equal = a.equalsIgnoreCase(b);
            return op == Op.NE ? !equal : equal;
        }
    }

    /**
     * The number in {@code s} (spaces around it allowed), or NaN when it is not one: an optional
     * sign, digits with an optional fraction and an optional exponent. Words such as
     * {@code NaN} or {@code Infinity}, and Java's {@code 1d}, are text.
     */
    static double number(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && Character.isWhitespace(s.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(s.charAt(to - 1))) {
            to--;
        }
        int i = from;
        if (i < to && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < to && isDigit(s.charAt(i))) {
            i++;
            digits++;
        }
        if (i < to && s.charAt(i) == '.') {
            i++;
            while (i < to && isDigit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return Double.NaN;
        }
        if (i < to && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < to && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int exponent = 0;
            while (i < to && isDigit(s.charAt(i))) {
                i++;
                exponent++;
            }
            if (exponent == 0) {
                return Double.NaN;
            }
        }
        if (i != to) {
            return Double.NaN;
        }
        double value = Double.parseDouble(s.substring(from, to));
        return Double.isInfinite(value) ? Double.NaN : value;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** A parse error; carries no stack trace. */
    private static final class Malformed extends Exception {

        Malformed(String reason) {
            super(reason, null, false, false);
        }
    }

    private enum Kind { OPEN, CLOSE, AND, OR, OP, OPERAND }

    private record Tok(Kind kind, String text, Op op) {
    }

    /** Recursive descent over the tokens of one line. */
    private static final class Parser {

        private final List<Tok> toks;
        private final String line;
        private final Consumer<String> warn;
        private int pos;

        private Parser(List<Tok> toks, String line, Consumer<String> warn) {
            this.toks = toks;
            this.line = line;
            this.warn = warn;
        }

        static Node parseLine(String line, Consumer<String> warn) {
            try {
                Parser parser = new Parser(tokenize(line), line, warn);
                Node node = parser.or();
                if (parser.pos < parser.toks.size()) {
                    Tok extra = parser.toks.get(parser.pos);
                    throw new Malformed(extra.kind == Kind.CLOSE ? "a ')' without its '('"
                            : "'" + extra.text + "' where '&&' or '||' was expected");
                }
                return node;
            } catch (Malformed malformed) {
                warn.accept("Requirement '" + line + "' is written wrong (" + malformed.getMessage()
                        + "); it always fails.");
                return NEVER;
            }
        }

        private Node or() throws Malformed {
            List<Node> parts = new ArrayList<>(2);
            parts.add(and());
            while (at(Kind.OR)) {
                pos++;
                parts.add(and());
            }
            return parts.size() == 1 ? parts.get(0) : new Or(parts.toArray(Node[]::new));
        }

        private Node and() throws Malformed {
            List<Node> parts = new ArrayList<>(2);
            parts.add(primary());
            while (at(Kind.AND)) {
                pos++;
                parts.add(primary());
            }
            return parts.size() == 1 ? parts.get(0) : new And(parts.toArray(Node[]::new));
        }

        private Node primary() throws Malformed {
            if (pos >= toks.size()) {
                throw new Malformed(pos == 0 ? "nothing to compare" : "a condition is missing at the end");
            }
            Tok tok = toks.get(pos++);
            switch (tok.kind) {
                case OPEN -> {
                    Node inner = or();
                    if (!at(Kind.CLOSE)) {
                        throw new Malformed("a '(' is never closed");
                    }
                    pos++;
                    return inner;
                }
                case OPERAND -> {
                    if (!at(Kind.OP)) {
                        throw new Malformed("no operator after '" + tok.text + "'");
                    }
                    Op op = toks.get(pos++).op;
                    if (!at(Kind.OPERAND)) {
                        throw new Malformed("nothing after '" + op.symbol + "'");
                    }
                    Tok right = toks.get(pos++);
                    return new Compare(Operand.of(tok.text), op, Operand.of(right.text), line, warn);
                }
                case OP -> throw new Malformed("nothing before '" + tok.op.symbol + "'");
                case CLOSE -> throw new Malformed("a ')' without a condition");
                default -> throw new Malformed("'" + tok.text + "' without a condition before it");
            }
        }

        private boolean at(Kind kind) {
            return pos < toks.size() && toks.get(pos).kind == kind;
        }

        /** Splits a line into parentheses, connectors, operators and operands. */
        static List<Tok> tokenize(String line) {
            List<Tok> out = new ArrayList<>();
            int n = line.length();
            int i = 0;
            while (i < n) {
                char c = line.charAt(i);
                Kind connector = connector(line, i);
                Op op = opAt(line, i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '(' || c == ')') {
                    out.add(new Tok(c == '(' ? Kind.OPEN : Kind.CLOSE, String.valueOf(c), null));
                    i++;
                } else if (connector != null) {
                    out.add(new Tok(connector, connector == Kind.AND ? "&&" : "||", null));
                    i += 2;
                } else if (op != null) {
                    out.add(new Tok(Kind.OP, op.symbol, op));
                    i += op.symbol.length();
                } else if (c == '\'' || c == '"') {
                    i = quoted(line, i, c, out);
                } else {
                    int end = plainEnd(line, i);
                    out.add(new Tok(Kind.OPERAND, line.substring(i, end).trim(), null));
                    i = end;
                }
            }
            return out;
        }

        /** An operand that starts with a quote; returns where scanning goes on. */
        private static int quoted(String line, int start, char quote, List<Tok> out) {
            int close = line.indexOf(quote, start + 1);
            if (close < 0) {
                out.add(new Tok(Kind.OPERAND, line.substring(start + 1), null));
                return line.length();
            }
            int end = plainEnd(line, close + 1);
            if (line.substring(close + 1, end).isBlank()) {
                out.add(new Tok(Kind.OPERAND, line.substring(start + 1, close), null));
            } else {
                // The quotes do not wrap the whole operand: it is kept as written.
                out.add(new Tok(Kind.OPERAND, line.substring(start, end).trim(), null));
            }
            return end;
        }

        /** Where plain operand text that starts at {@code from} ends. */
        private static int plainEnd(String line, int from) {
            int j = from;
            int n = line.length();
            while (j < n) {
                char c = line.charAt(j);
                if (c == '(' || c == ')' || connector(line, j) != null || opAt(line, j) != null) {
                    break;
                }
                j++;
            }
            return j;
        }

        private static Kind connector(String line, int i) {
            if (i + 1 >= line.length()) {
                return null;
            }
            char c = line.charAt(i);
            char next = line.charAt(i + 1);
            if (c == '&' && next == '&') {
                return Kind.AND;
            }
            return c == '|' && next == '|' ? Kind.OR : null;
        }

        private static Op opAt(String line, int i) {
            char c = line.charAt(i);
            boolean eqNext = i + 1 < line.length() && line.charAt(i + 1) == '=';
            return switch (c) {
                case '>' -> eqNext ? Op.GE : Op.GT;
                case '<' -> eqNext ? Op.LE : Op.LT;
                case '=' -> eqNext ? Op.EQEQ : Op.EQ;
                case '!' -> eqNext ? Op.NE : null;
                default -> null;
            };
        }
    }
}
