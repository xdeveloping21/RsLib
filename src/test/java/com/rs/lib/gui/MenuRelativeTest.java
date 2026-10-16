package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;
import com.rs.lib.gui.GuiKit.TestPlayer;
import com.rs.lib.internal.LibMessages;
import com.rs.lib.text.RsText;
import com.rs.lib.util.Formats.RelativeTexts;

/** {@code {key:relative}} in a menu is written in the language of the plugin that owns the menu. */
class MenuRelativeTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final RelativeTexts SPANISH = new RelativeTexts("hace {time}", "en {time}", "ahora", "{amount} d",
            "{amount} h", "{amount} min", "{amount} s");
    private static final Supplier<RelativeTexts> ES = () -> SPANISH;

    private final GuiKit kit = new GuiKit();

    @AfterEach
    void reset() {
        LibMessages.reset();
    }

    private static Ph threeDaysAgo() {
        return Ph.of("t", System.currentTimeMillis() - 3 * DAY - 30_000);
    }

    @Test
    void titleLoreAndClickActionsUseThePluginsLanguage() {
        kit.relative = ES;
        kit.engine.relativeTexts(ES);
        kit.renderLore = true;
        Gui gui = kit.gui("seen", """
                title: 'Visto {t:relative}'
                templates:
                  p:
                    material: STONE
                    lore: ['Conectado {t:relative}']
                    click-actions: ['[console] say {t:relative}']
                """);
        TestPlayer steve = kit.player("Steve");
        Ph t = threeDaysAgo();
        gui.open(steve.player, t);
        GuiSession session = gui.session(steve.player);
        assertEquals("Visto hace 3 d", kit.title(steve));
        session.bind(4, gui.template("p"), t);
        assertEquals(List.of("Conectado hace 3 d"), kit.lore);
        session.handleClick(4, ClickType.LEFT);
        kit.tick();
        assertEquals(List.of("say hace 3 d"), kit.engine.console());
        assertEquals("3d ago", RsText.applyLocals("{t:relative}", t), "RsLib's own language after the render");
    }

    @Test
    void aMenuWithoutAPluginLanguageUsesRsLibs() {
        LibMessages.use("es");
        kit.renderLore = true;
        Gui gui = kit.gui("seen", """
                title: 'Seen {t:relative}'
                templates:
                  p: {material: STONE, lore: ['Online {t:relative}']}
                """);
        TestPlayer steve = kit.player("Steve");
        Ph t = threeDaysAgo();
        gui.open(steve.player, t);
        gui.session(steve.player).bind(4, gui.template("p"), t);
        assertEquals("Seen hace 3 d", kit.title(steve));
        assertEquals(List.of("Online hace 3 d"), kit.lore);
    }
}
