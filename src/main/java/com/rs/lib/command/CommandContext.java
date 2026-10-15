package com.rs.lib.command;

import java.util.List;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** What a subcommand receives when it runs: the sender, the parsed arguments and the raw words. */
public final class CommandContext {

    private final RootCommand root;
    private final CommandSender sender;
    private final String label;
    private final Map<String, Object> values;
    private final List<String> raw;

    CommandContext(RootCommand root, CommandSender sender, String label, Map<String, Object> values, List<String> raw) {
        this.root = root;
        this.sender = sender;
        this.label = label;
        this.values = values;
        this.raw = raw;
    }

    public CommandSender sender() {
        return sender;
    }

    /** The root that is running the subcommand. */
    RootCommand root() {
        return root;
    }

    /** The command as typed, lowercase and without a {@code plugin:} prefix ({@code c} for {@code /c}). */
    public String label() {
        return label;
    }

    /**
     * The sender as a player. When the sender is the console, RsLib stops the command and sends
     * {@code rslib.players-only}; the exception is an {@link IllegalStateException}.
     */
    public Player player() {
        if (sender instanceof Player player) {
            return player;
        }
        throw new PlayersOnlyException();
    }

    /**
     * The parsed value of an argument. An optional argument that was not typed is not present:
     * check {@link #has(String)} or {@link #raw(int)} first.
     *
     * @throws IllegalArgumentException when the argument was not declared or not typed
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        if (!values.containsKey(name)) {
            throw new IllegalArgumentException("Argument not declared or not typed: " + name);
        }
        return (T) values.get(name);
    }

    /** True when the argument was typed (always true for required ones). */
    public boolean has(String name) {
        return values.containsKey(name);
    }

    public int getInt(String name) {
        Object value = get(name);
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    public double getDouble(String name) {
        Object value = get(name);
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    /** The value of an argument declared with {@code Args.onlinePlayer()}. */
    public Player player(String name) {
        return get(name);
    }

    /** The {@code index}-th word after the subcommand's path, or null when there are fewer words. */
    public @Nullable String raw(int index) {
        return index >= 0 && index < raw.size() ? raw.get(index) : null;
    }

    /** How many words came after the subcommand's path. */
    public int rawCount() {
        return raw.size();
    }

    /** Thrown by {@link #player()} for a non-player sender; RsLib answers it with a message. */
    static final class PlayersOnlyException extends IllegalStateException {
        PlayersOnlyException() {
            super("This command can only be used by players");
        }
    }
}
