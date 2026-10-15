package com.rs.lib.papi;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;

/**
 * Declares a PlaceholderAPI expansion, from {@link RsPapi#expansion(String)}:
 *
 * <pre>{@code
 * rs().papi().expansion("rskills")
 *         .placeholder("kills", player -> String.valueOf(kills(player)))
 *         .prefixed("top_", (player, rank) -> topName(rank))
 *         .global("online", () -> String.valueOf(Bukkit.getOnlinePlayers().size()))
 *         .register();
 * }</pre>
 *
 * <p>The tokens are {@code %<identifier>_<name>%}. Names and prefixes match ignoring case, an
 * exact name wins over any prefix, prefixes are tried in the order they were declared, and the
 * text after a prefix is passed on with its capitals. Player placeholders are left unresolved
 * when PlaceholderAPI asks without a player; global ones always answer. Resolvers run where
 * PlaceholderAPI asks (normally the main thread) and should only read memory: no files,
 * database or network. A resolver that throws leaves the token unresolved and is logged.</p>
 *
 * <p>Author and version default to the plugin's. Declaring a name or prefix twice keeps the
 * last resolver (a prefix keeps its first position).</p>
 */
public final class ExpansionBuilder {

    private final Predicate<PapiHolder> registrar;
    private final String identifier;
    private final Logger logger;
    private final Map<String, PapiHolder.Entry> exact = new HashMap<>();
    private final Map<String, PapiHolder.Entry> prefixes = new LinkedHashMap<>();
    private String author;
    private String version;

    ExpansionBuilder(Predicate<PapiHolder> registrar, String identifier, String author, String version, Logger logger) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("expansion identifier is blank");
        }
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.identifier = identifier.trim();
        this.author = author;
        this.version = version;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** {@code %<identifier>_<name>%} for a player; unresolved when there is none. */
    public ExpansionBuilder placeholder(String name, Function<OfflinePlayer, String> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        exact.put(key(name), new PapiHolder.Entry(true, (player, rest) -> resolver.apply(player)));
        return this;
    }

    /** {@code %<identifier>_<prefix><rest>%} for a player; the resolver gets the player and the rest. */
    public ExpansionBuilder prefixed(String prefix, BiFunction<OfflinePlayer, String, String> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        prefixes.put(key(prefix), new PapiHolder.Entry(true, resolver::apply));
        return this;
    }

    /** {@code %<identifier>_<name>%} that does not depend on a player. */
    public ExpansionBuilder global(String name, Supplier<String> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        exact.put(key(name), new PapiHolder.Entry(false, (player, rest) -> resolver.get()));
        return this;
    }

    /** {@code %<identifier>_<prefix><rest>%} that does not depend on a player; the resolver gets the rest. */
    public ExpansionBuilder globalPrefixed(String prefix, Function<String, String> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        prefixes.put(key(prefix), new PapiHolder.Entry(false, (player, rest) -> resolver.apply(rest)));
        return this;
    }

    /** Shown by {@code /papi info}; defaults to the plugin's authors, or its name. */
    public ExpansionBuilder author(String author) {
        this.author = Objects.requireNonNull(author, "author");
        return this;
    }

    /** Shown by {@code /papi info}; defaults to the plugin's version. */
    public ExpansionBuilder version(String version) {
        this.version = Objects.requireNonNull(version, "version");
        return this;
    }

    /**
     * Registers the expansion with what was declared so far, replacing any expansion with the
     * same identifier (also one from an earlier enable). It survives {@code /papi reload} and
     * is unregistered when the plugin is disabled. Returns false when PlaceholderAPI is not
     * there (it is registered by itself if PlaceholderAPI is enabled later), when called off
     * the main thread (it is registered on the next tick) or when PlaceholderAPI refused it.
     */
    public boolean register() {
        if (identifier.indexOf('_') >= 0 || identifier.indexOf('%') >= 0 || identifier.indexOf(' ') >= 0) {
            logger.warning("Placeholder expansion '" + identifier + "' cannot work: PlaceholderAPI identifiers"
                    + " cannot contain '_', '%' or spaces.");
        }
        return registrar.test(build());
    }

    PapiHolder build() {
        return new PapiHolder(identifier, author, version, exact, prefixes, logger);
    }

    private static String key(String name) {
        return PapiHolder.key(Objects.requireNonNull(name, "name"));
    }
}
