package com.rs.lib.util;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;

import com.rs.lib.Rs;
import com.rs.lib.lang.RsLang;

/**
 * {@link Formats} for one plugin, reached with {@code rs().formats()}: money and dates as
 * RsLib's {@code formats.yml} says, and relative times in the plugin's own language, read from
 * the {@code rslib.format.*} messages of its language file ({@code hace 3 d}, {@code en 2 h}).
 * A message that is missing, or a plugin without {@code lang()}, uses the English words.
 * The messages are read on every call, so a reload applies at once. Any thread.
 */
public final class RsFormats {

    /** The messages a relative time is written with. */
    static final String PAST = "rslib.format.relative-past";
    static final String FUTURE = "rslib.format.relative-future";
    static final String NOW = "rslib.format.relative-now";
    static final String DAYS = "rslib.format.days";
    static final String HOURS = "rslib.format.hours";
    static final String MINUTES = "rslib.format.minutes";
    static final String SECONDS = "rslib.format.seconds";

    private final Function<String, String> messages;

    /** Created by the context. */
    public RsFormats(Rs rs) {
        this(key -> {
            RsLang lang = Objects.requireNonNull(rs, "rs").langOrNull();
            return lang == null ? null : lang.rawOrNull(key);
        });
    }

    /** {@code messages} gives the text of a message key, or null when there is none. */
    RsFormats(Function<String, String> messages) {
        this.messages = messages;
    }

    /** See {@link Formats#money(BigDecimal)}. */
    public String money(BigDecimal amount) {
        return Formats.money(amount);
    }

    /** See {@link Formats#money(double)}. */
    public String money(double amount) {
        return Formats.money(amount);
    }

    /** See {@link Formats#money(long)}. */
    public String money(long amount) {
        return Formats.money(amount);
    }

    /** See {@link Formats#date(long)}. */
    public String date(long epochMillis) {
        return Formats.date(epochMillis);
    }

    /** See {@link Formats#date(long, String)}. */
    public String date(long epochMillis, String style) {
        return Formats.date(epochMillis, style);
    }

    /** {@code epochMillis} from now in the plugin's language: {@code hace 3 d}, {@code en 2 h}. */
    public String relative(long epochMillis) {
        return Formats.relative(epochMillis, texts());
    }

    /** {@code epochMillis} seen from {@code nowMillis}, in the plugin's language. */
    public String relative(long epochMillis, long nowMillis) {
        return Formats.relative(epochMillis, nowMillis, texts());
    }

    /** The relative-time words of the plugin's language file, English where one is missing. */
    public Formats.RelativeTexts texts() {
        return new Formats.RelativeTexts(messages.apply(PAST), messages.apply(FUTURE), messages.apply(NOW),
                messages.apply(DAYS), messages.apply(HOURS), messages.apply(MINUTES), messages.apply(SECONDS));
    }
}
