package com.rs.lib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.Ph;
import com.rs.lib.internal.LibMessages;
import com.rs.lib.util.Formats.RelativeTexts;
import com.rs.lib.yml.RsYml;
import com.rs.lib.yml.YmlTestSupport;

/**
 * {@code {key:relative}} in an item is written in the language of the plugin whose registry
 * renders it, also for an appearance built in code (no YAML file of the plugin behind it).
 * Building the stack itself needs a server, so the render is checked on the builder.
 */
class ItemRelativeTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final RelativeTexts SPANISH = new RelativeTexts("hace {time}", "en {time}", "ahora", "{amount} d",
            "{amount} h", "{amount} min", "{amount} s");
    private static final Supplier<RelativeTexts> ES = () -> SPANISH;

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        LibMessages.reset();
    }

    private static List<String> lore(ItemAppearance look, Ph t) {
        return ItemTexts.lore(look, null, ItemAppearance.locals(t));
    }

    private static Ph twoDaysAgo() {
        return Ph.of("t", System.currentTimeMillis() - 2 * DAY - 30_000);
    }

    @Test
    void anItemBuiltInCodeUsesThePluginsLanguage() throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("material: PAPER\nlore: ['Creado {t:relative}']\n");
        ItemAppearance look = ItemAppearance.parse(config, "code", null, message -> { });
        ItemDef def = ItemDef.builder().appearance(look).build();
        Ph t = twoDaysAgo();
        assertEquals(List.of("Creado 2d ago"), lore(def.appearance(), t), "no plugin: RsLib's language");
        assertEquals(List.of("Creado hace 2 d"), ItemRegistry.render(ES, () -> lore(def.appearance(), t)));
        assertEquals(List.of("Creado 2d ago"), lore(def.appearance(), t), "the outer language is back");
        LibMessages.use("es");
        assertEquals(List.of("Creado hace 2 d"), ItemRegistry.render(() -> null, () -> lore(def.appearance(), t)),
                "a plugin without a language: RsLib's");
    }

    @Test
    void anItemFileUsesThePluginsLanguage() throws IOException {
        Path file = dir.resolve("items.yml");
        Files.writeString(file, "sword:\n  material: STONE\n  lore: ['Visto {t:relative}']\n", StandardCharsets.UTF_8);
        YmlTestSupport.FakeHost host = new YmlTestSupport.FakeHost();
        host.relative = ES;
        RsYml yml = YmlTestSupport.open(file.toFile(), host);
        ItemAppearance look = ItemAppearance.of(yml, "sword");
        Ph t = twoDaysAgo();
        assertEquals(List.of("Visto hace 2 d"), lore(look, t));
        assertEquals(List.of("Visto hace 2 d"), ItemRegistry.render(ES, () -> lore(look, t)));
    }
}
