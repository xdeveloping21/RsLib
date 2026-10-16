package com.rs.lib.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Money, dates and relative times written the way the server owner set them in RsLib's
 * {@code plugins/RsLib/formats.yml}, for every Rs plugin. Static like {@link NumberFormatter}
 * and {@link TimeUtil}, which stay fixed: what a plugin stores or parses never goes through
 * this class, only what a player reads.
 *
 * <ul>
 *   <li>{@link #money}: symbol before or after, a fixed number of decimals (HALF_UP), the
 *       thousands and decimal separators, and from {@code abbreviate-from} on a short form with
 *       the configured suffixes ({@code $1.5M}, at most two decimals, rounded down so it never
 *       shows more than there is). Below the threshold the amount is written in full, so a price
 *       to approve is exact.</li>
 *   <li>{@link #date}: named styles, each a {@link DateTimeFormatter} pattern, in the
 *       configured locale and the server's time zone.</li>
 *   <li>{@link #relative}: {@code 3d ago} / {@code in 2h} with the largest whole unit (days,
 *       hours, minutes, seconds; rounded down), {@code now} under one second. The words come
 *       from a {@link RelativeTexts}; a plugin's own language is {@code rs().formats()}. In a
 *       text, {@code {key:relative}} writes the epoch milliseconds of {@code key} so, in the
 *       language of the plugin rendering it ({@link #placeholderTexts}).</li>
 *   <li>{@link #progress}: a fraction as a percentage, rounded down, so 99.96% never reads 100%.</li>
 * </ul>
 *
 * <p>Settings are one immutable object replaced as a whole by {@link #apply}: a render sees all
 * the old values or all the new ones. Until RsLib loads its file the defaults apply
 * ({@link Settings#DEFAULTS}). Locale independent apart from the date locale; any thread.</p>
 */
public final class Formats {

    private static final Logger LOG = Logger.getLogger("RsLib");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final int MAX_DECIMALS = 8;
    private static final Set<String> WARNED_STYLES = ConcurrentHashMap.newKeySet();

    private static volatile Compiled current = new Compiled(Settings.DEFAULTS);
    /** The words of {@code :relative} for the render running on this thread (its plugin's language). */
    private static final ThreadLocal<Supplier<RelativeTexts>> RENDERING = new ThreadLocal<>();
    /** The words of {@code :relative} when no render says otherwise: RsLib's own language. */
    private static volatile Supplier<RelativeTexts> fallbackTexts = () -> RelativeTexts.ENGLISH;

    private Formats() {
    }

    /** Where the money symbol goes. */
    public enum SymbolPosition { BEFORE, AFTER }

    /**
     * Everything {@code formats.yml} sets.
     *
     * @param symbol             the money symbol, with any space it needs ({@code " €"})
     * @param symbolPosition     before or after the amount
     * @param decimals           decimals of a full amount, 0 to 8
     * @param thousandsSeparator between groups of three digits; empty for none
     * @param decimalSeparator   before the decimals
     * @param abbreviateFrom     from this amount on money is abbreviated; 0 or less never
     * @param suffixes           thousands, millions, ... in order (at least one)
     * @param dateLocale         the language of month and day names
     * @param dateStyle          the style {@link #date(long)} uses; one of {@code dateStyles}
     * @param dateStyles         pattern of each style, by lower-case name (at least one)
     */
    public record Settings(String symbol, SymbolPosition symbolPosition, int decimals, String thousandsSeparator,
                           String decimalSeparator, BigDecimal abbreviateFrom, List<String> suffixes,
                           Locale dateLocale, String dateStyle, Map<String, String> dateStyles) {

        /** The values of the jar's {@code formats.yml}. */
        public static final Settings DEFAULTS = new Settings("$", SymbolPosition.BEFORE, 2, ",", ".",
                BigDecimal.valueOf(1_000_000L), List.of("K", "M", "B", "T", "Qa", "Qi"), Locale.ENGLISH, "date",
                defaultStyles());

        public Settings {
            symbol = symbol == null ? "" : symbol;
            symbolPosition = symbolPosition == null ? SymbolPosition.BEFORE : symbolPosition;
            decimals = Math.max(0, Math.min(MAX_DECIMALS, decimals));
            thousandsSeparator = thousandsSeparator == null ? "" : thousandsSeparator;
            decimalSeparator = decimalSeparator == null || decimalSeparator.isEmpty() ? "." : decimalSeparator;
            abbreviateFrom = abbreviateFrom == null ? BigDecimal.ZERO : abbreviateFrom;
            suffixes = suffixes == null || suffixes.isEmpty() ? List.of("K", "M", "B", "T", "Qa", "Qi")
                    : List.copyOf(suffixes);
            dateLocale = dateLocale == null ? Locale.ENGLISH : dateLocale;
            Map<String, String> styles = new LinkedHashMap<>();
            if (dateStyles != null) {
                dateStyles.forEach((name, pattern) -> styles.put(name.toLowerCase(Locale.ROOT), pattern));
            }
            if (styles.isEmpty()) {
                styles.putAll(defaultStyles());
            }
            dateStyles = Collections.unmodifiableMap(styles);
            dateStyle = dateStyle == null ? "" : dateStyle.toLowerCase(Locale.ROOT);
            if (!styles.containsKey(dateStyle)) {
                dateStyle = styles.keySet().iterator().next();
            }
        }

        private static Map<String, String> defaultStyles() {
            Map<String, String> styles = new LinkedHashMap<>();
            styles.put("date", "dd/MM/yyyy");
            styles.put("time", "HH:mm");
            styles.put("date-time", "dd/MM/yyyy HH:mm");
            styles.put("long", "d MMMM yyyy");
            return styles;
        }

        /**
         * Reads {@code formats.yml}: {@code root} gives the value at a dotted path, null when
         * missing, with a section as a {@link Map} of its keys in order (null {@code root} gives
         * the defaults). A missing key takes its default; a wrong one too, reported to
         * {@code warn} with the key. Takes no Bukkit type, so the text pipeline stays usable on
         * the proxy.
         */
        public static Settings read(Function<String, Object> root, Consumer<String> warn) {
            if (root == null) {
                return DEFAULTS;
            }
            Consumer<String> out = warn == null ? message -> { } : warn;
            Settings d = DEFAULTS;
            String position = text(root, "money.symbol-position", d.symbolPosition.name().toLowerCase(Locale.ROOT), out);
            SymbolPosition symbolPosition = switch (position.trim().toLowerCase(Locale.ROOT)) {
                case "before" -> SymbolPosition.BEFORE;
                case "after" -> SymbolPosition.AFTER;
                default -> {
                    out.accept("money.symbol-position: '" + position + "' is not before or after; using before.");
                    yield SymbolPosition.BEFORE;
                }
            };
            int decimals = d.decimals;
            Object rawDecimals = root.apply("money.decimals");
            if (rawDecimals != null) {
                if (rawDecimals instanceof Integer whole && whole >= 0 && whole <= MAX_DECIMALS) {
                    decimals = whole;
                } else {
                    out.accept("money.decimals: '" + rawDecimals + "' is not a whole number from 0 to " + MAX_DECIMALS
                            + "; using " + d.decimals + ".");
                }
            }
            BigDecimal abbreviateFrom = d.abbreviateFrom;
            Object rawFrom = root.apply("money.abbreviate-from");
            if (rawFrom != null) {
                if (rawFrom instanceof Number number && Double.isFinite(number.doubleValue())) {
                    abbreviateFrom = new BigDecimal(number.toString());
                } else {
                    out.accept("money.abbreviate-from: '" + rawFrom + "' is not a number; using "
                            + d.abbreviateFrom.toPlainString() + ".");
                }
            }
            List<String> suffixes = d.suffixes;
            Object rawSuffixes = root.apply("money.suffixes");
            if (rawSuffixes != null) {
                List<String> read = new ArrayList<>();
                if (rawSuffixes instanceof List<?> list) {
                    for (Object each : list) {
                        if (each != null && !String.valueOf(each).isBlank()) {
                            read.add(String.valueOf(each).trim());
                        }
                    }
                }
                if (read.isEmpty()) {
                    out.accept("money.suffixes: '" + rawSuffixes + "' is not a list of suffixes; using " + d.suffixes
                            + ".");
                } else {
                    suffixes = read;
                }
            }
            String language = text(root, "date.locale", d.dateLocale.toLanguageTag(), out).trim();
            Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
            if (language.isEmpty() || locale.getLanguage().isEmpty()) {
                out.accept("date.locale: '" + language + "' is not a language code; using en.");
                locale = Locale.ENGLISH;
            }
            Map<String, String> styles = d.dateStyles;
            Object rawStyles = root.apply("date.styles");
            if (rawStyles != null && !(rawStyles instanceof Map<?, ?>)) {
                out.accept("date.styles: expected one 'name: pattern' line per style; using the default ones.");
            } else if (rawStyles != null) {
                Map<String, String> read = new LinkedHashMap<>();
                for (Map.Entry<?, ?> style : ((Map<?, ?>) rawStyles).entrySet()) {
                    String name = String.valueOf(style.getKey());
                    Object pattern = style.getValue();
                    String written = pattern == null || pattern instanceof Map<?, ?> ? "" : String.valueOf(pattern);
                    String problem = written.isBlank() ? "it is empty" : null;
                    try {
                        DateTimeFormatter.ofPattern(written);
                    } catch (IllegalArgumentException invalid) {
                        problem = invalid.getMessage();
                    }
                    if (problem == null) {
                        read.put(name.toLowerCase(Locale.ROOT), written);
                    } else {
                        out.accept("date.styles." + name + ": '" + written + "' is not a date pattern (" + problem
                                + "); the style is left out.");
                    }
                }
                if (!read.isEmpty()) {
                    styles = read;
                } else {
                    out.accept("date.styles has no valid style; using the default ones.");
                }
            }
            String style = text(root, "date.style", d.dateStyle, out).trim().toLowerCase(Locale.ROOT);
            if (!styles.containsKey(style)) {
                String first = styles.keySet().iterator().next();
                out.accept("date.style: there is no style '" + style + "' in date.styles; using '" + first + "'.");
                style = first;
            }
            return new Settings(text(root, "money.symbol", d.symbol, out), symbolPosition, decimals,
                    text(root, "money.thousands-separator", d.thousandsSeparator, out),
                    nonEmpty(root, "money.decimal-separator", d.decimalSeparator, out), abbreviateFrom, suffixes, locale,
                    style, styles);
        }

        private static String text(Function<String, Object> root, String key, String def, Consumer<String> warn) {
            Object value = root.apply(key);
            if (value == null) {
                return def;
            }
            if (value instanceof Map<?, ?> || value instanceof List<?>) {
                warn.accept(key + ": expected a text; using '" + def + "'.");
                return def;
            }
            return String.valueOf(value);
        }

        private static String nonEmpty(Function<String, Object> root, String key, String def, Consumer<String> warn) {
            String value = text(root, key, def, warn);
            if (value.isEmpty()) {
                warn.accept(key + ": cannot be empty; using '" + def + "'.");
                return def;
            }
            return value;
        }
    }

    /**
     * The words of a relative time. {@code past} and {@code future} hold {@code {time}}; each
     * unit holds {@code {amount}}.
     */
    public record RelativeTexts(String past, String future, String now, String days, String hours, String minutes,
                                String seconds) {

        /** English: {@code 3d ago}, {@code in 2h}, {@code now}. */
        public static final RelativeTexts ENGLISH = new RelativeTexts("{time} ago", "in {time}", "now", "{amount}d",
                "{amount}h", "{amount}m", "{amount}s");

        /** A null text takes the English one. */
        public RelativeTexts {
            past = past == null ? "{time} ago" : past;
            future = future == null ? "in {time}" : future;
            now = now == null ? "now" : now;
            days = days == null ? "{amount}d" : days;
            hours = hours == null ? "{amount}h" : hours;
            minutes = minutes == null ? "{amount}m" : minutes;
            seconds = seconds == null ? "{amount}s" : seconds;
        }
    }

    // ------------------------------------------------------------------ settings

    /** Replaces the settings at once (RsLib does it when it loads {@code formats.yml}). */
    public static void apply(Settings settings) {
        current = new Compiled(Objects.requireNonNull(settings, "settings"));
    }

    /** The settings in use. */
    public static Settings settings() {
        return current.settings;
    }

    // ------------------------------------------------------------------ money

    /** {@link #money(BigDecimal)} of a double, read as its shortest decimal; NaN and infinities as Java writes them. */
    public static String money(double amount) {
        if (!Double.isFinite(amount)) {
            return Double.toString(amount);
        }
        return money(BigDecimal.valueOf(amount));
    }

    /** {@link #money(BigDecimal)} of a whole amount. */
    public static String money(long amount) {
        return money(BigDecimal.valueOf(amount));
    }

    /** The amount as money: {@code $1,250.00}, {@code $1.5M} from the threshold on. Null gives null. */
    public static String money(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        Compiled c = current;
        Settings s = c.settings;
        BigDecimal abs = amount.abs();
        String digits;
        boolean zero;
        if (s.abbreviateFrom.signum() > 0 && abs.compareTo(s.abbreviateFrom) >= 0) {
            int index = 0;
            BigDecimal v = abs;
            while (v.compareTo(THOUSAND) >= 0 && index < s.suffixes.size()) {
                v = v.movePointLeft(3);
                index++;
            }
            BigDecimal rounded = v.setScale(2, RoundingMode.DOWN).stripTrailingZeros();
            zero = rounded.signum() == 0;
            digits = group(rounded.toPlainString(), s) + (index == 0 ? "" : s.suffixes.get(index - 1));
        } else {
            BigDecimal rounded = abs.setScale(s.decimals, RoundingMode.HALF_UP);
            zero = rounded.signum() == 0;
            digits = group(rounded.toPlainString(), s);
        }
        String sign = amount.signum() < 0 && !zero ? "-" : "";
        return s.symbolPosition == SymbolPosition.BEFORE ? sign + s.symbol + digits : sign + digits + s.symbol;
    }

    /** Plain digits with a dot ({@code 1234.50}) written with the configured separators. */
    private static String group(String plain, Settings s) {
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        StringBuilder out = new StringBuilder(plain.length() + whole.length() / 3 * s.thousandsSeparator.length() + 2);
        int first = whole.length() % 3 == 0 ? 3 : whole.length() % 3;
        out.append(whole, 0, first);
        for (int i = first; i < whole.length(); i += 3) {
            out.append(s.thousandsSeparator).append(whole, i, i + 3);
        }
        if (dot >= 0) {
            out.append(s.decimalSeparator).append(plain, dot + 1, plain.length());
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ dates

    /** {@code epochMillis} in the default style, in the server's time zone. */
    public static String date(long epochMillis) {
        Compiled c = current;
        return format(c.styles.get(c.settings.dateStyle), epochMillis);
    }

    /**
     * {@code epochMillis} in the style {@code style} (any case); an unknown style uses the
     * default one and is reported once.
     */
    public static String date(long epochMillis, String style) {
        Compiled c = current;
        DateTimeFormatter formatter = style == null ? null : c.styles.get(style.toLowerCase(Locale.ROOT));
        if (formatter == null) {
            if (WARNED_STYLES.add(String.valueOf(style))) {
                LOG.warning("[RsLib] There is no date style '" + style + "' in formats.yml; using '"
                        + c.settings.dateStyle + "'.");
            }
            formatter = c.styles.get(c.settings.dateStyle);
        }
        return format(formatter, epochMillis);
    }

    /** A pattern that cannot write a date (a field an instant does not have) gives the ISO form. */
    private static String format(DateTimeFormatter formatter, long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        try {
            return formatter.format(instant);
        } catch (DateTimeException unsupported) {
            return instant.toString();
        }
    }

    // ------------------------------------------------------------------ relative times

    /** The clock relative times are measured against; tests replace it to avoid sleeping. */
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** Tests only: measure relative times against {@code now} ({@code null} restores the real clock). */
    static void clock(LongSupplier now) {
        clock = now == null ? System::currentTimeMillis : now;
    }

    /** {@code epochMillis} from now, in English: {@code 3d ago}, {@code in 2h}, {@code now}. */
    public static String relative(long epochMillis) {
        return relative(epochMillis, clock.getAsLong(), RelativeTexts.ENGLISH);
    }

    /** {@code epochMillis} from now with {@code texts}. */
    public static String relative(long epochMillis, RelativeTexts texts) {
        return relative(epochMillis, clock.getAsLong(), texts);
    }

    /**
     * {@code epochMillis} seen from {@code nowMillis}: the largest whole unit of the gap
     * (rounded down), in the past or future text; under one second, the now text.
     */
    public static String relative(long epochMillis, long nowMillis, RelativeTexts texts) {
        RelativeTexts t = texts == null ? RelativeTexts.ENGLISH : texts;
        long gap;
        try {
            gap = Math.subtractExact(epochMillis, nowMillis);
        } catch (ArithmeticException overflow) {
            gap = epochMillis < nowMillis ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        long abs = gap == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(gap);
        if (abs < SECOND) {
            return t.now();
        }
        String unit;
        long amount;
        if (abs >= DAY) {
            unit = t.days();
            amount = abs / DAY;
        } else if (abs >= HOUR) {
            unit = t.hours();
            amount = abs / HOUR;
        } else if (abs >= MINUTE) {
            unit = t.minutes();
            amount = abs / MINUTE;
        } else {
            unit = t.seconds();
            amount = abs / SECOND;
        }
        String time = unit.replace("{amount}", Long.toString(amount));
        return (gap < 0 ? t.past() : t.future()).replace("{time}", time);
    }

    // ------------------------------------------------------------------ relative times in placeholders

    /**
     * The words a {@code {key:relative}} placeholder is written with on this thread now: those of
     * the plugin whose text is being rendered ({@link #enterPlaceholderTexts}), else RsLib's own
     * language ({@code lang} in its config.yml), else English.
     */
    public static RelativeTexts placeholderTexts() {
        Supplier<RelativeTexts> rendering = RENDERING.get();
        RelativeTexts texts = rendering == null ? null : rendering.get();
        if (texts == null) {
            texts = fallbackTexts.get();
        }
        return texts == null ? RelativeTexts.ENGLISH : texts;
    }

    /**
     * Makes {@code texts} the words of {@code :relative} on this thread while a plugin renders
     * one of its texts; it is asked only when such a placeholder is met, and may answer null
     * (RsLib's own language then). Always give the returned value back to
     * {@link #exitPlaceholderTexts} in a {@code finally}:
     *
     * <pre>
     * Object previous = Formats.enterPlaceholderTexts(texts);
     * try {
     *     ... render ...
     * } finally {
     *     Formats.exitPlaceholderTexts(previous);
     * }
     * </pre>
     */
    public static Object enterPlaceholderTexts(Supplier<RelativeTexts> texts) {
        Supplier<RelativeTexts> previous = RENDERING.get();
        RENDERING.set(texts);
        return previous;
    }

    /** Puts back what {@link #enterPlaceholderTexts} returned. */
    @SuppressWarnings("unchecked")
    public static void exitPlaceholderTexts(Object previous) {
        if (previous == null) {
            RENDERING.remove();
        } else {
            RENDERING.set((Supplier<RelativeTexts>) previous);
        }
    }

    /** The words of {@code :relative} outside any plugin's render (RsLib sets its own language); null is English. */
    public static void fallbackPlaceholderTexts(Supplier<RelativeTexts> texts) {
        fallbackTexts = texts == null ? () -> RelativeTexts.ENGLISH : texts;
    }

    // ------------------------------------------------------------------ progress

    /**
     * {@code ratio} (0 to 1, clamped; NaN reads as 0) as a percentage with up to
     * {@code decimals} decimals (0 to 6), rounded down and without trailing zeros:
     * {@code progress(0.9996, 1)} is {@code 99.9%}, {@code progress(1, 1)} is {@code 100%}.
     */
    public static String progress(double ratio, int decimals) {
        double clamped = Double.isNaN(ratio) ? 0 : Math.max(0, Math.min(1, ratio));
        int places = Math.max(0, Math.min(6, decimals));
        BigDecimal percent = BigDecimal.valueOf(clamped).multiply(HUNDRED).setScale(places, RoundingMode.DOWN);
        return (percent.signum() == 0 ? "0" : percent.stripTrailingZeros().toPlainString()) + "%";
    }

    /** Settings with the formatters built from them, so renders build nothing. */
    private static final class Compiled {

        final Settings settings;
        final Map<String, DateTimeFormatter> styles;

        Compiled(Settings settings) {
            this.settings = settings;
            Map<String, DateTimeFormatter> built = new LinkedHashMap<>();
            ZoneId zone = ZoneId.systemDefault();
            settings.dateStyles().forEach((name, pattern) -> {
                try {
                    built.put(name, DateTimeFormatter.ofPattern(pattern, settings.dateLocale()).withZone(zone));
                } catch (IllegalArgumentException invalid) {
                    LOG.warning("[RsLib] Date style '" + name + "' has an invalid pattern '" + pattern
                            + "'; using dd/MM/yyyy.");
                    built.put(name, DateTimeFormatter.ofPattern("dd/MM/yyyy", settings.dateLocale()).withZone(zone));
                }
            });
            this.styles = Map.copyOf(built);
        }
    }
}
