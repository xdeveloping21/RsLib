package com.rs.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.yml.YmlTestSupport.FakeHost;

class YmlManagerTest {

    @TempDir
    Path dir;

    private final FakeHost host = new FakeHost();
    private final Map<String, String> jar = new HashMap<>();

    private YmlManager manager() {
        return new YmlManager(host, dir.toFile(), path -> jar.containsKey(path)
                ? new ByteArrayInputStream(jar.get(path).getBytes(StandardCharsets.UTF_8)) : null, "config.yml");
    }

    private String read(String path) throws IOException {
        return Files.readString(dir.resolve(path));
    }

    @Test
    void configIsSeededAndMergedAtConstruction() throws IOException {
        jar.put("config.yml", "a: 1\nb: 2\n");
        YmlManager yml = manager();
        assertEquals("a: 1\nb: 2\n", read("config.yml"));
        assertEquals(2, yml.config().getInt("b", 0));
        Files.writeString(dir.resolve("config.yml"), "a: 5\n# old\nupdate-configs: true\n");
        YmlManager again = manager();
        assertEquals("a: 5\nb: 2\n", read("config.yml"));
        assertEquals(5, again.config().getInt("a", 0));
    }

    @Test
    void firstMountFixesTheModeAndWarnsOnce() throws IOException {
        jar.put("config.yml", "a: 1\n");
        jar.put("lang/x.yml", "k: v\nk2: v2\n");
        YmlManager yml = manager();
        Files.createDirectories(dir.resolve("lang"));
        Files.writeString(dir.resolve("lang/x.yml"), "k: mine\n");
        RsYml data = yml.data("lang/x.yml");
        RsYml managed = yml.managed("lang\\x.yml");
        RsYml again = yml.managed("/lang/x.yml");
        assertSame(data, managed);
        assertSame(data, again);
        assertEquals("k: mine\n", read("lang/x.yml"));
        assertEquals(1, host.log.warnings().size(), host.log.warnings().toString());
        assertSame(data, yml.load("lang/x.yml"));
        assertEquals(1, host.log.warnings().size());
    }

    @Test
    void seedOnlyIsSeededButNeverMerged() throws IOException {
        jar.put("config.yml", "a: 1\n");
        jar.put("kits.yml", "starter: 1\n");
        YmlManager yml = manager();
        RsYml kits = yml.seedOnly("kits.yml");
        assertEquals("starter: 1\n", read("kits.yml"));
        Files.writeString(dir.resolve("kits.yml"), "mine: 2\n");
        yml.reloadAll();
        assertEquals("mine: 2\n", read("kits.yml"));
        assertEquals(2, kits.getInt("mine", 0));
        Files.delete(dir.resolve("kits.yml"));
        yml.reloadAll();
        assertEquals("starter: 1\n", read("kits.yml"));
    }

    @Test
    void dataAndLoadAreNeverSeeded() {
        jar.put("config.yml", "a: 1\n");
        jar.put("data.yml", "x: 1\n");
        YmlManager yml = manager();
        yml.data("data.yml");
        yml.load("other.yml");
        assertFalse(Files.exists(dir.resolve("data.yml")));
        assertFalse(Files.exists(dir.resolve("other.yml")));
    }

    @Test
    void managedPruningRemovesAndManagedDoesNot() throws IOException {
        jar.put("config.yml", "a: 1\n");
        jar.put("p.yml", "keep: 1\n");
        jar.put("m.yml", "keep: 1\n");
        Files.writeString(dir.resolve("p.yml"), "keep: 1\nextra: 2\n");
        Files.writeString(dir.resolve("m.yml"), "keep: 1\nextra: 2\n");
        YmlManager yml = manager();
        yml.managedPruning("p.yml");
        yml.managed("m.yml");
        assertEquals("keep: 1\n", read("p.yml"));
        assertEquals("keep: 1\nextra: 2\n", read("m.yml"));
    }

    @Test
    void reloadAllMergesAgainAndFiresHooks() throws IOException {
        jar.put("config.yml", "a: 1\nb: 2\n");
        YmlManager yml = manager();
        List<Integer> seen = new ArrayList<>();
        yml.config().onReload(() -> seen.add(yml.config().getInt("b", 0)));
        Files.writeString(dir.resolve("config.yml"), "a: 3\n");
        yml.reloadAll();
        assertEquals("a: 3\nb: 2\n", read("config.yml"));
        assertEquals(List.of(2), seen);
        assertEquals(3, yml.config().getInt("a", 0));
    }

    @Test
    void flushAllWritesPendingSaves() throws IOException {
        jar.put("config.yml", "a: 1\n");
        YmlManager yml = manager();
        RsYml data = yml.data("store/data.yml");
        data.set("x", 1);
        data.save();
        yml.flushAll();
        assertEquals("x: 1\n", read("store/data.yml"));
        assertTrue(data.file().getPath().endsWith("data.yml"));
    }

    @Test
    void pathsAreNormalized() {
        assertEquals("a/b.yml", YmlManager.normalize(" \\a\\b.yml "));
        assertEquals("a/b.yml", YmlManager.normalize("//a/b.yml"));
    }
}
