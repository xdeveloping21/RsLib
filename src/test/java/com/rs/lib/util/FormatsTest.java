package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.text.RsText;
import com.rs.lib.util.Formats.RelativeTexts;
import com.rs.lib.util.Formats.Settings;
import com.rs.lib.util.Formats.SymbolPosition;

/** formats.yml: money, dates, relative times, progress, the reader and the placeholder formats. */
class FormatsTest {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 1_790_000_000_000L;

    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void restore() {
        Formats.apply(Settings.DEFAULTS);
    }

    private static YamlConfiguration resource(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = FormatsTest.class.getResourceAsStream(name)) {
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    /** What RsLib hands the reader: a section becomes a map of its keys. */
    private static Function<String, Object> values(ConfigurationSection root) {
        return path -> {
            Object value = root.get(path);
            return value instanceof ConfigurationSection section ? section.getValues(false) : value;
        };
    }

    private Settings read(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return Settings.read(values(config), warnings::add);
    }

    // ---------------------------------------------------------------- money

    @Test
    void defaultMoneyIsWrittenInFullBelowAMillion() {
        assertEquals("$1,250.00", Formats.money(1250));
        assertEquals("$0.50", Formats.money(0.5));
        assertEquals("$999,999.99", Formats.money(999_999.99));
        assertEquals("$1,000,000.00", Formats.money(999_999.999));
        assertEquals("-$1,250.50", Formats.money(-1250.5));
        assertEquals("$0.00", Formats.money(-0.001));
        assertEquals("$1.01", Formats.money(new BigDecimal("1.005")));
        assertEquals("$1.01", Formats.money(1.005));
        assertEquals("NaN", Formats.money(Double.NaN));
        assertNull(Formats.money((BigDecimal) null));
    }

    @Test
    void moneyIsAbbreviatedFromTheThresholdRoundingDown() {
        assertEquals("$1M", Formats.money(1_000_000));
        assertEquals("$1.5M", Formats.money(1_500_000));
        assertEquals("$1.99M", Formats.money(1_999_999));
        assertEquals("$999.99M", Formats.money(999_999_999));
        assertEquals("-$2.5B", Formats.money(-2_500_000_000L));
        assertEquals("$1,000Qi", Formats.money(new BigDecimal("1e21")));
    }

    @Test
    void moneyFollowsTheSettings() {
        Formats.apply(new Settings(" €", SymbolPosition.AFTER, 0, ".", ",", BigDecimal.valueOf(10_000), List.of("k", "M"),
                Locale.ENGLISH, "date", null));
        assertEquals("1.251 €", Formats.money(1250.6));
        assertEquals("9.999 €", Formats.money(9999));
        assertEquals("12,5k €", Formats.money(12_500));
        assertEquals("2.500M €", Formats.money(2_500_000_000L));
        Formats.apply(new Settings("$", SymbolPosition.BEFORE, 3, "", ".", BigDecimal.ZERO, null, null, null, null));
        assertEquals("$123456789.000", Formats.money(123_456_789));
    }

    // ---------------------------------------------------------------- the file

    @Test
    void theJarFileHoldsTheDefaults() throws Exception {
        Settings read = Settings.read(values(resource("/formats.yml")), warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(Settings.DEFAULTS, read);
        assertEquals(Settings.DEFAULTS, Settings.read(null, warnings::add));
    }

    @Test
    void aWrongValueIsReportedAndTakesItsDefault() throws Exception {
        Settings read = read("""
                money:
                  symbol-position: middle
                  decimals: 2.5
                  abbreviate-from: lots
                  suffixes: k
                  decimal-separator: ''
                date:
                  locale: ''
                  style: nope
                  styles:
                    good: 'yyyy'
                    bad: 'qqqqqqqq{'
                    empty: ''
                """);
        assertEquals(SymbolPosition.BEFORE, read.symbolPosition());
        assertEquals(2, read.decimals());
        assertEquals(Settings.DEFAULTS.abbreviateFrom(), read.abbreviateFrom());
        assertEquals(Settings.DEFAULTS.suffixes(), read.suffixes());
        assertEquals(".", read.decimalSeparator());
        assertEquals(Locale.ENGLISH, read.dateLocale());
        assertEquals(Map.of("good", "yyyy"), read.dateStyles());
        assertEquals("good", read.dateStyle());
        assertEquals(9, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("money.symbol-position"), warnings.toString());
    }

    @Test
    void customValuesAreRead() throws Exception {
        Settings read = read("""
                money:
                  symbol: 'CLP '
                  symbol-position: before
                  decimals: 0
                  thousands-separator: '.'
                  decimal-separator: ','
                  abbreviate-from: 0
                  suffixes: [mil, M]
                date:
                  locale: es
                  style: LONG
                  styles:
                    LONG: "d 'de' MMMM yyyy"
                """);
        assertEquals(List.of(), warnings);
        Formats.apply(read);
        assertEquals("CLP 12.345.679", Formats.money(12_345_678.9));
        assertEquals(Map.of("long", "d 'de' MMMM yyyy"), read.dateStyles());
        long when = Instant.parse("2026-08-17T12:00:00Z").toEpochMilli();
        String expected = DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", Locale.forLanguageTag("es"))
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(when));
        assertEquals(expected, Formats.date(when));
        assertTrue(expected.contains("agosto"), expected);
    }

    // ---------------------------------------------------------------- dates

    @Test
    void datesUseTheNamedStyleInTheServerZone() {
        long when = Instant.parse("2026-08-17T14:30:05Z").toEpochMilli();
        ZoneId zone = ZoneId.systemDefault();
        assertEquals(DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(zone).format(Instant.ofEpochMilli(when)),
                Formats.date(when));
        assertEquals(DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(when)),
                Formats.date(when, "TIME"));
        // An unknown style falls back to the default one.
        assertEquals(Formats.date(when), Formats.date(when, "nope"));
        assertEquals(Formats.date(when), Formats.date(when, null));
    }

    // ---------------------------------------------------------------- relative

    @Test
    void relativeTimesUseTheLargestWholeUnit() {
        RelativeTexts en = RelativeTexts.ENGLISH;
        assertEquals("in 3d", Formats.relative(NOW + 3 * DAY + 5 * HOUR, NOW, en));
        assertEquals("3d ago", Formats.relative(NOW - 3 * DAY - 23 * HOUR, NOW, en));
        assertEquals("in 2h", Formats.relative(NOW + 2 * HOUR + 59 * MINUTE, NOW, en));
        assertEquals("5m ago", Formats.relative(NOW - 5 * MINUTE, NOW, en));
        assertEquals("in 59s", Formats.relative(NOW + 59 * SECOND + 999, NOW, en));
        assertEquals("now", Formats.relative(NOW + 999, NOW, en));
        assertEquals("now", Formats.relative(NOW - 999, NOW, en));
        assertEquals("1s ago", Formats.relative(NOW - SECOND, NOW, en));
        assertEquals("in 2h", Formats.relative(NOW + 2 * HOUR, NOW, null));
        assertTrue(Formats.relative(Long.MIN_VALUE, Long.MAX_VALUE, en).endsWith("d ago"));
    }

    @Test
    void aPluginWritesRelativeTimesInItsLanguage() throws Exception {
        YamlConfiguration spanish = resource("/rslib-messages_es.yml");
        RsFormats es = new RsFormats(spanish::getString);
        assertEquals("hace 3 d", es.relative(NOW - 3 * DAY, NOW));
        assertEquals("en 2 h", es.relative(NOW + 2 * HOUR, NOW));
        assertEquals("en 5 min", es.relative(NOW + 5 * MINUTE, NOW));
        assertEquals("hace 10 s", es.relative(NOW - 10 * SECOND, NOW));
        assertEquals("ahora", es.relative(NOW, NOW));
        RsFormats en = new RsFormats(resource("/rslib-messages_en.yml")::getString);
        assertEquals("3d ago", en.relative(NOW - 3 * DAY, NOW));
        assertEquals("in 2h", en.relative(NOW + 2 * HOUR, NOW));
        // Missing messages (no lang module, an old file) are English.
        RsFormats none = new RsFormats(key -> null);
        assertEquals(RelativeTexts.ENGLISH, none.texts());
        Map<String, String> partial = Map.of(RsFormats.PAST, "hace {time}");
        assertEquals("hace 4h", new RsFormats(partial::get).relative(NOW - 4 * HOUR, NOW));
        assertEquals("$1,250.00", none.money(1250));
    }

    @Test
    void bothLanguagesHaveTheSameFormatMessages() throws Exception {
        ConfigurationSection en = resource("/rslib-messages_en.yml").getConfigurationSection("rslib.format");
        ConfigurationSection es = resource("/rslib-messages_es.yml").getConfigurationSection("rslib.format");
        assertEquals(en.getKeys(false), es.getKeys(false));
        for (String key : List.of(RsFormats.PAST, RsFormats.FUTURE, RsFormats.NOW, RsFormats.DAYS, RsFormats.HOURS,
                RsFormats.MINUTES, RsFormats.SECONDS)) {
            assertTrue(en.getRoot().isString(key) && es.getRoot().isString(key), key);
        }
    }

    // ---------------------------------------------------------------- progress

    @Test
    void progressRoundsDown() {
        assertEquals("99.9%", Formats.progress(0.9996, 1));
        assertEquals("100%", Formats.progress(1, 1));
        assertEquals("29%", Formats.progress(0.29, 0));
        assertEquals("50%", Formats.progress(0.5, 2));
        assertEquals("33.33%", Formats.progress(1 / 3.0, 2));
        assertEquals("0%", Formats.progress(Double.NaN, 1));
        assertEquals("100%", Formats.progress(1.5, 1));
        assertEquals("0%", Formats.progress(-1, 1));
        assertEquals("0%", Formats.progress(0.0004, 1));
    }

    // ---------------------------------------------------------------- placeholders

    @Test
    void moneyAndDateArePlaceholderFormats() {
        assertEquals("Price: $1,500.00", RsText.applyLocals("Price: {price:money}", Ph.of("price", "1500")));
        assertEquals("Price: $2.5M", RsText.applyLocals("Price: %price:MONEY%", Ph.of("price", "2.5M")));
        assertEquals("Price: free", RsText.applyLocals("Price: {price:money|free}", Ph.of("price", "")));
        assertEquals("Price: abc", RsText.applyLocals("Price: {price:money}", Ph.of("price", "abc")));
        long when = Instant.parse("2026-08-17T14:30:05Z").toEpochMilli();
        assertEquals("On " + Formats.date(when), RsText.applyLocals("On {when:date}", Ph.of("when", when)));
        assertEquals("On -", RsText.applyLocals("On {when:date|-}", Ph.of("when", "")));
    }
}
