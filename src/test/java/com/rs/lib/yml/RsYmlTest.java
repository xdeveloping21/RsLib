package com.rs.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.Ph;
import com.rs.lib.text.RsText;
import com.rs.lib.yml.YmlTestSupport.FakeHost;

class RsYmlTest {

    enum Toggle { ON, OFF, AUTO }

    enum Answer { YES, NO }

    enum Bool { TRUE, FALSE }

    enum Mode { STOP, RESTART }

    enum Both { NO, OFF, ON }

    @TempDir
    Path dir;

    private final FakeHost host = new FakeHost();

    private RsYml open(String text) throws IOException {
        Path file = dir.resolve("test.yml");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return YmlTestSupport.open(file.toFile(), host);
    }

    // ------------------------------------------------------------------ getters

    @Test
    void absentKeyIsSilentNullValueWarns() throws IOException {
        RsYml yml = open("a: ~\nb:\nsec:\n  c: ~\n");
        assertEquals("def", yml.getString("missing", "def"));
        assertEquals(7, yml.getInt("missing.deep", 7));
        assertTrue(host.log.warnings().isEmpty());
        assertEquals("def", yml.getString("a", "def"));
        assertEquals(3, yml.getInt("b", 3));
        assertTrue(yml.getBoolean("sec.c", true));
        assertEquals(3, host.log.warnings().size(), host.log.warnings().toString());
        assertFalse(yml.isSet("a"));
    }

    @Test
    void wrongTypeWarnsOncePerKeyAndValueUntilNextRead() throws IOException {
        RsYml yml = open("rows: six\n");
        for (int i = 0; i < 5; i++) {
            assertEquals(3, yml.getInt("rows", 3));
        }
        assertEquals(List.of("Invalid value in test.yml -> 'rows': received 'six', using default '3'"),
                host.log.warnings());
        yml.reload();
        assertEquals(3, yml.getInt("rows", 3));
        assertEquals(2, host.log.warnings().size());
    }

    @Test
    void getStringReturnsConvertedNonStringsWithAWarning() throws IOException {
        RsYml yml = open("n: 5\nb: true\nd: 1.5\ns: hello\nsec:\n  x: 1\n");
        assertEquals("5", yml.getString("n", "def"));
        assertEquals("true", yml.getString("b", "def"));
        assertEquals("1.5", yml.getString("d", "def"));
        assertEquals("hello", yml.getString("s", "def"));
        assertEquals("def", yml.getString("sec", "def"));
        assertEquals(4, host.log.warnings().size(), host.log.warnings().toString());
    }

    @Test
    void numberGettersParseText() throws IOException {
        RsYml yml = open("i: '42'\nl: ' 9000000000 '\nd: '2.5'\nbad: 'x1'\nnum: 7\nbool: true\n");
        assertEquals(42, yml.getInt("i", 0));
        assertEquals(9_000_000_000L, yml.getLong("l", 0));
        assertEquals(2.5, yml.getDouble("d", 0), 1e-9);
        assertEquals(7, yml.getLong("num", 0));
        assertEquals(7.0, yml.getDouble("num", 0), 1e-9);
        assertEquals(-1, yml.getInt("bad", -1));
        assertEquals(-1, yml.getInt("bool", -1));
        assertEquals(2, host.log.warnings().size());
    }

    @Test
    void booleanAcceptsOnlyTrueFalseText() throws IOException {
        RsYml yml = open("a: true\nb: 'FALSE'\nc: 'yes'\nd: 1\n");
        assertTrue(yml.getBoolean("a", false));
        assertFalse(yml.getBoolean("b", true));
        assertTrue(yml.getBoolean("c", true));
        assertFalse(yml.getBoolean("d", false));
        assertEquals(2, host.log.warnings().size());
    }

    @Test
    void enumReadsRawValueIgnoringCaseAndMapsYamlBooleans() throws IOException {
        RsYml yml = open("a: off\nb: ON\nc: 'auto'\nd: no\ne: yes\nf: false\ng: true\nh: off\ni: Restart\nj: 3\n"
                + "k: '{x}'\n");
        assertEquals(Toggle.OFF, yml.getEnum("a", Toggle.class, Toggle.AUTO));
        assertEquals(Toggle.ON, yml.getEnum("b", Toggle.class, Toggle.AUTO));
        assertEquals(Toggle.AUTO, yml.getEnum("c", Toggle.class, Toggle.ON));
        assertEquals(Answer.NO, yml.getEnum("d", Answer.class, Answer.YES));
        assertEquals(Answer.YES, yml.getEnum("e", Answer.class, Answer.NO));
        assertEquals(Bool.FALSE, yml.getEnum("f", Bool.class, Bool.TRUE));
        assertEquals(Bool.TRUE, yml.getEnum("g", Bool.class, Bool.FALSE));
        // false -> first of OFF, NO, FALSE the enum declares (OFF before NO).
        assertEquals(Both.OFF, yml.getEnum("h", Both.class, Both.ON));
        assertEquals(Mode.RESTART, yml.getEnum("i", Mode.class, Mode.STOP));
        assertTrue(host.log.warnings().isEmpty());
        assertEquals(Mode.STOP, yml.getEnum("a", Mode.class, Mode.STOP));
        assertTrue(host.log.warnings().get(0).contains("never as a name"), host.log.warnings().get(0));
        assertEquals(Mode.STOP, yml.getEnum("j", Mode.class, Mode.STOP));
        assertEquals(Mode.STOP, yml.getEnum("k", Mode.class, Mode.STOP));
        assertEquals(3, host.log.warnings().size());
        assertNull(yml.getEnum("missing", Mode.class, null));
    }

    @Test
    void stringListResolvesElementsAndTurnsNullsIntoEmptyText() throws IOException {
        RsYml yml = open("l:\n- a\n- ~\n- 3\nnot: text\n");
        assertEquals(List.of("a", "", "3"), yml.getStringList("l", List.of()));
        assertEquals(List.of("d"), yml.getStringList("not", List.of("d")));
        assertEquals(List.of("d"), yml.getStringList("missing", List.of("d")));
        assertEquals(1, host.log.warnings().size());
    }

    @Test
    void sectionIsRawAndIsSetSeesZeroAndFalse() throws IOException {
        RsYml yml = open("sec:\n  a: '%server_name%'\nzero: 0\nflag: false\nempty: ''\n");
        assertNotNull(yml.getSection("sec"));
        assertEquals("%server_name%", yml.getSection("sec").getString("a"));
        assertNull(yml.getSection("zero"));
        assertTrue(yml.isSet("zero"));
        assertTrue(yml.isSet("flag"));
        assertTrue(yml.isSet("empty"));
        assertFalse(yml.isSet("missing"));
    }

    @Test
    void placeholderApiOnlyOnTheMainThread() throws IOException {
        RsYml yml = open("a: 'Server %server_name%'\nl:\n- '%server_name%'\n");
        assertEquals("Server %server_name%", yml.getString("a", ""));
        assertEquals(1, host.traces.size());
        host.primary = true;
        assertEquals("Server Lobby", yml.getString("a", ""));
        assertEquals(List.of("Lobby"), yml.getStringList("l", List.of()));
        // Defaults are returned as given.
        assertEquals("%server_name%", yml.getString("missing", "%server_name%"));
    }

    @Test
    void localsAndPhResolveBeforePlaceholderApi() throws IOException {
        Assumptions.assumeTrue(rsTextReady(), "RsText.applyLocals is not implemented yet");
        RsYml yml = open("a: '{who} on %server_name% ({n})'\nnum: '{amount}'\n");
        yml.placeholder("who", () -> "Raul");
        host.primary = true;
        assertEquals("Raul on Lobby (3)", yml.getString("a", "", null, Ph.of("n", 3)));
        yml.placeholder("amount", () -> "12");
        assertEquals(12, yml.getInt("num", 0));
    }

    private static boolean rsTextReady() {
        try {
            RsText.applyLocals("{a}", Ph.of("a", 1));
            return true;
        } catch (UnsupportedOperationException stub) {
            return false;
        }
    }

    // ------------------------------------------------------------------ reading

    @Test
    void unparseableFileKeepsPreviousContent() throws IOException {
        RsYml yml = open("a: 1\n");
        Files.writeString(yml.file().toPath(), "a: [broken\n");
        yml.reload();
        assertEquals(1, yml.getInt("a", 0));
        assertTrue(host.log.warnings().get(0).startsWith("Could not parse test.yml; keeping the previous content"),
                host.log.warnings().get(0));
    }

    @Test
    void tabsAreRepairedWithOneWarningAndBomIsIgnored() throws IOException {
        Path file = dir.resolve("tabs.yml");
        Files.write(file, ("\uFEFFs:\n\ta: 1\n\tb: 2\n").getBytes(StandardCharsets.UTF_8));
        RsYml yml = YmlTestSupport.open(file.toFile(), host);
        assertEquals(1, yml.getInt("s.a", 0));
        assertEquals(List.of("Indentation tabs fixed in tabs.yml (lines [2, 3])"), host.log.warnings());
    }

    @Test
    void missingFileReadsEmpty() {
        RsYml yml = YmlTestSupport.open(dir.resolve("none.yml").toFile(), host);
        assertEquals("d", yml.getString("a", "d"));
        assertFalse(Files.exists(dir.resolve("none.yml")));
    }

    @Test
    void reloadRunsHooksInOrderAndSurvivesFailures() throws IOException {
        RsYml yml = open("a: 1\n");
        List<String> calls = new ArrayList<>();
        yml.onReload(() -> calls.add("first"));
        yml.onReload(() -> {
            throw new IllegalStateException("boom");
        });
        yml.onReload(() -> calls.add("third:" + yml.getInt("a", 0)));
        Files.writeString(yml.file().toPath(), "a: 2\n");
        yml.reload();
        assertEquals(List.of("first", "third:2"), calls);
    }

    // ------------------------------------------------------------------ writing

    @Test
    void saveIsAsyncAndCoalesced() throws IOException {
        RsYml yml = open("a: 1\n");
        yml.set("a", 2);
        yml.save();
        yml.set("a", 3);
        yml.save();
        assertEquals(1, host.tasks.size());
        assertEquals("a: 1\n", Files.readString(yml.file().toPath()));
        host.runTasks();
        assertEquals("a: 3\n", Files.readString(yml.file().toPath()));
        // A later save schedules a new write.
        yml.set("a", 4);
        yml.save();
        assertEquals(1, host.runTasks());
        assertEquals("a: 4\n", Files.readString(yml.file().toPath()));
        assertEquals(List.of("test.yml"), List.of(dir.toFile().list()));
    }

    @Test
    void shutdownWritesInlineAndOlderSnapshotNeverWins() throws IOException {
        RsYml yml = open("a: 1\n");
        yml.set("a", 2);
        yml.save();
        host.shuttingDown = true;
        yml.set("a", 3);
        yml.save();
        assertEquals("a: 3\n", Files.readString(yml.file().toPath()));
        host.runTasks();
        assertEquals("a: 3\n", Files.readString(yml.file().toPath()));
    }

    @Test
    void flushWritesPendingInlineWithoutRunningTheTask() throws IOException {
        RsYml yml = open("a: 1\n");
        yml.set("b.c", "x");
        yml.setComments("b", List.of("note"));
        yml.save();
        yml.flush();
        String text = Files.readString(yml.file().toPath());
        assertEquals("a: 1\n# note\nb:\n  c: x\n", text);
        assertFalse(text.contains("\r"));
        assertFalse(yml.hasPendingWrite());
        host.runTasks();
        assertEquals(text, Files.readString(yml.file().toPath()));
    }

    @Test
    void unschedulableSaveIsWrittenInline() throws IOException {
        FakeHost failing = new FakeHost();
        failing.refuseAsync = true;
        Path file = dir.resolve("f.yml");
        RsYml yml = YmlTestSupport.open(file.toFile(), failing);
        yml.set("x", 1);
        yml.save();
        assertEquals("x: 1\n", Files.readString(file));
    }

    @Test
    void reloadWritesPendingSaveFirst() throws IOException {
        RsYml yml = open("a: 1\n");
        yml.set("a", 9);
        yml.save();
        yml.reload();
        assertEquals(9, yml.getInt("a", 0));
        assertEquals("a: 9\n", Files.readString(yml.file().toPath()));
    }
}
