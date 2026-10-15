package com.rs.lib.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Declares one subcommand. A subcommand with children ({@link #sub(String, Consumer)}) is a
 * group that only routes to the next word; one without children takes arguments and runs.
 */
public final class SubCommandBuilder {

    private final RsCommands.RootBuilder root;
    private final String name;
    private final List<String> aliases = new ArrayList<>();
    private final List<Node.ArgSpec> args = new ArrayList<>();
    private final List<Node.Condition> conditions = new ArrayList<>();
    private final List<SubCommandBuilder> children = new ArrayList<>();
    private String permission;
    private String usage;
    private String description = "";
    private boolean visible = true;
    private boolean helpVisible = true;
    private boolean groupHelp;
    private Consumer<CommandContext> executor;

    SubCommandBuilder(RsCommands.RootBuilder root, String name) {
        this.root = root;
        this.name = RsCommands.word(name, "subcommand name");
    }

    public SubCommandBuilder aliases(String... aliases) {
        for (String alias : aliases) {
            this.aliases.add(RsCommands.word(alias, "alias"));
        }
        return this;
    }

    /** Needed on top of every permission above it. */
    public SubCommandBuilder permission(String permission) {
        this.permission = permission == null || permission.isBlank() ? null : permission.trim();
        return this;
    }

    /** Usage shown instead of the generated one; {@code {label}} becomes the typed command. */
    public SubCommandBuilder usage(String usage) {
        this.usage = usage;
        return this;
    }

    public SubCommandBuilder description(String description) {
        this.description = description == null ? "" : description;
        return this;
    }

    /** Hidden subcommands still run, but are left out of suggestions and help. */
    public SubCommandBuilder visible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** False leaves the subcommand out of the help only. */
    public SubCommandBuilder helpVisible(boolean helpVisible) {
        this.helpVisible = helpVisible;
        return this;
    }

    /** For groups: the group answers {@code help} with its own page and shows as one help line. */
    public SubCommandBuilder groupHelp() {
        this.groupHelp = true;
        return this;
    }

    /** A required argument; arguments are read in the order they are declared. */
    public SubCommandBuilder arg(String name, Arg<?> arg) {
        return addArg(name, arg, false);
    }

    /** An optional argument; no required argument can follow it. */
    public SubCommandBuilder argOptional(String name, Arg<?> arg) {
        return addArg(name, arg, true);
    }

    /** The word at {@code index} must pass {@code condition}, or the usage is shown. */
    public SubCommandBuilder when(int index, Predicate<String> condition) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be 0 or more: " + index);
        }
        conditions.add(new Node.Condition(index, Objects.requireNonNull(condition, "condition")));
        return this;
    }

    public SubCommandBuilder executes(Consumer<CommandContext> executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    /** A nested subcommand, configured by {@code spec}; this one becomes a group. */
    public SubCommandBuilder sub(String name, Consumer<SubCommandBuilder> spec) {
        SubCommandBuilder child = new SubCommandBuilder(null, name);
        Objects.requireNonNull(spec, "spec").accept(child);
        children.add(child);
        return this;
    }

    /** Back to the root, to declare the next subcommand. */
    public RsCommands.RootBuilder and() {
        if (root == null) {
            throw new IllegalStateException("and() is only for first-level subcommands; '" + name
                    + "' is nested, its spec returns on its own");
        }
        return root;
    }

    String name() {
        return name;
    }

    List<String> aliases() {
        return aliases;
    }

    private SubCommandBuilder addArg(String argName, Arg<?> arg, boolean optional) {
        Objects.requireNonNull(arg, "arg");
        if (argName == null || argName.isBlank()) {
            throw new IllegalArgumentException("Argument name is empty in '" + name + "'");
        }
        String clean = argName.trim();
        for (Node.ArgSpec existing : args) {
            if (existing.name().equals(clean)) {
                throw new IllegalArgumentException("Argument '" + clean + "' declared twice in '" + name + "'");
            }
        }
        if (!optional && !args.isEmpty() && args.get(args.size() - 1).optional()) {
            throw new IllegalStateException("Required argument '" + clean + "' after an optional one in '" + name + "'");
        }
        args.add(new Node.ArgSpec(clean, arg, optional));
        return this;
    }

    Node build(String parentPath) {
        return build(parentPath, path -> { });
    }

    /**
     * The node of this subcommand. A subcommand with children is a group, so its executor,
     * arguments and conditions are dropped; {@code ignored} gets the path of each such one.
     */
    Node build(String parentPath, Consumer<String> ignored) {
        String path = parentPath.isEmpty() ? name : parentPath + " " + name;
        List<Node> built = new ArrayList<>(children.size());
        Set<String> taken = new HashSet<>();
        for (SubCommandBuilder child : children) {
            RsCommands.claimWords(taken, child.name, child.aliases, path);
            built.add(child.build(path, ignored));
        }
        if (!built.isEmpty() && (executor != null || !args.isEmpty() || !conditions.isEmpty())) {
            ignored.accept(path);
        }
        return new Node(name, aliases, path, permission, usage, description, visible, helpVisible, groupHelp,
                args, conditions, executor, built);
    }
}
