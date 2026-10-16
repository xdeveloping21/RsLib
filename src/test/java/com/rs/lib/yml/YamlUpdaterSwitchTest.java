package com.rs.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.yml.YmlTestSupport.Captured;

/** {@code update-configs: false} in RsLib's config: files are reported, never written, except RsLib's own. */
class YamlUpdaterSwitchTest {

    private static final String JAR = "a: 1\nnew: 2\nsection:\n  x: 1\n  y: 2\n";
    private static final String DISK = "a: 5\nsection:\n  x: 9\n";

    @TempDir
    Path dir;

    private final Captured log = new Captured();

    @AfterEach
    void restore() {
        YamlUpdater.setMergeEnabled(true, null);
    }

    private static Function<String, InputStream> jar() {
        return path -> new ByteArrayInputStream(JAR.getBytes(StandardCharsets.UTF_8));
    }

    private boolean update(Path file) {
        return YamlUpdater.updateResource(log.logger, jar(), "config.yml", file.toFile(), false, false);
    }

    @Test
    void offReportsTheMissingKeysAndLeavesTheFileAlone() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, DISK);
        YamlUpdater.setMergeEnabled(false, dir.resolve("RsLib/config.yml").toFile());
        assertFalse(YamlUpdater.mergeEnabled());
        assertFalse(update(file));
        assertEquals(DISK, Files.readString(file));
        List<String> warnings = log.records.stream().filter(r -> r.getLevel() == Level.WARNING)
                .map(r -> r.getMessage()).toList();
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals("[update-configs] update-configs is false: 2 keys missing in config.yml", warnings.get(0));
    }

    @Test
    void offStillCreatesMissingFilesAndMergesRsLibsOwnConfig() throws IOException {
        Path own = dir.resolve("RsLib/config.yml");
        YamlUpdater.setMergeEnabled(false, own.toFile());
        Path fresh = dir.resolve("fresh.yml");
        assertTrue(update(fresh));
        assertEquals(JAR, Files.readString(fresh));
        Files.createDirectories(own.getParent());
        Files.writeString(own, DISK);
        assertTrue(update(own));
        assertTrue(Files.readString(own).contains("new: 2"));
    }

    @Test
    void onMergesAsAlways() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, DISK);
        assertTrue(YamlUpdater.mergeEnabled());
        assertTrue(update(file));
        assertTrue(Files.readString(file).contains("  y: 2"));
    }

    @Test
    void forcedLinesMergeEvenWhenOff() throws IOException {
        Path file = dir.resolve("lang/messages_en.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "a: mine\n");
        YamlUpdater.setMergeEnabled(false, dir.resolve("RsLib/config.yml").toFile());
        assertFalse(YamlUpdater.updateFromLines(log.logger, List.of("rslib:", "  b: shared"), file.toFile()));
        assertEquals("a: mine\n", Files.readString(file));
        assertTrue(YamlUpdater.updateFromLines(log.logger, List.of("rslib:", "  b: shared"), file.toFile(), true));
        assertEquals("a: mine\nrslib:\n  b: shared\n", Files.readString(file));
        assertFalse(YamlUpdater.updateFromLines(log.logger, List.of("rslib:", "  b: other"), file.toFile(), true));
        assertTrue(Files.readString(file).contains("b: shared"));
    }

    @Test
    void missingKeysCountsNestedOnes() {
        assertEquals(0, YamlUpdater.missingKeys(List.of("a: 1"), List.of("a: 3")));
        assertEquals(3, YamlUpdater.missingKeys(List.of("a: 1", "b:", "  c: 1", "  d: 2"), List.of("a: 1")));
    }
}
