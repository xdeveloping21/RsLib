package com.rs.lib.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import com.rs.lib.Ph;

/**
 * A command built with {@link RsCommands#root(String)}. It is a real Bukkit {@link Command};
 * {@link RsCommands#rootOf(Command)} finds it from any command of the server's command map
 * (also when plugin.yml declares it) and {@link #owner()} tells which plugin it belongs to.
 *
 * <p>{@link #execute} always returns true (Bukkit never prints the plugin.yml usage) and never
 * lets an exception reach the player: errors are logged with the subcommand's path.</p>
 */
public final class RootCommand extends Command implements PluginIdentifiableCommand {

    static final int HELP_PAGE_SIZE = 10;

    final RsCommands commands;
    final String rootPermission;
    final List<String> staticAliases;
    final Supplier<? extends Collection<String>> aliasSupplier;
    final boolean dynamic;
    final boolean priority;
    final Consumer<RootContext> onEmpty;
    final List<Node> top;
    final Node fallback;
    private final String codeDescription;
    private volatile String translatedDescription;

    // Registration state, touched on the main thread only (see CommandRegistry).
    volatile boolean registered;
    PluginCommand pluginCommand;
    TabExecutor adapter;
    /** Holds the bare name key ({@code /name}). */
    boolean holdsName;
    /** Aliases holding their bare key. */
    final Set<String> activeAliases = new LinkedHashSet<>();
    /** Aliases that lost their bare key but hold {@code plugin:alias}. */
    final Set<String> prefixedOnly = new LinkedHashSet<>();
    /** The aliases wanted when they were last reconciled; {@link #wants} reads only this. */
    volatile Set<String> wantedAliases = Set.of();
    /** Commands this one took a key from, to give the key back when it lets it go. */
    final Map<String, CommandRegistry.Displaced> displaced = new HashMap<>();

    RootCommand(RsCommands commands, String name, String permission, String description, List<String> staticAliases,
            Supplier<? extends Collection<String>> aliasSupplier, boolean dynamic, boolean priority,
            Consumer<RootContext> onEmpty, List<Node> top, Node fallback) {
        super(name);
        this.commands = commands;
        this.rootPermission = permission;
        this.codeDescription = description == null ? "" : description;
        this.staticAliases = List.copyOf(staticAliases);
        this.aliasSupplier = aliasSupplier;
        this.dynamic = dynamic;
        this.priority = priority;
        this.onEmpty = onEmpty;
        this.top = List.copyOf(top);
        this.fallback = fallback;
        setDescription(this.codeDescription);
        setUsage("/" + name);
    }

    /** The plugin that declared this command. */
    public JavaPlugin owner() {
        return commands.plugin();
    }

    @Override
    public JavaPlugin getPlugin() {
        return owner();
    }

    /** Puts the command in the server's command map again (after {@link #unregister()}). */
    public void register() {
        commands.register(this);
    }

    /** Takes the command, its aliases and every key it took out of the server's command map. */
    public void unregister() {
        commands.unregister(this);
    }

    /** True while the command is in the server's command map. */
    public boolean isActive() {
        return registered;
    }

    String rootDescription() {
        String translated = translatedDescription;
        return translated == null || translated.isBlank() ? codeDescription : translated;
    }

    String codeDescription() {
        return codeDescription;
    }

    void translateDescription(String description) {
        translatedDescription = description;
        setDescription(rootDescription());
        PluginCommand declared = pluginCommand;
        if (declared != null) {
            declared.setDescription(rootDescription());
        }
    }

    /** Lowercase command words this root wants: its name and the aliases wanted at the last reconcile. */
    boolean wants(String key) {
        return getName().equals(key) || wantedAliases.contains(key);
    }

    // ---------------------------------------------------------------- running

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!owner().isEnabled()) {
            return true;
        }
        String label = normalizeLabel(commandLabel);
        try {
            switch (resolve(sender, label, args)) {
                case Resolution.Empty empty -> runEmpty(sender, label);
                case Resolution.Message message -> commands.send(sender, message.key(), message.phs());
                case Resolution.GroupHelp help -> sendGroupHelp(sender, label, help.group(), help.page());
                case Resolution.Run run -> runLeaf(sender, label, run);
            }
        } catch (RuntimeException | LinkageError e) {
            owner().getLogger().log(Level.SEVERE, "Error running /" + label + " " + String.join(" ", args), e);
        }
        return true;
    }

    private void runEmpty(CommandSender sender, String label) {
        if (onEmpty == null) {
            sendHelp(sender, label, 1);
            return;
        }
        try {
            onEmpty.accept(new RootContext(this, sender, label));
        } catch (CommandContext.PlayersOnlyException notPlayer) {
            commands.send(sender, "rslib.players-only");
        } catch (RuntimeException | LinkageError e) {
            owner().getLogger().log(Level.SEVERE, "Error running /" + label, e);
        }
    }

    private void runLeaf(CommandSender sender, String label, Resolution.Run run) {
        try {
            run.leaf().executor.accept(run.context());
        } catch (CommandContext.PlayersOnlyException notPlayer) {
            commands.send(sender, "rslib.players-only");
        } catch (RuntimeException | LinkageError e) {
            owner().getLogger().log(Level.SEVERE, "Error running /" + label + " " + run.leaf().path, e);
        }
    }

    /** What a command line means, decided before anything runs. */
    sealed interface Resolution {
        record Empty() implements Resolution {
        }

        record Message(String key, Ph... phs) implements Resolution {
        }

        record GroupHelp(Node group, int page) implements Resolution {
        }

        record Run(Node leaf, CommandContext context) implements Resolution {
        }
    }

    Resolution resolve(CommandSender sender, String label, String[] args) {
        return resolve(sender, label, shownLabel(label), args);
    }

    /** {@code label} goes to the context; {@code shown} is the command usage lines tell the player to type. */
    Resolution resolve(CommandSender sender, String label, String shown, String[] args) {
        if (rootPermission != null && !sender.hasPermission(rootPermission)) {
            return new Resolution.Message("rslib.no-permission");
        }
        if (args.length == 0) {
            return new Resolution.Empty();
        }
        Node first = child(args[0]);
        if (first == null) {
            if (fallback != null && fallback.permitted(sender)) {
                return leaf(sender, label, shown, fallback, args, 0, true);
            }
            return new Resolution.Message("rslib.unknown-subcommand", Ph.literal("value", args[0]), Ph.of("label", shown));
        }
        return dispatch(sender, label, shown, first, args, 1);
    }

    private Resolution dispatch(CommandSender sender, String label, String shown, Node node, String[] args, int index) {
        if (!node.permitted(sender)) {
            return new Resolution.Message("rslib.no-permission");
        }
        if (!node.group()) {
            return leaf(sender, label, shown, node, args, index, false);
        }
        if (index >= args.length) {
            if (node.groupHelp) {
                return new Resolution.GroupHelp(node, 1);
            }
            return usage("/" + shown + " " + node.path + " <" + childChoices(node, sender) + ">");
        }
        Node next = node.child(args[index]);
        if (next == null) {
            if (node.groupHelp && args[index].equalsIgnoreCase("help")) {
                return new Resolution.GroupHelp(node, pageOf(index + 1 < args.length ? args[index + 1] : null));
            }
            return new Resolution.Message("rslib.unknown-subcommand", Ph.literal("value", node.path + " " + args[index]),
                    Ph.of("label", shown));
        }
        return dispatch(sender, label, shown, next, args, index + 1);
    }

    private Resolution leaf(CommandSender sender, String label, String shown, Node leaf, String[] args, int from,
            boolean viaFallback) {
        List<String> words = List.of(args).subList(from, args.length);
        if (words.size() < leaf.required) {
            return usage(usageOf(leaf, shown, viaFallback));
        }
        for (Node.Condition condition : leaf.conditions) {
            if (condition.index() < words.size() && !condition.test().test(words.get(condition.index()))) {
                return usage(usageOf(leaf, shown, viaFallback));
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        int last = leaf.args.size() - 1;
        boolean greedy = leaf.greedyTail();
        for (int i = 0; i <= last && i < words.size(); i++) {
            Node.ArgSpec spec = leaf.args.get(i);
            boolean rest = i == last && greedy;
            String raw = rest ? String.join(" ", words.subList(i, words.size())) : words.get(i);
            try {
                values.put(spec.name(), spec.arg().parse(sender, raw));
            } catch (Arg.ArgParseException invalid) {
                return new Resolution.Message(invalid.langKey(), invalid.phs());
            }
        }
        if (leaf.executor == null) {
            return usage(usageOf(leaf, shown, viaFallback));
        }
        return new Resolution.Run(leaf, new CommandContext(this, sender, label, values, words));
    }

    private static Resolution usage(String usage) {
        return new Resolution.Message("rslib.usage", Ph.literal("usage", usage));
    }

    private Node child(String token) {
        for (Node node : top) {
            if (node.answersTo(token)) {
                return node;
            }
        }
        return null;
    }

    private static String childChoices(Node group, CommandSender sender) {
        StringJoiner names = new StringJoiner("|");
        for (Node child : group.children) {
            if (child.visible && child.permitted(sender)) {
                names.add(child.name);
            }
        }
        return names.length() == 0 ? "subcommand" : names.toString();
    }

    /** The usage line of a leaf: its own text, or the path and its arguments. */
    String usageOf(Node leaf, String label, boolean viaFallback) {
        if (leaf.usage != null && !leaf.usage.isBlank()) {
            return leaf.usage.replace("{label}", label);
        }
        StringBuilder line = new StringBuilder("/").append(label);
        if (!viaFallback) {
            line.append(' ').append(leaf.path);
        }
        int last = leaf.args.size() - 1;
        for (int i = 0; i <= last; i++) {
            Node.ArgSpec spec = leaf.args.get(i);
            String shown = leaf.argLabel(spec.name()) + (i == last && leaf.greedyTail() ? "..." : "");
            line.append(' ').append(spec.optional() ? "[" : "<").append(shown).append(spec.optional() ? "]" : ">");
        }
        return line.toString();
    }

    static String normalizeLabel(String typed) {
        String label = typed == null ? "" : typed;
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        return label.toLowerCase(Locale.ROOT);
    }

    /**
     * The command players are told to type: {@code plugin:label} when the bare word runs another
     * command (this root lost it to another plugin), else {@code label} as it is.
     */
    String shownLabel(String label) {
        return registered ? shownLabel(this, label, CommandRegistry.prefix(this), CommandRegistry.holderOf(label)) : label;
    }

    /** {@code label} when {@code holder}, the command at the bare key, runs {@code root}; else prefixed. */
    static String shownLabel(RootCommand root, String label, String prefix, Command holder) {
        return holder != null && RsCommands.rootOf(holder) == root ? label : prefix + label;
    }

    /** The help page a word asks for: 1 when it is missing or not a number. */
    static int pageOf(String word) {
        if (word == null) {
            return 1;
        }
        try {
            return Integer.parseInt(word.trim());
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    // ---------------------------------------------------------------- suggestions

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        try {
            return new ArrayList<>(suggestions(sender, args));
        } catch (RuntimeException | LinkageError e) {
            owner().getLogger().log(Level.WARNING, "Error suggesting for /" + normalizeLabel(alias), e);
            return new ArrayList<>();
        }
    }

    List<String> suggestions(CommandSender sender, String[] args) {
        if (args.length == 0 || rootPermission != null && !sender.hasPermission(rootPermission)) {
            return List.of();
        }
        boolean fallbackShown = fallback != null && fallback.visible && fallback.permitted(sender);
        if (args.length == 1) {
            List<String> out = childNames(top, sender, args[0], false);
            if (fallbackShown) {
                Set<String> seen = new HashSet<>();
                out.forEach(s -> seen.add(s.toLowerCase(Locale.ROOT)));
                for (String option : argSuggestions(fallback, sender, args)) {
                    if (seen.add(option.toLowerCase(Locale.ROOT))) {
                        out.add(option);
                    }
                }
            }
            return out;
        }
        Node node = child(args[0]);
        if (node == null) {
            return fallbackShown ? argSuggestions(fallback, sender, args) : List.of();
        }
        int index = 1;
        while (true) {
            // A hidden node suggests nothing, not even when its name is typed in full.
            if (!node.visible || !node.permitted(sender)) {
                return List.of();
            }
            if (!node.group()) {
                return argSuggestions(node, sender, List.of(args).subList(index, args.length).toArray(String[]::new));
            }
            if (index == args.length - 1) {
                return childNames(node.children, sender, args[index], node.groupHelp && node.child("help") == null);
            }
            Node next = node.child(args[index]);
            if (next == null) {
                return List.of();
            }
            node = next;
            index++;
        }
    }

    private static List<String> childNames(List<Node> nodes, CommandSender sender, String typed, boolean withHelp) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Node node : nodes) {
            if (node.visible && node.permitted(sender) && node.name.startsWith(prefix)) {
                names.add(node.name);
            }
        }
        if (withHelp && "help".startsWith(prefix)) {
            names.add("help");
        }
        names.sort(null);
        return names;
    }

    /** Suggestions for the last word, which is argument number {@code words.length - 1} of the leaf. */
    private static List<String> argSuggestions(Node leaf, CommandSender sender, String[] words) {
        if (words.length == 0 || leaf.args.isEmpty()) {
            return List.of();
        }
        int position = words.length - 1;
        int last = leaf.args.size() - 1;
        if (position > last && !leaf.greedyTail()) {
            return List.of();
        }
        Node.ArgSpec spec = leaf.args.get(Math.min(position, last));
        List<String> options = spec.arg().suggest(sender, words[position], leaf.argLabel(spec.name()));
        return options == null ? List.of() : options;
    }

    // ---------------------------------------------------------------- help

    /** One line of help: what to type, what it does and the permission it needs. */
    record HelpEntry(String usage, String description, String permission) {
    }

    void sendHelp(CommandSender sender, String label, int page) {
        if (rootPermission != null && !sender.hasPermission(rootPermission)) {
            commands.send(sender, "rslib.no-permission");
            return;
        }
        String shown = shownLabel(label);
        List<HelpEntry> entries = new ArrayList<>();
        collect(top, sender, shown, rootPermission, entries);
        sendPaged(sender, entries, page, "rslib.help.header",
                new Ph[] {Ph.of("plugin", owner().getName()), Ph.of("command", "/" + shown)},
                "rslib.help.footer", Ph.of("command", "/" + shown + " help"));
    }

    void sendGroupHelp(CommandSender sender, String label, Node group, int page) {
        String shown = shownLabel(label);
        List<HelpEntry> entries = new ArrayList<>();
        collect(group.children, sender, shown, inheritedPermission(group), entries);
        String path = "/" + shown + " " + group.path;
        sendPaged(sender, entries, page, "rslib.help.group-header",
                new Ph[] {Ph.of("group", group.name), Ph.of("path", path), Ph.of("description", group.description()),
                        Ph.of("plugin", owner().getName())},
                "rslib.help.group-footer", Ph.of("path", path));
    }

    /** One page of {@code entries} under its header; the footer, naming {@code target}, only when there are more. */
    private void sendPaged(CommandSender sender, List<HelpEntry> entries, int page, String headerKey, Ph[] header,
            String footerKey, Ph target) {
        Page<HelpEntry> pages = Page.of(entries, HELP_PAGE_SIZE);
        int shown = pages.clamp(page);
        commands.send(sender, headerKey, header);
        for (HelpEntry entry : pages.page(shown)) {
            sendEntry(sender, entry);
        }
        if (pages.totalPages() > 1) {
            commands.send(sender, footerKey, Ph.of("page", shown), Ph.of("total", pages.totalPages()), target);
        }
    }

    private void sendEntry(CommandSender sender, HelpEntry entry) {
        commands.send(sender, "rslib.help.entry", Ph.literal("usage", entry.usage()), Ph.of("description", entry.description()),
                Ph.of("permission", entry.permission()));
    }

    /** One entry per leaf the sender can use; a group with its own help counts as one entry. */
    private void collect(List<Node> nodes, CommandSender sender, String label, String inherited, List<HelpEntry> out) {
        for (Node node : nodes) {
            if (!inHelp(node, sender)) {
                continue;
            }
            String permission = node.permission != null ? node.permission : inherited;
            if (!node.group()) {
                out.add(new HelpEntry(usageOf(node, label, false), node.description(), permission == null ? "" : permission));
            } else if (node.groupHelp) {
                if (hasHelpEntry(node, sender)) {
                    out.add(new HelpEntry("/" + label + " " + node.path + " help", node.description(),
                            permission == null ? "" : permission));
                }
            } else {
                collect(node.children, sender, label, permission, out);
            }
        }
    }

    private static boolean inHelp(Node node, CommandSender sender) {
        return node.visible && node.helpVisible && node.permitted(sender);
    }

    /** True when {@link #collect} would list at least one entry under {@code group}; stops at the first. */
    private static boolean hasHelpEntry(Node group, CommandSender sender) {
        for (Node node : group.children) {
            if (inHelp(node, sender) && (!node.group() || hasHelpEntry(node, sender))) {
                return true;
            }
        }
        return false;
    }

    /** The permission shown for entries under {@code group}: the closest one declared on its path. */
    private String inheritedPermission(Node group) {
        String found = rootPermission;
        List<Node> level = top;
        for (String word : group.path.split(" ")) {
            Node step = null;
            for (Node node : level) {
                if (node.name.equals(word)) {
                    step = node;
                    break;
                }
            }
            if (step == null) {
                break;
            }
            if (step.permission != null) {
                found = step.permission;
            }
            level = step.children;
        }
        return found;
    }

    @Override
    public String toString() {
        return "RootCommand(/" + getName() + ", " + owner().getName() + ")";
    }
}
