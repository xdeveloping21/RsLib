package com.rs.lib.command;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.rs.lib.Ph;
import com.rs.lib.command.Arg.ArgParseException;
import com.rs.lib.util.Amounts;
import com.rs.lib.util.TimeUtil;

/**
 * Ready-made argument types. Numbers accept the suffixes {@code K, M, B, T, Qa, Qi} and
 * grouping ({@code 2k}, {@code 1.5m}, {@code 1,500}, {@code 1,500.25}) and fail closed: an
 * ambiguous {@code 1.500} or an exponent is rejected instead of guessed (see {@link Amounts}).
 * Whatever the player typed is shown back as {@code {value}} without formatting.
 */
public final class Args {

    /** Most suggestions sent for one argument, applied after filtering by what was typed. */
    public static final int MAX_SUGGESTIONS = 500;

    private Args() {
    }

    /** An online player by exact name. Suggests the players the sender can see. */
    public static RsArg<Player> onlinePlayer() {
        return new RsArg<>(List.of(), false, null) {
            @Override
            protected Player read(CommandSender sender, String raw) throws ArgParseException {
                Player player = Bukkit.getPlayerExact(raw);
                if (player == null || sender instanceof Player viewer && !viewer.canSee(player)) {
                    throw new ArgParseException("rslib.player-not-found", Ph.literal("value", raw));
                }
                return player;
            }

            @Override
            protected Collection<String> options(CommandSender sender) {
                return visibleNames(sender);
            }
        };
    }

    /**
     * A player's UUID: online by exact name first, then the server's cache of known players.
     * Never looks a profile up over the network, so it never freezes the server.
     */
    public static RsArg<UUID> offlinePlayerUuid() {
        return new RsArg<>(List.of(), false, null) {
            @Override
            protected UUID read(CommandSender sender, String raw) throws ArgParseException {
                Player online = Bukkit.getPlayerExact(raw);
                if (online != null) {
                    return online.getUniqueId();
                }
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(raw);
                if (cached != null) {
                    return cached.getUniqueId();
                }
                throw new ArgParseException("rslib.player-not-found", Ph.literal("value", raw));
            }

            @Override
            protected Collection<String> options(CommandSender sender) {
                return visibleNames(sender);
            }
        };
    }

    /** One of the given options, case-insensitive; returns the option as written in the list. */
    public static RsArg<String> oneOf(Supplier<? extends Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return oneOf(sender -> options.get());
    }

    /** {@link #oneOf(Supplier)} with options that depend on the sender. */
    public static RsArg<String> oneOf(Function<CommandSender, ? extends Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return new RsArg<>(List.of(), false, null) {
            @Override
            protected String read(CommandSender sender, String raw) throws ArgParseException {
                Collection<String> all = options.apply(sender);
                if (all != null) {
                    for (String option : all) {
                        if (option != null && option.equalsIgnoreCase(raw)) {
                            return option;
                        }
                    }
                }
                throw new ArgParseException("rslib.invalid-value", Ph.literal("value", raw));
            }

            @Override
            protected Collection<String> options(CommandSender sender) {
                return options.apply(sender);
            }
        };
    }

    /** Any text, suggesting the given options; the command itself validates it. */
    public static RsArg<String> suggesting(Supplier<? extends Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return suggesting(sender -> options.get());
    }

    /** {@link #suggesting(Supplier)} with options that depend on the sender. */
    public static RsArg<String> suggesting(Function<CommandSender, ? extends Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return new RsArg<>(List.of(), false, null) {
            @Override
            protected String read(CommandSender sender, String raw) {
                return raw;
            }

            @Override
            protected Collection<String> options(CommandSender sender) {
                return options.apply(sender);
            }
        };
    }

    /** A whole number from {@code min} to {@code max}; suggests both limits. */
    public static RsArg<Integer> intRange(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        return new RsArg<>(List.of(String.valueOf(min), String.valueOf(max)), false, null) {
            @Override
            protected Integer read(CommandSender sender, String raw) throws ArgParseException {
                BigDecimal value = whole(raw);
                if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0) {
                    throw new ArgParseException("rslib.out-of-range", Ph.of("min", min), Ph.of("max", max),
                            Ph.literal("value", raw));
                }
                return value.intValueExact();
            }
        };
    }

    /** A number from {@code min} to {@code max}; suggests both limits. */
    public static RsArg<Double> doubleRange(double min, double max) {
        if (!(min <= max)) {
            throw new IllegalArgumentException("min > max: " + min + " > " + max);
        }
        return new RsArg<>(List.of(plain(min), plain(max)), false, null) {
            @Override
            protected Double read(CommandSender sender, String raw) throws ArgParseException {
                double value = decimal(raw);
                if (value < min || value > max) {
                    throw new ArgParseException("rslib.out-of-range", Ph.of("min", plain(min)),
                            Ph.of("max", plain(max)), Ph.literal("value", raw));
                }
                return value;
            }
        };
    }

    /** A whole number of at least {@code min}, with no upper limit (amounts). */
    public static RsArg<Integer> intMin(int min) {
        return new RsArg<>(List.of(), false, "") {
            @Override
            protected Integer read(CommandSender sender, String raw) throws ArgParseException {
                BigDecimal value = whole(raw);
                if (value.compareTo(BigDecimal.valueOf(min)) < 0) {
                    throw new ArgParseException("rslib.number-too-small", Ph.of("min", min), Ph.literal("value", raw));
                }
                try {
                    return value.intValueExact();
                } catch (ArithmeticException tooLarge) {
                    throw invalidNumber(raw);
                }
            }
        };
    }

    /** A number of at least {@code min}, with no upper limit. */
    public static RsArg<Double> doubleMin(double min) {
        return new RsArg<>(List.of(), false, "") {
            @Override
            protected Double read(CommandSender sender, String raw) throws ArgParseException {
                double value = decimal(raw);
                if (value < min) {
                    throw new ArgParseException("rslib.number-too-small", Ph.of("min", plain(min)),
                            Ph.literal("value", raw));
                }
                return value;
            }
        };
    }

    /** Longest duration a command accepts (about 100 years), so adding it to a time never overflows. */
    public static final long MAX_DURATION_MILLIS = 100L * 365 * 24 * 60 * 60 * 1000;

    /**
     * A duration in milliseconds, greater than zero ({@code 30s}, {@code 5m}, {@code 1d 2h 30m}),
     * read like a duration in a config ({@link TimeUtil#parseMillis}): decimals and spelled-out
     * units work, a bare number is seconds, unknown units are skipped, and a result of zero is
     * rejected. As the last argument it takes the rest of the line, so {@code 1d 2h} and
     * {@code 1d2h} are the same; anywhere else it is one word. A sign, a comma between digits or
     * an exponent is rejected instead of being read wrong ({@code 1,5h} is not {@code 1s 5h}).
     */
    public static RsArg<Long> duration() {
        return new RsArg<>(List.of("30s", "5m", "1h", "1d"), true, null) {
            @Override
            protected Long read(CommandSender sender, String raw) throws ArgParseException {
                long millis = SUSPICIOUS_DURATION.matcher(raw).find() ? 0 : TimeUtil.parseMillis(raw);
                if (millis <= 0 || millis > MAX_DURATION_MILLIS) {
                    throw new ArgParseException("rslib.invalid-value", Ph.literal("value", raw));
                }
                return millis;
            }

            @Override
            boolean restMarked() {
                return false;
            }
        };
    }

    /** {@code true/yes/on} or {@code false/no/off}, case-insensitive. */
    public static RsArg<Boolean> bool() {
        return new RsArg<>(List.of("true", "false"), false, null) {
            @Override
            protected Boolean read(CommandSender sender, String raw) throws ArgParseException {
                return switch (raw.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "on" -> Boolean.TRUE;
                    case "false", "no", "off" -> Boolean.FALSE;
                    default -> throw new ArgParseException("rslib.invalid-value", Ph.literal("value", raw));
                };
            }
        };
    }

    /** One word, as typed. */
    public static RsArg<String> string() {
        return string(null);
    }

    /** One word, as typed; suggests {@code <hint>} while nothing is typed. */
    public static RsArg<String> string(String hint) {
        return text(false, hint);
    }

    /** The rest of the line, as typed. Only valid as the last argument. */
    public static RsArg<String> greedy() {
        return greedy(null);
    }

    /** {@link #greedy()} suggesting {@code <hint>} while nothing is typed. */
    public static RsArg<String> greedy(String hint) {
        return text(true, hint);
    }

    private static RsArg<String> text(boolean greedy, String hint) {
        return new RsArg<>(List.of(), greedy, hint == null ? "" : hint) {
            @Override
            protected String read(CommandSender sender, String raw) {
                return raw;
            }
        };
    }

    /** Signed on purpose: {@code -5} for {@code intMin(1)} reads as "too small", not "not a number". */
    private static final Pattern SUSPICIOUS_DURATION =
            Pattern.compile("[-+]|\\d\\s*+,\\s*+\\d|\\d[eE][-+]?\\d");

    /** One comma then exactly three digits, with an optional suffix: {@code 1,500} or {@code 2,500k}. */
    private static final Pattern ONE_GROUP =
            Pattern.compile("\\s*+([-+]?\\d{1,3}),(\\d{3}(?:[kKmMbBtT]|[qQ][aAiI])?)\\s*+");

    /**
     * {@code 1,500} as {@code 1500}: in a command a single comma before exactly three digits
     * groups thousands. {@link Amounts} leaves that layout out as ambiguous, so it is resolved
     * here, for commands only; everything else reaches {@link Amounts} untouched.
     */
    static String grouped(String raw) {
        Matcher group = ONE_GROUP.matcher(raw);
        return group.matches() ? group.group(1) + group.group(2) : raw;
    }

    private static BigDecimal whole(String raw) throws ArgParseException {
        Optional<BigDecimal> value = Amounts.parseSigned(grouped(raw));
        if (value.isEmpty()) {
            throw invalidNumber(raw);
        }
        BigDecimal exact = value.get().stripTrailingZeros();
        if (exact.scale() > 0) {
            throw invalidNumber(raw);
        }
        return exact;
    }

    private static double decimal(String raw) throws ArgParseException {
        OptionalDouble value = Amounts.parseSignedDouble(grouped(raw));
        if (value.isEmpty()) {
            throw invalidNumber(raw);
        }
        return value.getAsDouble();
    }

    private static ArgParseException invalidNumber(String raw) {
        return new ArgParseException("rslib.invalid-number", Ph.literal("value", raw));
    }

    private static String plain(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static List<String> visibleNames(CommandSender sender) {
        Player viewer = sender instanceof Player p ? p : null;
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (viewer == null || viewer.canSee(online)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    /**
     * Base of every argument made by {@link Args}: handles suggestions so each type only says
     * how to read a value and which options exist.
     */
    public abstract static class RsArg<T> implements Arg<T> {

        private final List<String> examples;
        private final boolean greedy;
        private final String hint;
        private volatile Supplier<String> current;

        /**
         * @param examples fixed suggestions, shown when they match what was typed
         * @param greedy   takes the rest of the line when it is the last argument
         * @param hint     null for no {@code <hint>}; empty to show the argument's label; else the text
         */
        protected RsArg(List<String> examples, boolean greedy, String hint) {
            this.examples = List.copyOf(examples);
            this.greedy = greedy;
            this.hint = hint;
        }

        /** Suggests the current value first (for "set" commands). */
        public RsArg<T> suggestCurrent(Supplier<String> current) {
            this.current = current;
            return this;
        }

        @Override
        public boolean greedy() {
            return greedy;
        }

        /** True when the usage line marks it with {@code ...}: a greedy one that reads loose words. */
        boolean restMarked() {
            return greedy;
        }

        /** Reads one value; {@code sender} is never null. */
        protected abstract T read(CommandSender sender, String raw) throws ArgParseException;

        /** The options to suggest for {@code sender}; may be large, RsLib filters and caps it. */
        protected Collection<String> options(CommandSender sender) {
            return List.of();
        }

        @Override
        public final T parse(String raw) throws ArgParseException {
            return read(Bukkit.getConsoleSender(), raw);
        }

        @Override
        public final T parse(CommandSender sender, String raw) throws ArgParseException {
            return read(sender == null ? Bukkit.getConsoleSender() : sender, raw);
        }

        @Override
        public final List<String> suggest(CommandSender sender, String partial) {
            return suggest(sender, partial, "value");
        }

        @Override
        public final List<String> suggest(CommandSender sender, String partial, String argName) {
            String typed = partial == null ? "" : partial;
            String prefix = typed.toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Supplier<String> now = current;
            if (now != null) {
                try {
                    add(out, seen, now.get(), prefix);
                } catch (RuntimeException ignored) {
                    // A failing "current value" only costs that one suggestion.
                }
            }
            if (hint != null && (typed.isEmpty() || typed.startsWith("<"))) {
                add(out, seen, bracketed(hint.isEmpty() ? argName : hint), prefix);
            }
            for (String example : examples) {
                add(out, seen, example, prefix);
            }
            Collection<String> all = options(sender);
            if (all != null && !all.isEmpty() && out.size() < MAX_SUGGESTIONS) {
                for (String option : firstMatches(all, prefix, seen, MAX_SUGGESTIONS - out.size())) {
                    add(out, seen, option, prefix);
                }
            }
            return out;
        }

        /** A matching option and where it was in the options, so equal ones keep their order. */
        private record Ranked(String value, String lower, int index) {
        }

        private static final Comparator<Ranked> RANKED = Comparator.comparing(Ranked::value, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(Ranked::index);

        /**
         * The first {@code room} options starting with {@code prefix} in case-insensitive order,
         * one per lowercase form and none already {@code seen}: what sorting every match and
         * adding them in order gives, but only the options that may still make it are kept (in
         * a max-heap) and lowercased.
         */
        private static List<String> firstMatches(Collection<String> all, String prefix, Set<String> seen, int room) {
            PriorityQueue<Ranked> kept = new PriorityQueue<>(RANKED.reversed());
            Map<String, Ranked> byLower = new HashMap<>();
            int index = 0;
            for (String option : all) {
                int at = index++;
                if (option == null || !option.regionMatches(true, 0, prefix, 0, prefix.length())
                        || kept.size() >= room && String.CASE_INSENSITIVE_ORDER.compare(option, kept.peek().value()) >= 0) {
                    continue;
                }
                String lower = option.toLowerCase(Locale.ROOT);
                if (option.isBlank() || !lower.startsWith(prefix) || seen.contains(lower)) {
                    continue;
                }
                Ranked ranked = new Ranked(option, lower, at);
                Ranked same = byLower.get(lower);
                if (same != null) {
                    if (RANKED.compare(ranked, same) >= 0) {
                        continue;
                    }
                    kept.remove(same);
                }
                byLower.put(lower, ranked);
                kept.add(ranked);
                if (kept.size() > room) {
                    byLower.remove(kept.poll().lower());
                }
            }
            List<Ranked> sorted = new ArrayList<>(kept);
            sorted.sort(RANKED);
            List<String> values = new ArrayList<>(sorted.size());
            for (Ranked ranked : sorted) {
                values.add(ranked.value());
            }
            return values;
        }

        /** {@code <text>}; a hint the plugin already wrapped in angle brackets is not wrapped twice. */
        static String bracketed(String text) {
            String inner = text.startsWith("<") ? text.substring(1) : text;
            inner = inner.endsWith(">") ? inner.substring(0, inner.length() - 1) : inner;
            return "<" + inner + ">";
        }

        private static void add(List<String> out, Set<String> seen, String value, String prefix) {
            if (value == null || value.isBlank()) {
                return;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith(prefix) && seen.add(lower)) {
                out.add(value);
            }
        }
    }
}
