package com.rs.lib.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.account.AltAccount;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** RsLib's own texts: its language from config.yml, both files complete, values from players literal. */
class LibMessagesTest {

    private static final long NOW = 1_800_000_000_000L;

    @AfterEach
    void reset() {
        LibMessages.reset();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> plain(List<Component> lines) {
        return lines.stream().map(LibMessagesTest::plain).toList();
    }

    private static YamlConfiguration resource(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = LibMessagesTest.class.getResourceAsStream(name)) {
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void bothLanguagesHaveEveryAdminText() throws Exception {
        ConfigurationSection en = resource("/rslib-messages_en.yml").getConfigurationSection("rslib.admin");
        ConfigurationSection es = resource("/rslib-messages_es.yml").getConfigurationSection("rslib.admin");
        assertNotNull(en);
        assertNotNull(es);
        assertEquals(en.getKeys(true), es.getKeys(true));
        for (String key : en.getKeys(true)) {
            if (en.isString(key)) {
                assertTrue(es.isString(key), key);
                assertNotEquals("", es.getString(key), key);
            }
        }
    }

    /** Every key the commands and the settings menu ask for exists, as text, in both languages. */
    @Test
    void everyKeyInUseExistsInBothLanguages() {
        List<String> keys = List.of("logs.usage", "logs.player-not-found", "logs.plugin-not-found", "logs.bad-page",
                "logs.read-failed", "logs.everybody", "logs.every-plugin", "logs.none", "logs.empty-page", "logs.header",
                "logs.next-page", "logs.click-to-open", "db.usage", "db.usage-export", "db.usage-import",
                "db.no-database", "db.no-file", "db.running", "db.exporting", "db.exported", "db.importing",
                "db.importing-force", "db.imported", "db.progress", "db.export-refused", "db.export-failed",
                "db.import-refused", "db.import-failed", "alts.usage", "alts.disabled", "alts.read-failed",
                "alts.no-record", "alts.none", "alts.header", "alts.entry", "settings.players-only");
        List<String> menu = List.of("menu-title", "none", "plugin", "value", "state-on", "state-off", "click-toggle",
                "click-cycle", "click-number", "previous-page", "next-page", "close");
        for (String code : LibMessages.LANGUAGES) {
            assertTrue(LibMessages.use(code));
            for (String key : keys) {
                String full = "rslib.admin." + key;
                assertNotEquals(full, LibMessages.text(full), code);
            }
            for (String key : menu) {
                String full = "rslib.settings." + key;
                assertNotEquals(full, LibMessages.text(full), code);
            }
        }
        LibMessages.use("es");
        assertNotEquals(LibMessages.text("rslib.admin.alts.header"), resourceText("en", "rslib.admin.alts.header"));
    }

    private static String resourceText(String code, String key) {
        try {
            return resource("/rslib-messages_" + code + ".yml").getString(key);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theLanguageComesFromConfigAndFallsBackToEnglish() {
        assertEquals("en", LibMessages.language());
        assertTrue(LibMessages.use(" ES "));
        assertEquals("es", LibMessages.language());
        assertEquals("&8Preferencias", LibMessages.text("rslib.settings.menu-title"), "the settings menu too");
        assertEquals("Solo un jugador puede usar esto.", LibMessages.text("rslib.admin.settings.players-only"));
        assertEquals("rslib.admin.nope", LibMessages.text("rslib.admin.nope"));
        assertFalse(LibMessages.use("fr"));
        assertEquals("en", LibMessages.language());
        assertEquals("&8Settings", LibMessages.text("rslib.settings.menu-title"));
    }

    /** Usage lines hold angle brackets; they are shown as written, never read as tags. */
    @Test
    void usageLinesKeepTheirBrackets() {
        Ph label = Ph.literal("label", "rslib");
        assertEquals("Usage: /rslib logs <player|*> [plugin|*] [page]",
                plain(LibMessages.render("rslib.admin.logs.usage", label)));
        assertEquals("Usage: /rslib db <export <plugin>|import <plugin> <file> [--force]>",
                plain(LibMessages.render("rslib.admin.db.usage", label)));
        assertEquals("Usage: /rslib alts <player>", plain(LibMessages.render("rslib.admin.alts.usage", label)));
        LibMessages.use("es");
        assertEquals("Uso: /rslib logs <jugador|*> [plugin|*] [página]",
                plain(LibMessages.render("rslib.admin.logs.usage", label)));
        assertEquals("Uso: /rslib db import <plugin> <archivo> [--force]",
                plain(LibMessages.render("rslib.admin.db.usage-import", label)));
        assertEquals("Uso: /rslib alts <jugador>", plain(LibMessages.render("rslib.admin.alts.usage", label)));
    }

    @Test
    void altsInSpanishWithNamesAsWritten() {
        LibMessages.use("es");
        String typed = "&cBob<red>%x%";
        assertEquals(List.of("No hay registro de que " + typed + " haya entrado al servidor."),
                plain(AltsAdmin.lines(typed, null, NOW)));
        assertEquals(List.of("Zed no comparte dirección con ninguna otra cuenta."),
                plain(AltsAdmin.lines("Zed", List.of(), NOW)));
        assertEquals(List.of("Cuentas que entraron desde una dirección de Alex (1):",
                        "- Dana (00000000-0000-0000-0000-000000000004), visto por última vez hace 2d 3h"),
                plain(AltsAdmin.lines("Alex", List.of(new AltAccount(new UUID(0, 4), "Dana",
                        NOW - 2 * 86_400_000L - 3 * 3_600_000L)), NOW)));
        assertEquals("La detección de cuentas alternativas está desactivada (accounts.enabled en el config.yml de RsLib).",
                plain(AltsAdmin.off()));
    }

    @Test
    void dbTextsTakeTheReportCounts() {
        Ph[] phs = {Ph.literal("plugin", "Shop"), Ph.of("tables", 3), Ph.of("rows", 120), Ph.of("millis", 45),
            Ph.literal("file", "Shop-db.rsdump")};
        assertEquals("Export of Shop done: 3 tables, 120 rows in 45 ms. File: plugins/Shop/Shop-db.rsdump",
                plain(LibMessages.render("rslib.admin.db.exported", phs)));
        LibMessages.use("es");
        assertEquals("Exportación de Shop terminada: 3 tablas, 120 filas en 45 ms. Archivo: plugins/Shop/Shop-db.rsdump",
                plain(LibMessages.render("rslib.admin.db.exported", phs)));
        assertEquals("La importación en Shop falló: disco lleno", plain(LibMessages.render("rslib.admin.db.import-failed",
                Ph.literal("plugin", "Shop"), Ph.literal("reason", "disco lleno"))));
    }
}
