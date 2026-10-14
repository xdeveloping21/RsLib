package com.rs.lib.command;

import org.bukkit.command.CommandSender;

/** What {@code onEmpty} receives: the command was typed with nothing after it. */
public final class RootContext {

    private final RootCommand root;
    private final CommandSender sender;
    private final String label;

    RootContext(RootCommand root, CommandSender sender, String label) {
        this.root = root;
        this.sender = sender;
        this.label = label;
    }

    public CommandSender sender() {
        return sender;
    }

    /** The command as typed, lowercase and without a {@code plugin:} prefix. */
    public String label() {
        return label;
    }

    /** Sends the first page of the generated help. */
    public void help() {
        help(1);
    }

    public void help(int page) {
        root.sendHelp(sender, label, page);
    }
}
