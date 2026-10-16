package com.rs.lib.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.internal.LibMessages;
import com.rs.lib.text.RsText;
import com.rs.lib.util.Formats.RelativeTexts;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** {@code {key:relative}} in an action list is written in the language of the plugin running it. */
class ActionRelativeTest {

    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final RelativeTexts SPANISH = new RelativeTexts("hace {time}", "en {time}", "ahora", "{amount} d",
            "{amount} h", "{amount} min", "{amount} s");

    private final FakeActionHost host = new FakeActionHost();
    private final ActionEngine engine = new ActionEngine(host);
    private final TestPlayer steve = new TestPlayer();

    @AfterEach
    void reset() {
        LibMessages.reset();
    }

    @Test
    void messagesCommandsAndDelaysUseThePluginsLanguage() {
        host.relative = () -> SPANISH;
        Ph ends = Ph.of("t", System.currentTimeMillis() + 2 * HOUR + 30_000);
        engine.run(steve.player, List.of("[message] Termina {t:relative}", "[console] say {t:relative}",
                "[delay] 1", "[console] later {t:relative}"), ActionContext.of(steve.player, null, ends));
        assertEquals("Termina en 2 h", PlainTextComponentSerializer.plainText().serialize(steve.messages.get(0)));
        assertEquals(List.of("say en 2 h"), host.console);
        host.runDelayed();
        assertEquals(List.of("say en 2 h", "later en 2 h"), host.console);
        assertEquals("in 2h", RsText.applyLocals("{t:relative}", ends), "RsLib's own language after the run");
    }

    @Test
    void anEngineWithoutAPluginLanguageUsesRsLibs() {
        LibMessages.use("es");
        Ph ends = Ph.of("t", System.currentTimeMillis() + 2 * HOUR + 30_000);
        engine.run(steve.player, List.of("[console] say {t:relative}"), ActionContext.of(steve.player, null, ends));
        assertEquals(List.of("say en 2 h"), host.console);
    }
}
