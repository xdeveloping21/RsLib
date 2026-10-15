package com.rs.lib.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.text.RsText;

class RsLangStaticTest {

    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void prefixGoesAfterLeadingTags() {
        assertEquals("[center][small]P text", RsText.insertPrefix("[center][small]text", "P "));
        assertEquals("P text", RsText.insertPrefix("text", "P "));
        assertEquals("[CENTER]P a [center]", RsText.insertPrefix("[CENTER]a [center]", "P "));
        assertEquals("[center][noprefix]x", RsText.insertPrefix("[center][noprefix]x", "P "));
        assertEquals("P [x]y", RsText.insertPrefix("[x]y", "P "));
    }

    @Test
    void legacyBlockIsRenamedOnceKeepingTexts(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages_en.yml");
        Files.writeString(file, "prefix: \"&7\"\nsnlib:\n  no-permission: \"&cNope\"\nother:\n  snlib: x\n",
                StandardCharsets.UTF_8);
        LangLoader.renameLegacyBlock(file.toFile(), LOG);
        assertEquals("prefix: \"&7\"\nrslib:\n  no-permission: \"&cNope\"\nother:\n  snlib: x\n",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void existingSharedBlockWins(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("messages_en.yml");
        String text = "rslib:\n  a: b\nsnlib:\n  a: c\n";
        Files.writeString(file, text, StandardCharsets.UTF_8);
        LangLoader.renameLegacyBlock(file.toFile(), LOG);
        assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
        LangLoader.renameLegacyBlock(new File(dir.toFile(), "absent.yml"), LOG);
    }

    @Test
    void parseErrorFlagsOnlyBrokenFiles(@TempDir Path dir) throws Exception {
        Path good = dir.resolve("good.yml");
        Path bad = dir.resolve("bad.yml");
        Files.writeString(good, "prefix: \"&7\"\nhello: \"Hi\"\n", StandardCharsets.UTF_8);
        Files.writeString(bad, "hello: \"Hi\n  broken: [\n", StandardCharsets.UTF_8);
        assertNull(LangLoader.parseError(good.toFile()));
        assertNull(LangLoader.parseError(dir.resolve("missing.yml").toFile()));
        String error = LangLoader.parseError(bad.toFile());
        assertNotNull(error);
        assertEquals(-1, error.indexOf('\n'));
    }
}
