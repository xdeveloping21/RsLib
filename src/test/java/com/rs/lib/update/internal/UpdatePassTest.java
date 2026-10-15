package com.rs.lib.update.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link UpdatePass}: whole passes against rsdevelopment.dev fixtures. */
class UpdatePassTest {

    private static final String RELEASE = SiteClient.BASE_URL + "/v1/releases/rslib";
    private static final String DOWNLOAD = RELEASE + "/download";

    @TempDir
    Path dir;

    private final FakeTransport http = new FakeTransport();
    private final List<String> steps = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final UpdatePass.Reporter reporter = new UpdatePass.Reporter() {
        @Override
        public void step(String message) {
            steps.add(message);
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }
    };

    private Path plugins() throws IOException {
        return Files.createDirectories(dir.resolve("plugins"));
    }

    private Path installed(String version) throws IOException {
        return TestJars.write(plugins().resolve("RsLib-" + version + ".jar"),
                TestJars.jar(TestJars.rsLibYml(version), 100));
    }

    /** A fresh cache on every pass, so each one reads the fixtures again. */
    private UpdatePass.Env env(String installed, Path codeSource, boolean sameMajorOnly) throws IOException {
        return new UpdatePass.Env(installed, plugins(), codeSource, plugins().resolve("update"), sameMajorOnly,
                new SiteClient(http, "RsLib/" + installed), new ReleaseCache(System::nanoTime), RsLibJars.DISK);
    }

    /** Publishes {@code jar} as RsLib {@code version} with the given digest (null: its real SHA-256). */
    private void publish(String version, byte[] jar, String sha256) {
        http.answer(RELEASE, FakeTransport.release("rslib", version,
                sha256 != null ? sha256 : TestJars.sha256(jar), jar.length));
        http.file(DOWNLOAD, jar);
    }

    private UpdatePass.Outcome pass(String installed, Path old) throws IOException {
        return UpdatePass.run(env(installed, old, true), reporter);
    }

    @Test
    void newerVersionIsDownloadedCheckedAndInstalled() throws Exception {
        Path old = installed("1.0.0");
        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.0"), 20_000), null);

        UpdatePass.Outcome outcome = pass("1.0.0", old);

        assertEquals(UpdatePass.Status.INSTALLED, outcome.status(), warnings.toString());
        assertEquals("1.1.0", outcome.version());
        assertEquals(plugins().resolve("RsLib-1.1.0.jar"), outcome.jar());
        assertFalse(Files.exists(old));
        RsLibJars.verify(outcome.jar(), "1.1.0");
        assertFalse(Files.exists(UpdatePass.staging(plugins())), "staging folder left behind");
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("SHA-256 checked")), steps.toString());
        assertTrue(steps.stream().anyMatch(s -> s.endsWith("installed on disk. Restart the server to activate it.")),
                steps.toString());
        // One read of the release and one download, both from the fixed addresses.
        assertEquals(List.of(RELEASE, DOWNLOAD), http.sent.stream().map(FakeTransport.Sent::url).toList());
        FakeTransport.Sent download = http.sent.get(1);
        assertEquals("RsLib/1.0.0", download.headers().get("User-Agent"));
        assertEquals("application/java-archive", download.headers().get("Accept"));
        assertFalse(download.headers().containsKey("Authorization"));
    }

    @Test
    void upToDateOrNewerInstalledMakesNoFurtherRequests() throws Exception {
        Path old = installed("1.1.0");
        http.answer(RELEASE, FakeTransport.release("rslib", "1.1.0"));
        UpdatePass.Outcome same = pass("1.1.0", old);
        assertEquals(UpdatePass.Status.UP_TO_DATE, same.status());
        assertEquals("1.1.0", same.version());

        http.answer(RELEASE, FakeTransport.release("rslib", "1.0.9"));
        assertEquals(UpdatePass.Status.UP_TO_DATE, pass("1.1.0", old).status());
        assertTrue(steps.stream().anyMatch(s -> s.contains("is up to date (rsdevelopment.dev offers 1.0.9)")),
                steps.toString());
        assertTrue(steps.contains("RsLib is up to date (1.1.0)."), steps.toString());
        assertEquals(0, http.count(DOWNLOAD));
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(Files.exists(old));
    }

    @Test
    void newMajorIsSkippedWhenSameMajorOnly() throws Exception {
        Path old = installed("1.1.0");
        publish("2.0.0", TestJars.jar(TestJars.rsLibYml("2.0.0"), 20_000), null);
        UpdatePass.Outcome outcome = pass("1.1.0", old);
        assertEquals(UpdatePass.Status.SKIPPED, outcome.status());
        assertEquals("2.0.0", outcome.version());
        assertTrue(steps.stream().anyMatch(s -> s.contains("new major version")), steps.toString());
        assertEquals(0, http.count(DOWNLOAD));
        assertTrue(Files.exists(old));
    }

    @Test
    void newMajorIsInstalledWhenAllowed() throws Exception {
        Path old = installed("1.1.0");
        publish("2.0.0", TestJars.jar(TestJars.rsLibYml("2.0.0"), 20_000), null);
        UpdatePass.Outcome outcome = UpdatePass.run(env("1.1.0", old, false), reporter);
        assertEquals(UpdatePass.Status.INSTALLED, outcome.status(), warnings.toString());
        assertEquals(plugins().resolve("RsLib-2.0.0.jar"), outcome.jar());
    }

    @Test
    void digestMismatchKeepsTheInstalledJar() throws Exception {
        Path old = installed("1.0.0");
        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.0"), 20_000), "00".repeat(32));
        UpdatePass.Outcome outcome = pass("1.0.0", old);
        assertEquals(UpdatePass.Status.FAILED, outcome.status());
        assertTrue(warnings.get(0).contains("does not match"), warnings.toString());
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(plugins().resolve("RsLib-1.1.0.jar")));
        assertFalse(Files.exists(UpdatePass.staging(plugins())));
    }

    @Test
    void tooSmallWrongSizeOrWrongVersionIsRefused() throws Exception {
        Path old = installed("1.0.0");
        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.0"), 10), null);
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(0).contains("only"), warnings.toString());

        byte[] jar = TestJars.jar(TestJars.rsLibYml("1.1.0"), 20_000);
        http.answer(RELEASE, FakeTransport.release("rslib", "1.1.0", TestJars.sha256(jar), jar.length + 1));
        http.file(DOWNLOAD, jar);
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(1).contains("the site announced"), warnings.toString());

        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.1"), 20_000), null);
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(2).contains("'1.1.1'"), warnings.toString());
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(UpdatePass.staging(plugins())));
    }

    @Test
    void httpFailuresAndMalformedAnswersAreWarnings() throws Exception {
        Path old = installed("1.0.0");
        // Nothing published: the site's 404.
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(0).contains("HTTP 404") && warnings.get(0).contains("unknown product"),
                warnings.toString());

        http.answer(RELEASE, 503, "<html>unavailable</html>");
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(1).contains("HTTP 503"), warnings.toString());

        // A redirect is never followed.
        http.answer(RELEASE, 302, "");
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(2).contains("HTTP 302"), warnings.toString());

        http.answer(RELEASE, "{\"product\":\"rslib\",\"version\":");
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(3).contains("unreadable JSON"), warnings.toString());

        http.answer(RELEASE, FakeTransport.release("rskills", "9.0.0"));
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(4).contains("'rskills'"), warnings.toString());

        // A version without a download.
        http.answer(RELEASE, FakeTransport.release("rslib", "1.1.0"));
        assertEquals(UpdatePass.Status.FAILED, pass("1.0.0", old).status());
        assertTrue(warnings.get(5).contains("HTTP 404 downloading"), warnings.toString());
        assertTrue(http.sent.stream().allMatch(s -> s.url().equals(RELEASE) || s.url().equals(DOWNLOAD)));
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(UpdatePass.staging(plugins())));
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsAreClampedAndDefaulted() throws Exception {
        assertEquals(new SelfUpdater.Settings(true, 12, true), SelfUpdater.Settings.read(new YamlConfiguration()));
        YamlConfiguration high = new YamlConfiguration();
        high.loadFromString("auto-update:\n  enabled: false\n  interval-hours: 500\n  same-major-only: false\n");
        assertEquals(new SelfUpdater.Settings(false, 168, false), SelfUpdater.Settings.read(high));
        YamlConfiguration low = new YamlConfiguration();
        low.loadFromString("auto-update:\n  interval-hours: 0\n");
        assertEquals(1, SelfUpdater.Settings.read(low).intervalHours());
    }

    @Test
    void bundledConfigHoldsTheDefaults() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        try (var in = UpdatePassTest.class.getResourceAsStream("/config.yml")) {
            bundled.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(new SelfUpdater.Settings(true, 12, true), SelfUpdater.Settings.read(bundled));
        assertTrue(bundled.isSet("auto-update.interval-hours"));
    }
}
