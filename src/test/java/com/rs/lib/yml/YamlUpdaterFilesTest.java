package com.rs.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.yml.YmlTestSupport.Captured;
import com.rs.lib.yml.internal.MergeBackups;
import com.rs.lib.yml.internal.YamlText;

/** {@link YamlUpdater} against real files in a temporary folder. */
class YamlUpdaterFilesTest {

    @TempDir
    Path dir;

    private final Captured log = new Captured();

    private static Function<String, InputStream> jar(Map<String, String> files) {
        return path -> files.containsKey(path)
                ? new ByteArrayInputStream(files.get(path).getBytes(StandardCharsets.UTF_8)) : null;
    }

    private String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private List<String> backups() {
        String[] names = dir.toFile().list((d, n) -> n.startsWith("old-"));
        Arrays.sort(names);
        return List.of(names);
    }

    private boolean update(String resource, File disk, boolean prune) {
        return YamlUpdater.updateResource(log.logger, jar(Map.of("config.yml", resource)), "config.yml",
                disk, prune, false);
    }

    @Test
    void missingFileIsSeededAsWritten() throws IOException {
        Path file = dir.resolve("sub/config.yml");
        assertTrue(update("# hi\r\na: 1\r\n", file.toFile(), false));
        assertEquals("# hi\na: 1\n", read(file));
        assertTrue(backups().isEmpty());
    }

    @Test
    void mergeWritesBackupAndUsesLf() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 5\r\n");
        assertTrue(update("a: 1\nb: 2\n", file.toFile(), false));
        assertEquals("a: 5\nb: 2\n", read(file));
        List<String> backups = backups();
        assertEquals(1, backups.size());
        assertTrue(MergeBackups.isBackupOf("config.yml", backups.get(0)), backups.get(0));
        assertEquals("a: 5\r\n", read(dir.resolve(backups.get(0))));
        // Nothing left to do: no write, no new backup, no temporary files.
        assertFalse(update("a: 1\nb: 2\n", file.toFile(), false));
        assertEquals(1, backups().size());
        assertEquals(2, dir.toFile().list().length);
    }

    @Test
    void unchangedCrlfFileIsNotRewritten() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 5\r\nb: 6\r\n");
        assertFalse(update("a: 1\nb: 2\n", file.toFile(), false));
        assertEquals("a: 5\r\nb: 6\r\n", read(file));
    }

    @Test
    void onlyThreeNewestBackupsOfThatExactNameAreKept() throws IOException {
        Path file = dir.resolve("config.yml");
        for (String stamp : List.of("20200101-000000", "20200102-000000", "20200103-000000")) {
            Files.writeString(dir.resolve("old-config-" + stamp + ".yml"), "x");
        }
        Files.writeString(dir.resolve("old-config-extra-20190101-000000.yml"), "x");
        Files.writeString(file, "a: 5\n");
        assertTrue(update("a: 1\nb: 2\n", file.toFile(), false));
        List<String> backups = backups();
        assertEquals(4, backups.size(), backups.toString());
        assertTrue(backups.contains("old-config-extra-20190101-000000.yml"));
        assertFalse(backups.contains("old-config-20200101-000000.yml"));
        assertTrue(backups.contains("old-config-20200102-000000.yml"));
        assertTrue(backups.contains("old-config-20200103-000000.yml"));
    }

    @Test
    void corruptFileIsMovedAsideAndSeededAgain() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: [unclosed\n");
        assertTrue(update("a: 1\n", file.toFile(), false));
        assertEquals("a: 1\n", read(file));
        assertEquals("a: [unclosed\n", read(dir.resolve("config.yml.backup-1")));
        assertTrue(log.warnings().stream().anyMatch(m -> m.startsWith("[config-updater]")
                && m.contains("config.yml.backup-1")), log.warnings().toString());
        Files.writeString(file, "b: {\n");
        update("a: 1\n", file.toFile(), false);
        assertEquals("b: {\n", read(dir.resolve("config.yml.backup-2")));
    }

    @Test
    void tabIndentedFileIsRepairedThenMerged() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "s:\n\ta: 5\n");
        assertTrue(update("s:\n  a: 1\n  b: 2\n", file.toFile(), false));
        assertEquals("s:\n  a: 5\n  b: 2\n", read(file));
        assertTrue(log.warnings().stream().anyMatch(m -> m.contains("Indentation tabs fixed") && m.contains("[2]")),
                log.warnings().toString());
    }

    @Test
    void pruneOnlyWhenAsked() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 1\nold: 2\n");
        assertFalse(update("a: 1\n", file.toFile(), false));
        assertEquals("a: 1\nold: 2\n", read(file));
        assertTrue(update("a: 1\n", file.toFile(), true));
        assertEquals("a: 1\n", read(file));
    }

    @Test
    void missingResourceOnlyWarns() {
        Path file = dir.resolve("config.yml");
        assertFalse(YamlUpdater.updateResource(log.logger, p -> null, "config.yml", file.toFile(), false, false));
        assertFalse(Files.exists(file));
        assertEquals(List.of("[config-updater] Resource config.yml absent from the jar"), log.warnings());
    }

    @Test
    void ioErrorsAreSevereAndNeverThrow() throws IOException {
        Path folder = dir.resolve("config.yml");
        Files.createDirectories(folder);
        assertFalse(update("a: 1\n", folder.toFile(), false));
        List<String> severe = log.messages(Level.SEVERE);
        assertEquals(1, severe.size());
        assertTrue(severe.get(0).startsWith("[config-updater]"), severe.get(0));
    }

    @Test
    void uselessMarkersAreWarnedOnceAcrossJarAndDisk() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "# rs:extensible\nname: x\n# sn:extensible\nother: y\n");
        update("# rs:extensible\nname: a\nother: b\n", file.toFile(), false);
        List<String> warnings = log.warnings();
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("(jar copy)") && warnings.get(0).contains("'name'"));
        assertTrue(warnings.get(1).contains("'other'"));
    }

    @Test
    void updateFromLinesSeedsMergesAndAcceptsTheLegacyFourthArgument() throws IOException {
        Path file = dir.resolve("lang/messages_es.yml");
        assertTrue(YamlUpdater.updateFromLines(log.logger, List.of("a: Hola"), file.toFile()));
        assertEquals("a: Hola\n", read(file));
        @SuppressWarnings("deprecation")
        boolean changed = YamlUpdater.updateFromLines(log.logger, List.of("a: Hello", "b: Bye"), file.toFile(),
                (File) null);
        assertTrue(changed);
        assertEquals("a: Hola\nb: Bye\n", read(file));
        @SuppressWarnings("deprecation")
        boolean again = YamlUpdater.updateFromLines(log.logger, List.of("a: Hello", "b: Bye"), file.toFile(),
                (File) null);
        assertFalse(again);
    }

    @Test
    void obsoleteKeyIsDroppedOnlyForTheMainConfig() throws IOException {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "a: 1\n# old\nupdate-configs: true\n");
        assertFalse(update("a: 1\n", file.toFile(), false));
        assertTrue(YamlUpdater.updateResource(log.logger, jar(Map.of("config.yml", "a: 1\n")), "config.yml",
                file.toFile(), false, true));
        assertEquals("a: 1\n", read(file));
    }

    @Test
    void writesNeverLeaveTemporaryFiles() throws IOException {
        Path file = dir.resolve("x.yml");
        YamlText.write(file, "a: 1\n");
        YamlText.write(file, "a: 2\n");
        assertEquals("a: 2\n", read(file));
        assertEquals(List.of("x.yml"), List.of(dir.toFile().list()));
    }
}
