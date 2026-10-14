package com.rs.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlPreprocessorTest {

    @TempDir
    Path dir;

    @Test
    void leadingTabsBecomeTwoSpacesAndLinesAreReported() {
        YamlPreprocessor.Result r = YamlPreprocessor.preprocess("a:\n\tb:\n\t\tc: 2\n  \td: 3\n");
        assertEquals("a:\n  b:\n    c: 2\n    d: 3\n", r.cleanText());
        assertEquals(List.of(2, 3, 4), r.fixedLines());
        assertTrue(YamlUpdater.isParseable(r.cleanText()));
    }

    @Test
    void tabsInsideValuesAreKept() {
        YamlPreprocessor.Result r = YamlPreprocessor.preprocess("a: \"x\ty\"\nb: c\td\n");
        assertEquals("a: \"x\ty\"\nb: c\td\n", r.cleanText());
        assertTrue(r.fixedLines().isEmpty());
    }

    @Test
    void lineEndingsAreNormalized() {
        YamlPreprocessor.Result r = YamlPreprocessor.preprocess("a: 1\r\nb: 2\rc: 3\n");
        assertEquals("a: 1\nb: 2\nc: 3\n", r.cleanText());
        assertTrue(r.fixedLines().isEmpty());
    }

    @Test
    void blockScalarContentIsCopiedAsWritten() {
        String raw = "msg: |\n  line one\n  \tindented by a tab after spaces\n\n  last\n\tnext: 1\n";
        YamlPreprocessor.Result r = YamlPreprocessor.preprocess(raw);
        // A tab after the leading spaces is content. The last line is deeper than the indicator,
        // so it is still block content, but it starts with a tab (never valid): that tab is repaired.
        assertEquals("msg: |\n  line one\n  \tindented by a tab after spaces\n\n  last\n  next: 1\n",
                r.cleanText());
        assertEquals(List.of(6), r.fixedLines());
    }

    @Test
    void blockScalarEndsAtTheIndicatorIndentation() {
        String raw = "a:\n\tmsg: >-\n\t  folded\ttext\n\tafter:\n\t\tx: 1\n";
        YamlPreprocessor.Result r = YamlPreprocessor.preprocess(raw);
        assertEquals("a:\n  msg: >-\n    folded\ttext\n  after:\n    x: 1\n", r.cleanText());
        assertEquals(List.of(2, 3, 4, 5), r.fixedLines());
        assertTrue(YamlUpdater.isParseable(r.cleanText()));
    }

    @Test
    void blockScalarContentStartingWithSpacesKeepsLaterTabs() {
        String raw = "lore: |2-\n    \tkeep\nother: x\n";
        YamlPreprocessor.Result r = YamlPreprocessor.preprocess(raw);
        assertEquals(raw, r.cleanText());
        assertTrue(r.fixedLines().isEmpty());
    }

    @Test
    void allChompingAndIndentationIndicatorsOpenABlock() {
        for (String indicator : List.of("|", ">", "|-", "|+", ">-", ">+", "|2", "|2-", "|-2", ">+1", "|1+")) {
            String raw = "k: " + indicator + "\n  \ttab\n";
            assertEquals(raw, YamlPreprocessor.preprocess(raw).cleanText(), indicator);
        }
    }

    @Test
    void listItemBlockScalarIsDetected() {
        String raw = "items:\n- |\n  \tkeep\n- key: >\n    \tkeep too\n";
        assertEquals(raw, YamlPreprocessor.preprocess(raw).cleanText());
    }

    @Test
    void commentsAndQuotedPipesDoNotOpenABlock() {
        String raw = "a: x # not a block |\nb: '|'\n\tc: 1\n";
        YamlPreprocessor.Result r = YamlPreprocessor.preprocess(raw);
        assertEquals(List.of(3), r.fixedLines());
        assertEquals("a: x # not a block |\nb: '|'\n  c: 1\n", r.cleanText());
        // An indicator followed by a comment still opens the block.
        String withComment = "k: | # text below\n  \tkeep\n";
        assertEquals(withComment, YamlPreprocessor.preprocess(withComment).cleanText());
    }

    @Test
    void nullAndEmptyNeverThrow() {
        assertEquals("", YamlPreprocessor.preprocess(null).cleanText());
        assertEquals("", YamlPreprocessor.preprocess("").cleanText());
    }

    @Test
    void readDecodesUtf8DropsBomAndReplacesInvalidBytes() throws IOException {
        Path file = dir.resolve("a.yml");
        byte[] text = "título: ñandú\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 4];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(text, 0, bytes, 3, text.length);
        bytes[bytes.length - 1] = (byte) 0xFF;
        Files.write(file, bytes);
        String read = YamlPreprocessor.read(file);
        assertTrue(read.startsWith("título: ñandú\n"), read);
        assertTrue(read.endsWith("\uFFFD"), read);
    }
}
