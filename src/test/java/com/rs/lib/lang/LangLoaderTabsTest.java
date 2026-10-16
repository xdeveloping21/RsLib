package com.rs.lib.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.yml.YmlTestSupport.Captured;

/** Message files indented with tabs are read repaired, with the same warning as any other file. */
class LangLoaderTabsTest {

    @TempDir
    Path dir;

    @Test
    void tabsInAMessageFileAreRepairedWithOneWarningAndLeftOnDisk() throws IOException {
        Path file = dir.resolve("messages_en.yml");
        Files.writeString(file, "menu:\n\ttitle: Shop\n\tfooter: Bye\n");
        Captured log = new Captured();
        assertEquals("Shop", LangLoader.parse(file.toFile(), log.logger).getString("menu.title"));
        assertEquals(List.of("Indentation tabs fixed in messages_en.yml (lines [2, 3])"), log.warnings());
        assertEquals("menu:\n\ttitle: Shop\n\tfooter: Bye\n", Files.readString(file));
    }
}
