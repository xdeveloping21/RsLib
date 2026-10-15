package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The menus the Rs plugins ship ({@code src/test/resources/real-guis/<plugin>/*.yml}, copies
 * of their {@code guis/} folders): every one loads, with no warning at all.
 */
class RealMenusTest {

    /** How many menu files the copies hold. */
    private static final int EXPECTED = 72;

    @Test
    void everyRealMenuLoadsWithoutWarnings() throws IOException, URISyntaxException {
        URL root = RealMenusTest.class.getResource("/real-guis");
        assertNotNull(root, "the copies of the real menus are missing");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of(root.toURI()))) {
            files = walk.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        }
        List<String> problems = new ArrayList<>();
        int loaded = 0;
        for (Path file : files) {
            GuiKit kit = new GuiKit();
            String id = file.getParent().getFileName() + ":" + GuiFiles.stripExtension(file.getFileName().toString());
            GuiDef def = kit.parse(id, Files.readString(file, StandardCharsets.UTF_8));
            if (def != null) {
                loaded++;
            }
            for (String warning : kit.allWarnings()) {
                problems.add(id + " -> " + warning);
            }
        }
        assertEquals(EXPECTED, files.size(), "menu files found");
        assertEquals(EXPECTED, loaded);
        assertTrue(problems.isEmpty(), problems.size() + " warnings:\n" + String.join("\n", problems));
    }

    /** The pieces the real menus lean on most come out as written. */
    @Test
    void realMenusKeepTheirShape() throws IOException, URISyntaxException {
        GuiKit kit = new GuiKit();
        GuiDef pin = kit.parse("pin", read("RsStaff/pin.yml"));
        assertEquals("DROPPER", pin.inventoryTypeName());
        assertEquals(9, pin.size());
        GuiDef trade = kit.parse("trade", read("RsTrade/trade.yml"));
        assertEquals(PlayerInventoryPolicy.OPEN, trade.playerInventory());
        boolean anyInput = false;
        for (int slot = 0; slot < trade.size(); slot++) {
            anyInput |= trade.declaredInput(slot);
        }
        assertTrue(anyInput || trade.templates().values().stream().anyMatch(t -> t.item().input()),
                "the trade menu receives items");
        assertTrue(kit.allWarnings().isEmpty(), kit.allWarnings().toString());
    }

    private static String read(String path) throws IOException, URISyntaxException {
        URL url = RealMenusTest.class.getResource("/real-guis/" + path);
        assertNotNull(url, path);
        return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
    }
}
