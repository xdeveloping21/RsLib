package com.rs.lib.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * The same jar is the Paper plugin and the Velocity plugin. Paper has no Velocity classes and
 * Velocity has no Bukkit classes, so: no class outside {@code com.rs.lib.velocity} may name that
 * package or the Velocity API, and nothing the velocity package reaches may name Bukkit, Paper
 * or another server-only library. Checked on the compiled classes (every reference a class can
 * ever link to is in its constant pool) and by running the proxy side in a class loader that
 * has no Bukkit at all.
 */
class VelocityIsolationTest {

    /** Packages that only exist on a Paper server (or in plugins RsLib hooks there). */
    private static final List<String> SERVER_ONLY = List.of("org/bukkit/", "io/papermc/", "com/destroystokyo/",
            "org/spigotmc/", "net/md_5/", "co/aikar/", "me/clip/", "net/milkbowl/", "com/zaxxer/", "org/sqlite/",
            "com/mysql/");
    private static final String VELOCITY_PACKAGE = "com/rs/lib/velocity/";
    private static final String PROXY_API = "com/velocitypowered/";
    private static final String OWN = "com/rs/lib/";

    private static FileSystem jarFileSystem;
    private static Path classes;

    @TempDir
    Path dir;

    @BeforeAll
    static void locateCompiledClasses() throws Exception {
        URI location = RsLibVelocity.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path path = Path.of(location);
        if (Files.isDirectory(path)) {
            classes = path;
        } else {
            jarFileSystem = FileSystems.newFileSystem(path);
            classes = jarFileSystem.getPath("/");
        }
    }

    @AfterAll
    static void close() throws IOException {
        if (jarFileSystem != null) {
            jarFileSystem.close();
        }
    }

    // ---------------------------------------------------------------- Paper side

    @Test
    void noPaperSideClassNamesTheVelocityPackageOrApi() throws IOException {
        Map<String, byte[]> all = compiledClasses();
        assertTrue(all.containsKey("com/rs/lib/RsLibPlugin"), "the compiled classes were found");
        assertTrue(all.containsKey("com/rs/lib/velocity/RsLibVelocity"));
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : all.entrySet()) {
            if (entry.getKey().startsWith(VELOCITY_PACKAGE)) {
                continue;
            }
            String text = latin1(entry.getValue());
            if (text.contains(VELOCITY_PACKAGE) || text.contains(PROXY_API)) {
                offenders.add(entry.getKey());
            }
        }
        assertEquals(List.of(), offenders, "these classes could make Paper load a Velocity class");
    }

    @Test
    void thePaperEntryPointsLinkWithoutTheVelocityApi() throws Exception {
        try (IsolatedLoader loader = new IsolatedLoader(List.of(PROXY_API.replace('/', '.')))) {
            for (String name : List.of("com.rs.lib.RsLibPlugin", "com.rs.lib.RsPlugin", "com.rs.lib.Rs",
                    "com.rs.lib.RsLib", "com.rs.lib.RsSpec", "com.rs.lib.yml.YamlUpdater")) {
                Class<?> type = loader.loadClass(name);
                type.getDeclaredMethods();
                type.getDeclaredFields();
                type.getDeclaredConstructors();
            }
            for (String loaded : loader.loaded) {
                assertFalse(loaded.startsWith("com.rs.lib.velocity."), loaded);
            }
        }
    }

    // ---------------------------------------------------------------- proxy side

    @Test
    void nothingTheVelocityPackageReachesNamesAServerOnlyLibrary() throws IOException {
        Map<String, byte[]> all = compiledClasses();
        Set<String> reached = new TreeSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String name : all.keySet()) {
            if (name.startsWith(VELOCITY_PACKAGE)) {
                pending.add(name);
            }
        }
        while (!pending.isEmpty()) {
            String name = pending.poll();
            if (!reached.add(name)) {
                continue;
            }
            for (String referenced : ownReferences(latin1(all.get(name)))) {
                if (all.containsKey(referenced) && !reached.contains(referenced)) {
                    pending.add(referenced);
                }
            }
        }
        List<String> offenders = new ArrayList<>();
        for (String name : reached) {
            String text = latin1(all.get(name));
            for (String prefix : SERVER_ONLY) {
                if (text.contains(prefix)) {
                    offenders.add(name + " -> " + prefix);
                }
            }
        }
        assertEquals(List.of(), offenders, "reached from the velocity package: " + reached);
    }

    @Test
    void theProxySideRunsWithoutBukkit() throws Exception {
        List<String> hidden = new ArrayList<>();
        for (String prefix : SERVER_ONLY) {
            hidden.add(prefix.replace('/', '.'));
        }
        VelocityFakes fakes = new VelocityFakes();
        try (IsolatedLoader loader = new IsolatedLoader(hidden)) {
            assertThrowsNotFound(loader, "org.bukkit.Bukkit");
            Class<?> rsvType = loader.loadClass("com.rs.lib.velocity.Rsv");
            Path data = dir.resolve("proxy-plugin");
            Files.createDirectories(data);
            Files.writeString(data.resolve("config.yml"), "# mine\nmotd: '&aHello'\n");
            Object rsv = rsvType.getMethod("create", Object.class, ProxyServer.class, Logger.class, Path.class)
                    .invoke(null, new Object(), fakes.proxy, fakes.logger, data);
            Object config = rsvType.getMethod("config").invoke(rsv);
            assertEquals("&aHello", config.getClass().getMethod("getString", String.class, String.class)
                    .invoke(config, "motd", null));
            Component motd = (Component) rsvType.getMethod("color", String.class).invoke(rsv, "&aHello [small]world");
            assertTrue(PlainTextComponentSerializer.plainText().serialize(motd).startsWith("Hello "));
            Object scheduler = rsvType.getMethod("scheduler").invoke(rsv);
            scheduler.getClass().getMethod("later", Runnable.class, Duration.class)
                    .invoke(scheduler, (Runnable) () -> { }, Duration.ofSeconds(1));
            assertEquals(1, fakes.tasks.size());

            Class<?> configType = loader.loadClass("com.rs.lib.velocity.RsvConfig");
            InputStream defaults = new ByteArrayInputStream("motd: x\n# new\nslots: 10\n"
                    .getBytes(StandardCharsets.UTF_8));
            Object merged = configType.getMethod("load", Path.class, InputStream.class, Logger.class)
                    .invoke(null, data.resolve("config.yml"), defaults, fakes.logger);
            assertEquals(10, configType.getMethod("getInt", String.class, int.class).invoke(merged, "slots", 0));
            assertEquals("# mine\nmotd: '&aHello'\n# new\nslots: 10\n", Files.readString(data.resolve("config.yml")));

            Object entry = loader.loadClass("com.rs.lib.velocity.RsLibVelocity")
                    .getConstructor(ProxyServer.class, Logger.class).newInstance(fakes.proxy, fakes.logger);
            entry.getClass().getMethods();

            List<String> unexpected = new ArrayList<>();
            for (String loaded : loader.loaded) {
                if (!loaded.startsWith("com.rs.lib.velocity.") && !loaded.startsWith("com.rs.lib.text.")
                        && !loaded.startsWith("com.rs.lib.yml.internal.") && !loaded.equals("com.rs.lib.Ph")
                        && !loaded.equals("com.rs.lib.util.NumberFormatter")) {
                    unexpected.add(loaded);
                }
            }
            assertEquals(List.of(), unexpected, "RsLib classes the proxy side loaded: " + loader.loaded);
        }
        assertEquals(List.of(), fakes.logs);
    }

    private static void assertThrowsNotFound(ClassLoader loader, String name) {
        try {
            loader.loadClass(name);
            fail(name + " is visible");
        } catch (ClassNotFoundException expected) {
            // hidden, as on a proxy
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Every compiled RsLib class, by internal name. */
    private static Map<String, byte[]> compiledClasses() throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        Path base = classes.resolve(OWN);
        try (Stream<Path> files = Files.walk(base)) {
            files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String relative = classes.relativize(p).toString().replace('\\', '/');
                try {
                    out.put(relative.substring(0, relative.length() - ".class".length()), Files.readAllBytes(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return out;
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /** The internal names {@code com/rs/lib/...} written anywhere in a class file. */
    private static Set<String> ownReferences(String classText) {
        Set<String> out = new TreeSet<>();
        int from = 0;
        int at;
        while ((at = classText.indexOf(OWN, from)) >= 0) {
            int end = at + OWN.length();
            while (end < classText.length() && isNameChar(classText.charAt(end))) {
                end++;
            }
            out.add(classText.substring(at, end));
            from = end;
        }
        return out;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '$' || c == '/';
    }

    /**
     * Loads RsLib's classes itself from the compiled classes (everything else from the test class
     * path) and refuses the given package prefixes, as a server without them would.
     */
    private static final class IsolatedLoader extends URLClassLoader {
        private final List<String> hidden;
        final List<String> loaded = Collections.synchronizedList(new ArrayList<>());

        IsolatedLoader(List<String> hidden) throws IOException {
            super(new URL[] {classesUrl()}, VelocityIsolationTest.class.getClassLoader());
            this.hidden = hidden;
        }

        private static URL classesUrl() throws IOException {
            return RsLibVelocity.class.getProtectionDomain().getCodeSource().getLocation();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String prefix : hidden) {
                if (name.startsWith(prefix)) {
                    throw new ClassNotFoundException(name + " (hidden)");
                }
            }
            if (!name.startsWith("com.rs.lib.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    type = findClass(name);
                    loaded.add(name);
                }
                if (resolve) {
                    resolveClass(type);
                }
                return type;
            }
        }
    }
}
