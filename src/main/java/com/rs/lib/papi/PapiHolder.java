package com.rs.lib.papi;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;

import com.rs.lib.text.SharedPlaceholders;

/**
 * One expansion as {@link ExpansionBuilder#register()} left it: identifier, author, version and
 * the placeholders, frozen. No PlaceholderAPI type is referenced here; the expansion PAPI sees
 * ({@code PapiBridge}) only forwards {@code onRequest} to {@link #resolve}.
 *
 * <p>Resolution is the hot path (scoreboards ask every few ticks for every player), so it does
 * not allocate unless the parameter has capital letters or a prefix matches: exact names are
 * looked up first, ignoring case; then prefixes, in the order they were declared, also ignoring
 * case. The text after a prefix keeps its capitals. Player placeholders stay unresolved when
 * there is no player; global ones answer anyway, and while {@link SharedPlaceholders} is on
 * (a menu's timed refresh) each global value is computed once per tick for every viewer.</p>
 */
final class PapiHolder {

    /** Minimum time between two warnings about the same failing placeholder. */
    static final long WARN_INTERVAL_NANOS = 60_000_000_000L;

    /** Computes a value; {@code rest} is the text after a prefix ({@code ""} for exact names). */
    @FunctionalInterface
    interface Resolver {
        String resolve(OfflinePlayer player, String rest);
    }

    /** A placeholder or prefix and what answers it. Failure bookkeeping is only touched on errors. */
    static final class Entry {

        final boolean needsPlayer;
        final Resolver resolver;
        private long lastWarn;
        private int quiet;
        private boolean warned;

        Entry(boolean needsPlayer, Resolver resolver) {
            this.needsPlayer = needsPlayer;
            this.resolver = resolver;
        }

        /** The first failure is logged with its trace; later ones at most once a minute. */
        synchronized String failureNote(long now) {
            if (!warned) {
                warned = true;
                lastWarn = now;
                return "";
            }
            if (now - lastWarn < WARN_INTERVAL_NANOS) {
                quiet++;
                return null;
            }
            lastWarn = now;
            String note = quiet == 0 ? " (again)" : " (again; " + quiet + " more failures not logged)";
            quiet = 0;
            return note;
        }
    }

    private final String identifier;
    private final String author;
    private final String version;
    private final Map<String, Entry> exact;
    private final String[] prefixes;
    private final Entry[] prefixEntries;
    private final Logger logger;

    PapiHolder(String identifier, String author, String version, Map<String, Entry> exact,
            Map<String, Entry> prefixes, Logger logger) {
        this.identifier = identifier;
        this.author = author;
        this.version = version;
        this.exact = Map.copyOf(exact);
        this.prefixes = prefixes.keySet().toArray(new String[0]);
        this.prefixEntries = prefixes.values().toArray(new Entry[0]);
        this.logger = logger;
    }

    String identifier() {
        return identifier;
    }

    /** The identifier as PlaceholderAPI stores it. */
    String key() {
        return key(identifier);
    }

    static String key(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    String author() {
        return author;
    }

    String version() {
        return version;
    }

    /** Expansions survive {@code /papi reload}: they are registered by code, not loaded from disk. */
    boolean persist() {
        return true;
    }

    /** The value of {@code %<identifier>_<params>%}, or null to leave the token as written. */
    String resolve(OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }
        Entry entry = exact.get(params.toLowerCase(Locale.ROOT));
        String rest = "";
        if (entry == null) {
            for (int i = 0; i < prefixes.length; i++) {
                String prefix = prefixes[i];
                if (params.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    entry = prefixEntries[i];
                    rest = params.substring(prefix.length());
                    break;
                }
            }
            if (entry == null) {
                return null;
            }
        }
        if (entry.needsPlayer && player == null) {
            return null;
        }
        if (!entry.needsPlayer && SharedPlaceholders.active()) {
            // Global: the same for every viewer, so computed once per tick while menus share values.
            Entry global = entry;
            String after = rest;
            return SharedPlaceholders.value(global, after, () -> compute(global, player, after, params));
        }
        return compute(entry, player, rest, params);
    }

    private String compute(Entry entry, OfflinePlayer player, String rest, String params) {
        try {
            return entry.resolver.resolve(player, rest);
        } catch (RuntimeException | LinkageError e) {
            failed(entry, params, e);
            return null;
        }
    }

    private void failed(Entry entry, String params, Throwable error) {
        String note = entry.failureNote(System.nanoTime());
        if (note == null) {
            return;
        }
        String message = "Placeholder %" + identifier + "_" + params + "% failed" + note + ": " + error;
        if (note.isEmpty()) {
            logger.log(Level.WARNING, message, error);
        } else {
            logger.warning(message);
        }
    }
}
