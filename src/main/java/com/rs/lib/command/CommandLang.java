package com.rs.lib.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.rs.lib.lang.RsLang;

/**
 * The {@code commands:} block of the language file: every description and argument label, so
 * the owner can translate them. Missing entries are added with the code's text; existing ones
 * are never overwritten. Roots are sorted by name so the file does not change between starts.
 */
final class CommandLang {

    static final String ROOT_KEY = "commands";

    private CommandLang() {
    }

    static void apply(RsLang lang, List<RootCommand> roots) {
        List<RootCommand> sorted = new ArrayList<>(roots);
        sorted.sort(Comparator.comparing(RootCommand::getName));
        if (sorted.isEmpty()) {
            return;
        }
        lang.mergeDefaults(block(sorted));
        for (RootCommand root : sorted) {
            String base = ROOT_KEY + "." + root.getName();
            root.translateDescription(lang.rawOrNull(base + ".description"));
            for (Node node : root.top) {
                read(lang, node, base + ".subcommands." + node.name);
            }
        }
    }

    private static void read(RsLang lang, Node node, String base) {
        Map<String, String> args = new HashMap<>();
        for (Node.ArgSpec spec : node.args) {
            if (spec.name().indexOf('.') < 0) {
                String label = lang.rawOrNull(base + ".args." + spec.name());
                if (label != null) {
                    args.put(spec.name(), label);
                }
            }
        }
        node.labels(new Node.Labels(lang.rawOrNull(base + ".description"), Map.copyOf(args)));
        for (Node child : node.children) {
            read(lang, child, base + ".subcommands." + child.name);
        }
    }

    /** The block as YAML text, with the code's texts. */
    static String block(List<RootCommand> roots) {
        StringBuilder out = new StringBuilder();
        out.append("# Texts of the commands: descriptions shown in the help and argument labels.\n");
        out.append("# Edit them freely; delete an entry to get the original text back on the next start.\n");
        out.append(ROOT_KEY).append(":\n");
        for (RootCommand root : roots) {
            out.append("  ").append(key(root.getName())).append(":\n");
            out.append("    description: ").append(quote(root.codeDescription())).append('\n');
            if (!root.top.isEmpty()) {
                out.append("    subcommands:\n");
                for (Node node : root.top) {
                    write(out, node, 6);
                }
            }
        }
        return out.toString();
    }

    private static void write(StringBuilder out, Node node, int indent) {
        String pad = " ".repeat(indent);
        out.append(pad).append(key(node.name)).append(":\n");
        out.append(pad).append("  description: ").append(quote(node.codeDescription)).append('\n');
        List<Node.ArgSpec> labelled = node.args.stream().filter(a -> a.name().indexOf('.') < 0).toList();
        if (!labelled.isEmpty()) {
            out.append(pad).append("  args:\n");
            for (Node.ArgSpec spec : labelled) {
                out.append(pad).append("    ").append(key(spec.name())).append(": ").append(quote(spec.name())).append('\n');
            }
        }
        if (!node.children.isEmpty()) {
            out.append(pad).append("  subcommands:\n");
            for (Node child : node.children) {
                write(out, child, indent + 4);
            }
        }
    }

    private static String key(String word) {
        return word.matches("[a-z_][a-z0-9_-]*") && !word.matches("(?i)y|n|yes|no|on|off|true|false|null")
                ? word : quote(word);
    }

    private static boolean validSurrogate(String text, int i) {
        char c = text.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (Character.isISOControl(c) || c == '\uFEFF' || c >= '\uFFFE'
                            || Character.isSurrogate(c) && !validSurrogate(text, i)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
