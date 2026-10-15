package com.rs.lib.update.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link RsLibJars}: plugin.yml checks, finding the installed jar, installing and cleaning up. */
class RsLibJarsTest {

    @TempDir
    Path dir;

    private Path plugins() throws IOException {
        return Files.createDirectories(dir.resolve("plugins"));
    }

    private Path rsLibJar(Path file, String version) throws IOException {
        return TestJars.write(file, TestJars.jar(TestJars.rsLibYml(version), 100));
    }

    // ------------------------------------------------------------------ plugin.yml

    @Test
    void verifyAcceptsExactlyRsLibAtTheExpectedVersion() throws Exception {
        Path good = rsLibJar(dir.resolve("good.jar"), "1.2.0");
        RsLibJars.verify(good, "1.2.0");
        assertTrue(RsLibJars.isRsLib(good));

        UpdateRefused version = assertThrows(UpdateRefused.class, () -> RsLibJars.verify(good, "1.2"));
        assertTrue(version.getMessage().contains("'1.2.0'"), version.getMessage());

        Path name = TestJars.write(dir.resolve("name.jar"),
                TestJars.jar(TestJars.pluginYml("RsLibX", "com.rs.lib.RsLibPlugin", "1.2.0"), 10));
        assertThrows(UpdateRefused.class, () -> RsLibJars.verify(name, "1.2.0"));
        assertFalse(RsLibJars.isRsLib(name));

        Path main = TestJars.write(dir.resolve("main.jar"),
                TestJars.jar(TestJars.pluginYml("RsLib", "evil.Main", "1.2.0"), 10));
        UpdateRefused wrongMain = assertThrows(UpdateRefused.class, () -> RsLibJars.verify(main, "1.2.0"));
        assertTrue(wrongMain.getMessage().contains("main class"), wrongMain.getMessage());

        Path none = TestJars.write(dir.resolve("none.jar"), TestJars.jar(null, 10));
        assertThrows(UpdateRefused.class, () -> RsLibJars.verify(none, "1.2.0"));

        Path broken = TestJars.write(dir.resolve("broken.jar"), "not a zip".getBytes(StandardCharsets.UTF_8));
        UpdateRefused notJar = assertThrows(UpdateRefused.class, () -> RsLibJars.verify(broken, "1.2.0"));
        assertTrue(notJar.getMessage().contains("not a readable jar"), notJar.getMessage());
        assertFalse(RsLibJars.isRsLib(broken));
    }

    @Test
    void sha256OfAFile() throws Exception {
        Path file = Files.writeString(dir.resolve("abc.txt"), "abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", RsLibJars.sha256(file));
    }

    // ------------------------------------------------------------------ the installed jar

    @Test
    void codeSourceDirectlyInPluginsIsTheInstalledJar() throws Exception {
        Path jar = rsLibJar(plugins().resolve("RsLib-1.0.0.jar"), "1.0.0");
        assertEquals(jar, RsLibJars.resolveInstalled(plugins(), jar));
    }

    @Test
    void remappedCopyLeadsToTheJarOfTheSameName() throws Exception {
        Path installed = rsLibJar(plugins().resolve("RsLib-1.0.0.jar"), "1.0.0");
        Path remapped = rsLibJar(plugins().resolve(".paper-remapped/RsLib-1.0.0.jar"), "1.0.0");
        assertEquals(installed, RsLibJars.resolveInstalled(plugins(), remapped));
    }

    @Test
    void unknownCodeSourceFallsBackToTheOnlyRsLibJar() throws Exception {
        // Not "rslib.jar": on Windows that is the same file as the "RsLib.jar" written below.
        Path installed = rsLibJar(plugins().resolve("rslib-core.jar"), "1.0.0");
        TestJars.write(plugins().resolve("Other.jar"), TestJars.jar(TestJars.pluginYml("Other", "o.Main", "1"), 10));
        Files.writeString(plugins().resolve("notes.jar"), "not a jar");
        assertEquals(installed, RsLibJars.resolveInstalled(plugins(), dir.resolve("cache/x/RsLib.jar")));
        assertEquals(installed, RsLibJars.resolveInstalled(plugins(), null));
        // A file of the same name that is not RsLib does not count.
        TestJars.write(plugins().resolve("RsLib.jar"), TestJars.jar(TestJars.pluginYml("Other", "o.Main", "1"), 10));
        assertEquals(installed, RsLibJars.resolveInstalled(plugins(), dir.resolve("cache/x/RsLib.jar")));
    }

    @Test
    void zeroOrSeveralRsLibJarsAreRefused() throws Exception {
        UpdateRefused none = assertThrows(UpdateRefused.class, () -> RsLibJars.resolveInstalled(plugins(), null));
        assertTrue(none.getMessage().contains("not found"), none.getMessage());
        rsLibJar(plugins().resolve("RsLib-1.0.0.jar"), "1.0.0");
        rsLibJar(plugins().resolve("RsLib-0.9.0.jar"), "0.9.0");
        UpdateRefused two = assertThrows(UpdateRefused.class, () -> RsLibJars.resolveInstalled(plugins(), null));
        assertTrue(two.getMessage().contains("2 RsLib jars"), two.getMessage());
    }

    // ------------------------------------------------------------------ installing

    /** The disk, except that deleting {@code locked} fails like a jar held open on Windows. */
    private static RsLibJars.FileOps lockedOn(Path locked, boolean moveFails) {
        return new RsLibJars.FileOps() {
            @Override
            public void move(Path from, Path to) throws IOException {
                if (moveFails && to.getParent().equals(locked.getParent())) {
                    throw new IOException("access denied");
                }
                RsLibJars.DISK.move(from, to);
            }

            @Override
            public void delete(Path file) throws IOException {
                if (file.equals(locked)) {
                    throw new IOException("the file is in use");
                }
                RsLibJars.DISK.delete(file);
            }
        };
    }

    private Path staged(String version) throws IOException {
        return rsLibJar(dir.resolve("staging/RsLib-" + version + ".jar.part"), version);
    }

    @Test
    void versionedNameIsReplacedByTheNewVersionedName() throws Exception {
        Path old = rsLibJar(plugins().resolve("RsLib-1.0.0.jar"), "1.0.0");
        Path part = staged("1.1.0");
        byte[] fresh = Files.readAllBytes(part);
        RsLibJars.Installed done = RsLibJars.install(part, old, "1.0.0", "1.1.0", dir.resolve("plugins/update"),
                RsLibJars.DISK);
        assertFalse(done.deferred());
        assertEquals(plugins().resolve("RsLib-1.1.0.jar"), done.jar());
        assertArrayEquals(fresh, Files.readAllBytes(done.jar()));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(part));
        assertFalse(Files.exists(dir.resolve("plugins/update")));
    }

    @Test
    void unversionedNameIsKept() throws Exception {
        Path old = rsLibJar(plugins().resolve("RsLib.jar"), "1.0.0");
        Path part = staged("1.1.0");
        RsLibJars.Installed done = RsLibJars.install(part, old, "1.0.0", "1.1.0", dir.resolve("plugins/update"),
                RsLibJars.DISK);
        assertEquals(old, done.jar());
        assertFalse(done.deferred());
        RsLibJars.verify(old, "1.1.0");
    }

    @Test
    void lockedOldJarUndoesTheMoveAndUsesTheUpdateFolder() throws Exception {
        Path old = rsLibJar(plugins().resolve("RsLib-1.0.0.jar"), "1.0.0");
        Path part = staged("1.1.0");
        Path updateFolder = dir.resolve("plugins/update");
        RsLibJars.Installed done = RsLibJars.install(part, old, "1.0.0", "1.1.0", updateFolder, lockedOn(old, false));
        assertTrue(done.deferred());
        // Bukkit swaps files of the same name at the next start.
        assertEquals(updateFolder.resolve("RsLib-1.0.0.jar"), done.jar());
        RsLibJars.verify(done.jar(), "1.1.0");
        RsLibJars.verify(old, "1.0.0");
        assertFalse(Files.exists(plugins().resolve("RsLib-1.1.0.jar")), "two RsLib jars side by side");
    }

    @Test
    void lockedSameNameGoesToTheUpdateFolder() throws Exception {
        Path old = rsLibJar(plugins().resolve("RsLib.jar"), "1.0.0");
        Path part = staged("1.1.0");
        Path updateFolder = dir.resolve("update");
        RsLibJars.Installed done = RsLibJars.install(part, old, "1.0.0", "1.1.0", updateFolder, lockedOn(old, true));
        assertTrue(done.deferred());
        assertEquals(updateFolder.resolve("RsLib.jar"), done.jar());
        RsLibJars.verify(old, "1.0.0");
    }

    @Test
    void refusedMoveGoesToTheUpdateFolder() throws Exception {
        Path old = rsLibJar(plugins().resolve("RsLib-1.0.0.jar"), "1.0.0");
        Path part = staged("1.1.0");
        Path updateFolder = dir.resolve("update");
        RsLibJars.Installed done = RsLibJars.install(part, old, "1.0.0", "1.1.0", updateFolder, lockedOn(old, true));
        assertTrue(done.deferred());
        assertEquals(updateFolder.resolve("RsLib-1.0.0.jar"), done.jar());
        assertTrue(Files.exists(old));
    }

    @Test
    void cleanStagingRemovesLeftoversAndTheEmptyFolder() throws Exception {
        Path staging = Files.createDirectories(dir.resolve(".rslib-update"));
        Files.writeString(staging.resolve("RsLib-1.1.0.jar.part"), "x");
        Files.writeString(staging.resolve("keep.txt"), "x");
        RsLibJars.cleanStaging(staging);
        assertFalse(Files.exists(staging.resolve("RsLib-1.1.0.jar.part")));
        assertTrue(Files.exists(staging.resolve("keep.txt")));
        Files.delete(staging.resolve("keep.txt"));
        Files.writeString(staging.resolve("RsLib-1.2.0.jar.part"), "x");
        RsLibJars.cleanStaging(staging);
        assertFalse(Files.exists(staging));
        RsLibJars.cleanStaging(staging);
    }
}
