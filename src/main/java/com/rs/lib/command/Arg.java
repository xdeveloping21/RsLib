package com.rs.lib.command;

import java.util.List;
import java.util.Objects;

import org.bukkit.command.CommandSender;

import com.rs.lib.Ph;

/**
 * One typed argument of a command: how to read what the player typed and what to suggest.
 *
 * <p>The factories in {@link Args} cover the usual cases; a plugin implements this interface
 * for its own types (a material, a kit, a crate). Both methods run on the thread that runs the
 * command, normally the main thread, so they must be cheap and never block.</p>
 *
 * @param <T> the parsed value
 */
public interface Arg<T> {

    /** Reads {@code raw}; throws {@link ArgParseException} with the message to show when it is not valid. */
    T parse(String raw) throws ArgParseException;

    /** Same as {@link #parse(String)} with the sender at hand. RsLib always calls this one. */
    default T parse(CommandSender sender, String raw) throws ArgParseException {
        return parse(raw);
    }

    /** Suggestions for {@code partial}; RsLib filters nothing for custom arguments, so filter here. */
    List<String> suggest(CommandSender sender, String partial);

    /** Same as {@link #suggest(CommandSender, String)}, with the label shown for this argument. */
    default List<String> suggest(CommandSender sender, String partial, String argName) {
        return suggest(sender, partial);
    }

    /**
     * The value is not valid: RsLib sends {@code langKey} with {@code phs} and stops the command.
     * Keys can be RsLib's ({@code rslib.invalid-value}) or the plugin's own.
     */
    final class ArgParseException extends Exception {

        private final String langKey;
        private final Ph[] phs;

        public ArgParseException(String langKey, Ph... phs) {
            super(langKey, null, false, false);
            this.langKey = Objects.requireNonNull(langKey, "langKey");
            this.phs = phs == null ? new Ph[0] : phs.clone();
        }

        public String langKey() {
            return langKey;
        }

        public Ph[] phs() {
            return phs.clone();
        }
    }
}
