package com.rs.lib.yml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rs.lib.yml.YmlTestSupport.FakeHost;

/** A plugin's own {@code update-configs} in its main config decides for all of its managed files. */
class YmlManagerSwitchTest {

    @TempDir
    Path dir;

    private final FakeHost host = new FakeHost();
    private final Map<String, String> jar = new HashMap<>();

    @AfterEach
    void restore() {
        YamlUpdater.setMergeEnabled(true, null);
    }

    private YmlManager manager() {
        return new YmlManager(host, dir.toFile(), path -> jar.containsKey(path)
                ? new ByteArrayInputStream(jar.get(path).getBytes(StandardCharsets.UTF_8)) : null, "config.yml");
    }

    private String read(String path) throws IOException {
        return Files.readString(dir.resolve(path));
    }

    @Test
    void aFreshConfigTakesTheJarsSwitch() {
        jar.put("config.yml", "# Master switch.\nupdate-configs: true\na: 1\n");
        assertEquals(Boolean.TRUE, manager().updateConfigs());
    }

    @Test
    void falseInThePluginsConfigStopsEveryMergeOfThatPlugin() throws IOException {
        jar.put("config.yml", "update-configs: true\na: 1\nb: 2\n");
        jar.put("menus.yml", "x: 1\ny: 2\n");
        Files.writeString(dir.resolve("config.yml"), "update-configs: false\na: 5\n");
        Files.writeString(dir.resolve("menus.yml"), "x: 9\n");
        YmlManager yml = manager();
        yml.managed("menus.yml");
        assertEquals(Boolean.FALSE, yml.updateConfigs());
        assertEquals("update-configs: false\na: 5\n", read("config.yml"));
        assertEquals("x: 9\n", read("menus.yml"));
        assertEquals(2, host.log.warnings().size(), host.log.warnings().toString());

        Files.writeString(dir.resolve("config.yml"), "update-configs: true\na: 5\n");
        yml.reloadAll();
        assertEquals(Boolean.TRUE, yml.updateConfigs());
        assertTrue(read("config.yml").contains("b: 2"));
        assertEquals("x: 9\ny: 2\n", read("menus.yml"));
    }

    @Test
    void trueInThePluginsConfigMergesWhileRsLibsIsFalse() throws IOException {
        YamlUpdater.setMergeEnabled(false, dir.resolve("RsLib/config.yml").toFile());
        jar.put("config.yml", "update-configs: true\na: 1\nb: 2\n");
        Files.writeString(dir.resolve("config.yml"), "update-configs: true\na: 5\n");
        manager();
        assertEquals("update-configs: true\na: 5\nb: 2\n", read("config.yml"));
    }

    @Test
    void withoutTheKeyRsLibsSwitchApplies() throws IOException {
        YamlUpdater.setMergeEnabled(false, dir.resolve("RsLib/config.yml").toFile());
        jar.put("config.yml", "a: 1\nb: 2\n");
        Files.writeString(dir.resolve("config.yml"), "a: 5\n");
        YmlManager yml = manager();
        assertNull(yml.updateConfigs());
        assertEquals("a: 5\n", read("config.yml"));
    }

    @Test
    void aKeyTheJarDoesNotDeclareIsRemovedAndStopsCounting() throws IOException {
        jar.put("config.yml", "a: 1\nb: 2\n");
        Files.writeString(dir.resolve("config.yml"), "update-configs: true\na: 5\n");
        YmlManager yml = manager();
        assertEquals("a: 5\nb: 2\n", read("config.yml"));
        assertNull(yml.updateConfigs());
    }
}
