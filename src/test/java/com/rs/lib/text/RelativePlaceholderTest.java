package com.rs.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.Ph;
import com.rs.lib.internal.LibMessages;
import com.rs.lib.util.Formats;
import com.rs.lib.util.Formats.RelativeTexts;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlTestSupport;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** {@code {key:relative}}: in the language of the plugin rendering it, else RsLib's, and never stale. */
class RelativePlaceholderTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final RelativeTexts SPANISH = new RelativeTexts("hace {time}", "en {time}", "ahora", "{amount} d",
            "{amount} h", "{amount} min", "{amount} s");
    private static final Supplier<RelativeTexts> ES = () -> SPANISH;
    private static final Supplier<RelativeTexts> EN = () -> RelativeTexts.ENGLISH;

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        LibMessages.reset();
    }

    /** Some time ago, with a little margin so the whole unit does not change during the test. */
    private static Ph ago(String key, long millis) {
        return Ph.of(key, System.currentTimeMillis() - millis - 30_000);
    }

    private static Ph ahead(String key, long millis) {
        return Ph.of(key, System.currentTimeMillis() + millis + 30_000);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static String within(Supplier<RelativeTexts> texts, Supplier<String> render) {
        Object previous = Formats.enterPlaceholderTexts(texts);
        try {
            return render.get();
        } finally {
            Formats.exitPlaceholderTexts(previous);
        }
    }

    @Test
    void outsideAnyPluginItIsRsLibsOwnLanguage() {
        assertEquals("Seen 3d ago", RsText.applyLocals("Seen {t:relative}", ago("t", 3 * DAY)));
        assertEquals("Ends in 2h", RsText.applyLocals("Ends %t:RELATIVE%", ahead("t", 2 * HOUR)));
        LibMessages.use("es");
        assertEquals("Visto hace 3 d", RsText.applyLocals("Visto {t:relative}", ago("t", 3 * DAY)));
        assertEquals("Termina en 2 h", RsText.applyLocals("Termina {t:relative}", ahead("t", 2 * HOUR)));
        assertEquals("ahora", RsText.applyLocals("{t:relative}", Ph.of("t", System.currentTimeMillis() + 500)));
        LibMessages.reset();
        assertEquals("3d ago", RsText.applyLocals("{t:relative}", ago("t", 3 * DAY)));
    }

    @Test
    void aPluginsRenderUsesItsLanguageAndPutsTheOuterOneBack() {
        LibMessages.use("es");
        Ph t = ago("t", 3 * DAY);
        assertEquals("3d ago", within(EN, () -> RsText.applyLocals("{t:relative}", t)));
        assertEquals("hace 3 d", within(ES, () -> {
            String inner = within(EN, () -> RsText.applyLocals("{t:relative}", t));
            assertEquals("3d ago", inner);
            return RsText.applyLocals("{t:relative}", t);
        }));
        assertEquals("hace 3 d", within(() -> null, () -> RsText.applyLocals("{t:relative}", t)),
                "a plugin without a language: RsLib's");
        assertEquals("hace 3 d", RsText.applyLocals("{t:relative}", t), "back to RsLib's after the render");
    }

    @Test
    void notANumberOrMissingBehaveLikeTheOtherFormats() {
        assertEquals("soon", RsText.applyLocals("{t:relative}", Ph.of("t", "soon")));
        assertEquals("never", RsText.applyLocals("{t:relative|never}", Ph.of("t", "")));
        assertEquals("{x:relative}", RsText.applyLocals("{x:relative}", Ph.of("t", 1)));
    }

    /** The same compiled line with the same value: each render is in the language and at the time it runs. */
    @Test
    void aCompiledLineIsNeverStale() throws InterruptedException {
        RsText.Template line = RsText.compile("&7Last seen &f{t:relative}");
        Ph t = ago("t", 3 * DAY);
        assertEquals("Last seen 3d ago", plain(line.render(t)));
        Object previous = Formats.enterPlaceholderTexts(ES);
        try {
            assertEquals("Last seen hace 3 d", plain(line.render(t)));
        } finally {
            Formats.exitPlaceholderTexts(previous);
        }
        assertEquals("Last seen 3d ago", plain(line.render(t)));

        Ph almostAMinute = Ph.of("t", System.currentTimeMillis() - 59_300);
        String first = plain(line.render(almostAMinute));
        Thread.sleep(900);
        String later = plain(line.render(almostAMinute));
        assertEquals("Last seen 59s ago", first);
        assertEquals("Last seen 1m ago", later);
        assertNotEquals(first, later);
        assertFalse(RenderCache.contains("&7Last seen &f{t:relative}"));
    }

    @Test
    void aPluginsYamlTextUsesItsLanguage() throws IOException {
        Path file = dir.resolve("menu.yml");
        Files.writeString(file, "seen: \"Seen {t:relative}\"\n", StandardCharsets.UTF_8);
        YmlTestSupport.FakeHost host = new YmlTestSupport.FakeHost();
        RsYml yml = YmlTestSupport.open(file.toFile(), host);
        Ph t = ago("t", 2 * DAY);
        assertEquals("Seen 2d ago", yml.getString("seen", null, null, t));
        host.relative = ES;
        assertEquals("Seen hace 2 d", yml.getString("seen", null, null, t));
        assertEquals("Seen hace 2 d", yml.resolveText("Seen {t:relative}", null, key -> key.equals("t")
                ? String.valueOf(System.currentTimeMillis() - 2 * DAY - 30_000) : null));
        host.relative = () -> null;
        LibMessages.use("es");
        assertEquals("Seen hace 2 d", yml.getString("seen", null, null, t), "no plugin language: RsLib's");
        host.relative = EN;
        assertEquals("Seen 2d ago", yml.getString("seen", null, null, t));
    }
}
