package com.rs.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Trampa 21: which files are menus, in the plugin's jar and on disk. */
class GuiFilesTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @TempDir
    Path dir;

    @Test
    void onlyTopLevelMenusAreSeededFromTheJar() throws IOException {
        Path jar = dir.resolve("plugin.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : List.of("plugin.yml", "guis/", "guis/shop.yml", "guis/admin.yml", "guis/extra/deep.yml",
                    "guis/readme.txt", "guis/old-shop-20260101-101010.yml", "other/guis/x.yml")) {
                out.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) {
                    out.write("title: x\n".getBytes());
                }
                out.closeEntry();
            }
        }
        assertEquals(List.of("admin", "shop"), GuiFiles.shipped(jar, "guis", LOG));
    }

    /** guis/ and every extra folder: shipped names are managed (seeded and merged), disk-only ones plain. */
    @Test
    void shippedFilesAreManagedAndTheRestPlain() {
        Map<String, Boolean> mounts = GuiFiles.mounts(List.of("main", "shop"), List.of("custom", "main", "shop"));
        assertEquals(List.of("main", "shop", "custom"), List.copyOf(mounts.keySet()));
        assertEquals(Boolean.TRUE, mounts.get("main"));
        assertEquals(Boolean.TRUE, mounts.get("shop"));
        assertEquals(Boolean.FALSE, mounts.get("custom"));
    }

    @Test
    void aClassesFolderWorksToo() throws IOException {
        Path classes = Files.createDirectories(dir.resolve("classes/guis"));
        Files.writeString(classes.resolve("main.yml"), "title: x\n");
        Files.createDirectories(classes.resolve("nested"));
        Files.writeString(classes.resolve("nested/inner.yml"), "title: x\n");
        assertEquals(List.of("main"), GuiFiles.shipped(dir.resolve("classes"), "guis", LOG));
    }

    @Test
    void backupsAndOtherFilesOnDiskAreNotMenus() throws IOException {
        Path guis = Files.createDirectories(dir.resolve("guis"));
        for (String name : List.of("shop.yml", "b.yml", "old-shop-20261013-142530.yml", "notes.txt", "old-town.yml")) {
            try (OutputStream out = Files.newOutputStream(guis.resolve(name))) {
                out.write("title: x\n".getBytes());
            }
        }
        Files.createDirectories(guis.resolve("sub.yml"));
        assertEquals(List.of("b", "old-town", "shop"), GuiFiles.onDisk(guis.toFile()));
        assertEquals(List.of(), GuiFiles.onDisk(dir.resolve("missing").toFile()));
        assertFalse(GuiFiles.isMenuFile(".yml"));
        assertTrue(GuiFiles.isMenuFile("Shop.YML"));
    }
}
