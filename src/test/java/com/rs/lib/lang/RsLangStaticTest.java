package com.rs.lib.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsLangStaticTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void prefixGoesAfterLeadingTags() {
        assertEquals("[center][small]P text", RsLang.insertPrefix("[center][small]text", "P "));
        assertEquals("P text", RsLang.insertPrefix("text", "P "));
        assertEquals("[CENTER]P a [center]", RsLang.insertPrefix("[CENTER]a [center]", "P "));
        assertEquals("[center][noprefix]x", RsLang.insertPrefix("[center][noprefix]x", "P "));
        assertEquals("P [x]y", RsLang.insertPrefix("[x]y", "P "));
    }

    @Test
    void legacyBlockIsRenamedOnceKeepingTexts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages_en.yml");
        Files.writeString(file, "prefix: \"&7\"\nsnlib:\n  no-permission: \"&cNope\"\nother:\n  snlib: x\n",
                StandardCharsets.UTF_8);
        RsLang.renameLegacyBlock(file.toFile(), LOG);
        assertEquals("prefix: \"&7\"\nrslib:\n  no-permission: \"&cNope\"\nother:\n  snlib: x\n",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void existingSharedBlockWins(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages_en.yml");
        String text = "rslib:\n  a: b\nsnlib:\n  a: c\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        RsLang.renameLegacyBlock(file.toFile(), LOG);
        assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
        RsLang.renameLegacyBlock(new File(dir.toFile(), "absent.yml"), LOG);
    }
}
