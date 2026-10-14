package com.rs.lib.command;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.bukkit.command.CommandSender;

/**
 * One immutable node of a command tree. A node with children is a group (it only routes to the
 * next word); a node without children is a leaf (it parses arguments and runs). Only the texts
 * shown to players change after building, all at once, when the translation is applied.
 */
final class Node {

    record ArgSpec(String name, Arg<?> arg, boolean optional) {
    }

    record Condition(int index, Predicate<String> test) {
    }

    /** Texts from the language file; null or blank entries keep the code's text. */
    record Labels(String description, Map<String, String> args) {
        static final Labels NONE = new Labels(null, Map.of());
    }

    final String name;
    final List<String> aliases;
    /** Path from the root, without the root: {@code "give"} or {@code "admin reset"}. */
    final String path;
    final String permission;
    final String usage;
    final String codeDescription;
    final boolean visible;
    final boolean helpVisible;
    final boolean groupHelp;
    final List<ArgSpec> args;
    final int required;
    final List<Condition> conditions;
    final Consumer<CommandContext> executor;
    final List<Node> children;

    private volatile Labels labels = Labels.NONE;

    Node(String name, List<String> aliases, String path, String permission, String usage, String codeDescription,
            boolean visible, boolean helpVisible, boolean groupHelp, List<ArgSpec> args, List<Condition> conditions,
            Consumer<CommandContext> executor, List<Node> children) {
        this.name = name;
        this.aliases = List.copyOf(aliases);
        this.path = path;
        this.permission = permission;
        this.usage = usage;
        this.codeDescription = codeDescription == null ? "" : codeDescription;
        this.visible = visible;
        this.helpVisible = helpVisible;
        this.groupHelp = groupHelp;
        this.args = List.copyOf(args);
        this.required = (int) args.stream().filter(a -> !a.optional()).count();
        this.conditions = List.copyOf(conditions);
        this.executor = executor;
        this.children = List.copyOf(children);
    }

    boolean group() {
        return !children.isEmpty();
    }

    boolean permitted(CommandSender sender) {
        return permission == null || sender.hasPermission(permission);
    }

    /** True when {@code token} is this node's name or one of its aliases, ignoring case. */
    boolean answersTo(String token) {
        if (name.equalsIgnoreCase(token)) {
            return true;
        }
        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    Node child(String token) {
        for (Node child : children) {
            if (child.answersTo(token)) {
                return child;
            }
        }
        return null;
    }

    /** The last argument takes the rest of the line. */
    boolean greedyTail() {
        return !args.isEmpty() && args.get(args.size() - 1).arg() instanceof Args.RsArg<?> arg && arg.greedy();
    }

    String description() {
        String translated = labels.description();
        return translated == null || translated.isBlank() ? codeDescription : translated;
    }

    String argLabel(String argName) {
        String translated = labels.args().get(argName);
        return translated == null || translated.isBlank() ? argName : translated;
    }

    Labels labels() {
        return labels;
    }

    void labels(Labels labels) {
        this.labels = labels == null ? Labels.NONE : labels;
    }
}
