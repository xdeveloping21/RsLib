package com.rs.lib.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.rs.lib.text.RsText;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class RsvTest {

    /** Stands for a proxy plugin's main class; defined again from its bytes inside a plugin "jar". */
    public static final class SamplePlugin {
    }

    @TempDir
    Path dir;

    private final VelocityFakes fakes = new VelocityFakes();

    private Rsv create(Object plugin) {
        return Rsv.create(plugin, fakes.proxy, fakes.logger, dir.resolve("data"));
    }

    // ---------------------------------------------------------------- context

    @Test
    void theContextHandsBackWhatItWasGiven() {
        Object plugin = new Object();
        Rsv rsv = create(plugin);
        assertSame(fakes.proxy, rsv.proxy());
        assertSame(fakes.logger, rsv.logger());
        assertEquals(dir.resolve("data"), rsv.dataDir());
        assertNotNull(rsv.config());
        assertNotNull(rsv.scheduler());
    }

    @Test
    void theConfigDefaultsComeFromThePluginsOwnJarOnly() throws Exception {
        Path jar = dir.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(Rsv.CONFIG));
            out.write("# from the plugin\ngreeting: '&aHi'\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        URL location = jar.toUri().toURL();
        try (PluginLoader loader = new PluginLoader(location)) {
            Object plugin = loader.definePlugin().getDeclaredConstructor().newInstance();
            Rsv rsv = create(plugin);
            assertEquals("&aHi", rsv.config().getString("greeting", null));
        }
        assertEquals("# from the plugin\ngreeting: '&aHi'\n", Files.readString(dir.resolve("data/config.yml")));

        // The test class path holds RsLib's own config.yml; a plugin whose jar has none must not get it.
        Rsv bare = Rsv.create(new SamplePlugin(), fakes.proxy, fakes.logger, dir.resolve("bare"));
        assertTrue(bare.config().keys().isEmpty());
        assertTrue(Files.notExists(dir.resolve("bare/config.yml")));
    }

    @Test
    void defaultsUrlPicksTheCopyOfTheGivenLocation() throws IOException {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Path c = Files.createDirectories(dir.resolve("c"));
        Files.writeString(a.resolve("config.yml"), "a: 1\n");
        Files.writeString(b.resolve("config.yml"), "b: 1\n");
        try (URLClassLoader parent = new URLClassLoader(new URL[] {b.toUri().toURL()}, null);
             URLClassLoader child = new URLClassLoader(new URL[] {a.toUri().toURL(), c.toUri().toURL()}, parent)) {
            assertEquals(a.resolve("config.yml").toUri().toURL().toString(),
                    Rsv.defaultsUrl(child, a.toUri().toURL(), "config.yml").toString());
            assertNull(Rsv.defaultsUrl(child, c.toUri().toURL(), "config.yml"));
            assertEquals(b.resolve("config.yml").toUri().toURL().toString(),
                    Rsv.defaultsUrl(child, null, "config.yml").toString(), "unknown location: the loader's own pick");
        }
    }

    @Test
    void reloadConfigReadsTheFileAgain() throws IOException {
        Rsv rsv = create(new Object());
        Path file = dir.resolve("data/config.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "motd: one\n");
        RsvConfig first = rsv.reloadConfig();
        assertSame(first, rsv.config());
        assertEquals("one", rsv.config().getString("motd", null));
        Files.writeString(file, "motd: two\n");
        rsv.reloadConfig();
        assertEquals("two", rsv.config().getString("motd", null));
    }

    @Test
    void colorUsesTheRsLibTextPipeline() {
        Rsv rsv = create(new Object());
        assertEquals(RsText.color("&aHi <bold>there"), rsv.color("&aHi <bold>there"));
        assertEquals(NamedTextColor.GREEN, rsv.color("&aHi").color());
        assertEquals("Hi", PlainTextComponentSerializer.plainText().serialize(rsv.color("&aHi")));
    }

    // ---------------------------------------------------------------- commands

    @Test
    void commandsAreRegisteredWithTheirAliasesAndOwner() {
        Object plugin = new Object();
        Rsv rsv = create(plugin);
        SimpleCommand command = invocation -> { };
        CommandMeta meta = rsv.command("hub", command, "lobby", "l");
        assertEquals(1, fakes.commands.size());
        VelocityFakes.Registration registration = fakes.commands.get(0);
        assertSame(meta, registration.meta);
        assertSame(command, registration.command);
        assertSame(plugin, registration.plugin);
        assertEquals(Set.of("hub", "lobby", "l"), registration.aliases);

        rsv.command("solo", command);
        assertEquals(Set.of("solo"), fakes.commands.get(1).aliases);
    }

    // ---------------------------------------------------------------- scheduler

    @Test
    void theSchedulerIsAShortcutOverBuildTask() {
        Object plugin = new Object();
        RsvScheduler scheduler = create(plugin).scheduler();
        Runnable body = () -> { };
        assertNotNull(scheduler.run(body));
        scheduler.later(body, Duration.ofSeconds(5));
        scheduler.repeat(body, Duration.ofMillis(250));
        scheduler.repeat(body, Duration.ofSeconds(1), Duration.ofMinutes(1));
        List<VelocityFakes.Task> tasks = fakes.tasks;
        assertEquals(4, tasks.size());
        for (VelocityFakes.Task task : tasks) {
            assertSame(plugin, task.plugin);
            assertSame(body, task.body);
            assertTrue(task.scheduled);
        }
        assertEquals(List.of(-1L, -1L), List.of(tasks.get(0).delayMillis, tasks.get(0).repeatMillis));
        assertEquals(List.of(5000L, -1L), List.of(tasks.get(1).delayMillis, tasks.get(1).repeatMillis));
        assertEquals(List.of(-1L, 250L), List.of(tasks.get(2).delayMillis, tasks.get(2).repeatMillis));
        assertEquals(List.of(1000L, 60000L), List.of(tasks.get(3).delayMillis, tasks.get(3).repeatMillis));
    }

    // ---------------------------------------------------------------- entry point

    @Test
    void theEntryPointIsInjectedAndLogsOnProxyInit() throws Exception {
        Constructor<?>[] constructors = RsLibVelocity.class.getConstructors();
        assertEquals(1, constructors.length);
        assertTrue(constructors[0].isAnnotationPresent(com.google.inject.Inject.class));
        Method init = RsLibVelocity.class.getMethod("onProxyInit", ProxyInitializeEvent.class);
        assertTrue(init.isAnnotationPresent(Subscribe.class));

        new RsLibVelocity(fakes.proxy, fakes.logger).onProxyInit(new ProxyInitializeEvent());
        assertEquals(List.of("RsLib 1.2.3 enabled on Velocity."), fakes.logged("info"));
    }

    @Test
    void velocityPluginJsonDeclaresTheEntryPointAndTheRslibId() throws IOException {
        Map<?, ?> json;
        try (InputStream in = RsLibVelocity.class.getResourceAsStream("/velocity-plugin.json")) {
            assertNotNull(in, "velocity-plugin.json is a resource of the jar");
            json = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        }
        assertEquals("rslib", json.get("id"));
        assertTrue(((String) json.get("id")).matches("[a-z][a-z0-9-_]{0,63}"), "a valid Velocity id");
        assertEquals("RsLib", json.get("name"));
        assertEquals(RsLibVelocity.class.getName(), json.get("main"));
        assertEquals(List.of(), json.get("dependencies"));
        String version = (String) json.get("version");
        assertTrue(version.equals("${project.version}") || (!version.isBlank() && !version.contains("${")),
                "the version comes from the pom: " + version);
    }

    /** Defines {@link SamplePlugin} again with its code source at a given jar, as Velocity's loader would. */
    private static final class PluginLoader extends URLClassLoader {
        private final URL location;

        PluginLoader(URL location) {
            super(new URL[] {location}, RsvTest.class.getClassLoader());
            this.location = location;
        }

        Class<?> definePlugin() throws IOException {
            String name = SamplePlugin.class.getName();
            byte[] bytes;
            try (InputStream in = RsvTest.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                bytes = in.readAllBytes();
            }
            return defineClass(name, bytes, 0, bytes.length,
                    new ProtectionDomain(new CodeSource(location, (Certificate[]) null), null));
        }
    }
}
