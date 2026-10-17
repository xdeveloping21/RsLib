package com.rs.lib.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsvConfigTest {

    private static final String JAR = """
            # Proxy plugin settings

            # How often to announce, in seconds.
            interval: 60
            messages:
              # Shown on join.
              join: '&aWelcome'
              leave: '&cBye'
            servers:
            - lobby
            - survival
            """;

    @TempDir
    Path dir;

    private final VelocityFakes fakes = new VelocityFakes();

    private static InputStream text(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private RsvConfig load(Path file, String defaults) {
        return RsvConfig.load(file, defaults == null ? null : text(defaults), fakes.logger);
    }

    // ---------------------------------------------------------------- seeding and merging

    @Test
    void aMissingFileIsCreatedFromTheJarCopyWithItsComments() throws IOException {
        Path file = dir.resolve("sub/config.yml");
        RsvConfig config = load(file, JAR);
        assertEquals(JAR, Files.readString(file));
        assertEquals(60, config.getInt("interval", 0));
        assertEquals("&aWelcome", config.getString("messages.join", null));
        assertEquals(List.of("lobby", "survival"), config.getStringList("servers"));
    }

    /** Trampa 14.6: rewriting the whole file would lose the owner's comments. */
    @Test
    void onlyMissingKeysAreAddedAndTheOwnersCommentsAndValuesStay() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                # my own notes
                interval: 30   # faster
                messages:
                  join: "&bHello"   # changed
                  extra: kept
                custom: 1
                """);
        RsvConfig config = load(file, JAR);
        assertEquals("""
                # my own notes
                interval: 30   # faster
                messages:
                  join: "&bHello"   # changed
                  leave: '&cBye'
                  extra: kept
                servers:
                - lobby
                - survival
                custom: 1
                """, Files.readString(file));
        assertEquals(30, config.getInt("interval", 0));
        assertEquals("&bHello", config.getString("messages.join", null));
        assertEquals("&cBye", config.getString("messages.leave", null));
        assertEquals("kept", config.getString("messages.extra", null));
        assertEquals(1, config.getInt("custom", 0));
        assertEquals(List.of("lobby", "survival"), config.getStringList("servers"));
        assertTrue(fakes.logs.isEmpty(), fakes.logs.toString());
    }

    @Test
    void aMissingSectionComesBackWithItsCommentsAndTheFilesIndentation() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, """
                interval: 5
                servers:
                - hub
                """);
        load(file, JAR);
        assertEquals("""
                interval: 5
                messages:
                  # Shown on join.
                  join: '&aWelcome'
                  leave: '&cBye'
                servers:
                - hub
                """, Files.readString(file));

        Path wide = dir.resolve("wide.yml");
        Files.writeString(wide, """
                messages:
                    join: hi
                """);
        RsvConfig config = load(wide, JAR);
        assertTrue(Files.readString(wide).contains("\n    leave: '&cBye'\n"), Files.readString(wide));
        assertEquals("&cBye", config.getString("messages.leave", null));
    }

    @Test
    void aFileThatLacksNothingIsNeverWritten() throws IOException {
        Path file = dir.resolve("config.yml");
        String owner = "interval: 1\r\nmessages:\r\n  join: a\r\n  leave: b\r\nservers: []\r\nmine: true\r\n";
        Files.writeString(file, owner);
        FileTime before = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, before);
        RsvConfig config = load(file, JAR);
        assertEquals(owner, Files.readString(file), "line endings and bytes untouched");
        assertEquals(before, Files.getLastModifiedTime(file));
        assertTrue(config.getBoolean("mine", false));
        assertEquals(List.of(), config.getStringList("servers"));
    }

    @Test
    void anEmptyFileGetsEveryKey() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "");
        RsvConfig config = load(file, JAR);
        assertEquals(60, config.getInt("interval", 0));
        assertEquals(60, load(file, JAR).getInt("interval", 0));
        assertTrue(Files.readString(file).contains("# Shown on join."));
    }

    @Test
    void anEmptySectionAndAnExtensibleSectionAreRespected() throws IOException {
        Path file = dir.resolve("config.yml");
        String owner = """
                interval: 1
                # rs:extensible
                messages:
                  join: x
                servers: {}
                """;
        Files.writeString(file, owner);
        RsvConfig config = load(file, JAR);
        assertEquals(owner, Files.readString(file));
        assertEquals(Map.of("join", "x"), config.getSection("messages"));
        assertEquals(Map.of(), config.getSection("servers"));
        assertEquals(List.of(), config.getStringList("servers"));
    }

    // ---------------------------------------------------------------- never throws

    @Test
    void aBrokenFileIsLeftAloneAndTheJarValuesAreUsed() throws IOException {
        Path file = dir.resolve("config.yml");
        String broken = "interval: 5\nmessages:\n  join: [unclosed\n";
        Files.writeString(file, broken);
        RsvConfig config = load(file, JAR);
        assertEquals(broken, Files.readString(file));
        assertEquals(60, config.getInt("interval", 0));
        assertEquals(1, fakes.logged("error").size(), fakes.logs.toString());
        assertTrue(fakes.logged("error").get(0).contains("not valid YAML"));
    }

    @Test
    void aFileWhoseTopLevelIsNotAMapCountsAsBroken() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "- a\n- b\n");
        RsvConfig config = load(file, JAR);
        assertEquals("- a\n- b\n", Files.readString(file));
        assertEquals(60, config.getInt("interval", 0));
    }

    @Test
    void anUnreadableFileIsLoggedAndTheJarValuesAreUsed() throws IOException {
        Path folder = dir.resolve("config.yml");
        Files.createDirectories(folder);
        RsvConfig config = load(folder, JAR);
        assertEquals(60, config.getInt("interval", 0));
        assertEquals(1, fakes.logged("error").size(), fakes.logs.toString());
    }

    @Test
    void aBrokenJarCopyIsIgnoredAndTheFileIsOnlyRead() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 1\n");
        RsvConfig config = load(file, "a: [\n");
        assertEquals("a: 1\n", Files.readString(file));
        assertEquals(1, config.getInt("a", 0));
        assertEquals(1, fakes.logged("error").size());

        Path missing = dir.resolve("missing.yml");
        assertTrue(load(missing, "b: [\n").keys().isEmpty());
        assertFalse(Files.exists(missing));
    }

    @Test
    void withoutAJarCopyAMissingFileIsNotCreated() throws IOException {
        Path file = dir.resolve("config.yml");
        RsvConfig config = load(file, null);
        assertFalse(Files.exists(file));
        assertTrue(config.keys().isEmpty());
        Files.writeString(file, "x: 2\n");
        assertEquals(2, load(file, null).getInt("x", 0));
        assertEquals("x: 2\n", Files.readString(file));
    }

    @Test
    void theDefaultsStreamIsClosed() {
        boolean[] closed = new boolean[1];
        InputStream in = new ByteArrayInputStream("a: 1\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        RsvConfig.load(dir.resolve("c.yml"), in, fakes.logger);
        assertTrue(closed[0]);
    }

    @Test
    void anInsertionThatWouldChangeTheOwnersValuesIsNotWritten() {
        Map<String, Object> before = RsvConfig.parse("a:\n  b: 1\nc: [1, 2]\n");
        assertTrue(RsvConfig.keeps(before, RsvConfig.parse("a:\n  b: 1\n  z: 0\nc: [1, 2]\nd: 3\n")));
        assertFalse(RsvConfig.keeps(before, RsvConfig.parse("a:\n  b: 2\nc: [1, 2]\n")));
        assertFalse(RsvConfig.keeps(before, RsvConfig.parse("a: 1\nc: [1, 2]\n")));
        assertFalse(RsvConfig.keeps(before, RsvConfig.parse("a:\n  b: 1\n")));
    }

    // ---------------------------------------------------------------- getters

    @Test
    void gettersFollowDottedPathsAndAWrongTypeGivesTheDefault() {
        RsvConfig config = RsvConfig.of(RsvConfig.parse("""
                db:
                  port: 3306
                  host: localhost
                  ratio: 0.5
                  big: 9000000000
                  enabled: true
                  name: ~
                list: [a, 1, true, {x: 1}, [2]]
                1: numeric key
                text: hello
                """));
        assertEquals(3306, config.getInt("db.port", 0));
        assertEquals(3306L, config.getLong("db.port", 0));
        assertEquals(3306.0, config.getDouble("db.port", 0));
        assertEquals(9000000000L, config.getLong("db.big", 0));
        assertEquals(0.5, config.getDouble("db.ratio", 0));
        assertEquals("3306", config.getString("db.port", null));
        assertEquals("true", config.getString("db.enabled", null));
        assertTrue(config.getBoolean("db.enabled", false));
        assertEquals(7, config.getInt("db.host", 7));
        assertEquals("def", config.getString("db", "def"));
        assertEquals("def", config.getString("list", "def"));
        assertEquals("def", config.getString("db.name", "def"));
        assertFalse(config.getBoolean("text", false));
        assertEquals(4, config.getInt("db.port.deeper", 4));
        assertEquals(4, config.getInt("nothing.here", 4));
        assertEquals("numeric key", config.getString("1", null));
        assertEquals(List.of("a", "1", "true"), config.getStringList("list"));
        assertEquals(List.of(), config.getStringList("text"));
        assertTrue(config.contains("db.name"));
        assertTrue(config.contains("db"));
        assertFalse(config.contains("db.nope"));
        assertFalse(config.contains(null));
        assertEquals(List.of("db", "list", "1", "text"), List.copyOf(config.keys()));
        Map<String, Object> db = config.getSection("db");
        assertEquals(List.of("port", "host", "ratio", "big", "enabled", "name"), List.copyOf(db.keySet()));
        assertEquals(Map.of(), config.getSection("text"));
        assertThrows(UnsupportedOperationException.class, () -> db.put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> config.keys().clear());
        List<String> copy = config.getStringList("list");
        copy.add("mutable copy");
    }
}
