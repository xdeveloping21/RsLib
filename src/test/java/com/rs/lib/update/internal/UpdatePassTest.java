package com.rs.lib.update.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** {@link UpdatePass}: picking the release asset and whole passes against fixtures. */
class UpdatePassTest {

    private static final String REPO = UpdatePass.RELEASES_REPO;

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

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String downloadUrl(String version) {
        return UpdatePass.DOWNLOAD_PREFIX + "rslib-" + version + "/RsLib-" + version + ".jar";
    }

    private static String assetJson(String name, String url, String digest) {
        return "{\"name\":\"" + name + "\",\"browser_download_url\":\"" + url + "\""
                + (digest == null ? "" : ",\"digest\":\"" + digest + "\"") + "}";
    }

    private static String release(String... assets) {
        return "{\"tag_name\":\"rslib-1.1.0\",\"assets\":[" + String.join(",", assets) + "]}";
    }

    private static final String DIGEST = "sha256:" + "AB".repeat(32);

    // ------------------------------------------------------------------ the asset

    @Test
    void assetIsTakenFromTheAssetsArray() throws Exception {
        UpdatePass.Asset asset = UpdatePass.asset(json(release(
                assetJson("RsLib-1.1.0.jar.sha256", downloadUrl("1.1.0") + ".sha256", DIGEST),
                assetJson("RsLib-1.1.0.jar", downloadUrl("1.1.0"), DIGEST))), "1.1.0");
        assertEquals("RsLib-1.1.0.jar", asset.name());
        assertEquals(downloadUrl("1.1.0"), asset.url());
        assertEquals("ab".repeat(32), asset.sha256());
    }

    @Test
    void assetOutsideTheAssetsArrayIsIgnored() {
        String sneaky = "{\"tag_name\":\"rslib-1.1.0\",\"name\":\"RsLib-1.1.0.jar\",\"browser_download_url\":\""
                + downloadUrl("1.1.0") + "\",\"digest\":\"" + DIGEST + "\",\"body\":"
                + "\"" + "RsLib-1.1.0.jar" + "\",\"assets\":[]}";
        UpdateRefused refused = assertThrows(UpdateRefused.class, () -> UpdatePass.asset(json(sneaky), "1.1.0"));
        assertTrue(refused.getMessage().contains("no asset named RsLib-1.1.0.jar"), refused.getMessage());
        assertThrows(UpdateRefused.class, () -> UpdatePass.asset(json("{\"tag_name\":\"rslib-1.1.0\"}"), "1.1.0"));
    }

    @Test
    void downloadAddressMustBeWhitelisted() {
        for (String url : List.of(
                "https://evil.example/xdeveloping21/rs-releases/releases/download/rslib-1.1.0/RsLib-1.1.0.jar",
                "https://github.com/someone/rs-releases/releases/download/rslib-1.1.0/RsLib-1.1.0.jar",
                "http://github.com/xdeveloping21/rs-releases/releases/download/rslib-1.1.0/RsLib-1.1.0.jar",
                "https://github.com/xdeveloping21/rs-releases/releases/download/../../../other/RsLib-1.1.0.jar")) {
            UpdateRefused refused = assertThrows(UpdateRefused.class,
                    () -> UpdatePass.asset(json(release(assetJson("RsLib-1.1.0.jar", url, DIGEST))), "1.1.0"), url);
            assertTrue(refused.getMessage().contains("not under"), refused.getMessage());
        }
    }

    @Test
    void missingOrMalformedDigestIsRefused() {
        UpdateRefused missing = assertThrows(UpdateRefused.class, () -> UpdatePass.asset(
                json(release(assetJson("RsLib-1.1.0.jar", downloadUrl("1.1.0"), null))), "1.1.0"));
        assertTrue(missing.getMessage().contains("no SHA-256 digest"), missing.getMessage());
        assertThrows(UpdateRefused.class, () -> UpdatePass.asset(
                json(release(assetJson("RsLib-1.1.0.jar", downloadUrl("1.1.0"), "md5:abc"))), "1.1.0"));
        assertThrows(UpdateRefused.class, () -> UpdatePass.asset(
                json(release(assetJson("RsLib-1.1.0.jar", downloadUrl("1.1.0"), "sha256:12zz"))), "1.1.0"));
    }

    // ------------------------------------------------------------------ whole passes

    private Path plugins() throws IOException {
        return Files.createDirectories(dir.resolve("plugins"));
    }

    private Path installed(String version) throws IOException {
        return TestJars.write(plugins().resolve("RsLib-" + version + ".jar"),
                TestJars.jar(TestJars.rsLibYml(version), 100));
    }

    private UpdatePass.Env env(String installed, Path codeSource, boolean sameMajorOnly) throws IOException {
        return new UpdatePass.Env(installed, plugins(), codeSource, plugins().resolve("update"), sameMajorOnly,
                new GitHubClient(http), new ReleaseFeed(System::nanoTime), RsLibJars.DISK);
    }

    /** Publishes {@code jar} as RsLib {@code version} with the given digest (null: its real SHA-256). */
    private void publish(String version, byte[] jar, String digest) {
        String hex = digest != null ? digest : "sha256:" + HexFormat.of().formatHex(ReleaseFeed.sha256(jar));
        http.answer(GitHubClient.API_ROOT + "/repos/" + REPO + "/releases/tags/rslib-" + version,
                release(assetJson("RsLib-" + version + ".jar", downloadUrl(version), hex)));
        http.file(downloadUrl(version), jar);
    }

    private void tags(String... tags) {
        http.answer(FakeTransport.pageUrl(REPO, 1), FakeTransport.releaseList(List.of(tags)));
    }

    @Test
    void newerReleaseIsDownloadedCheckedAndInstalled() throws Exception {
        Path old = installed("1.0.0");
        tags("rskills-9.0.0", "rslib-1.1.0", "rslib-1.0.0", "rslib-v1.0.5");
        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.0"), 20_000), null);

        UpdatePass.Outcome outcome = UpdatePass.run(env("1.0.0", old, true), reporter);

        assertEquals(UpdatePass.Status.INSTALLED, outcome.status(), warnings.toString());
        assertEquals("1.1.0", outcome.version());
        assertEquals(plugins().resolve("RsLib-1.1.0.jar"), outcome.jar());
        assertFalse(Files.exists(old));
        RsLibJars.verify(outcome.jar(), "1.1.0");
        assertFalse(Files.exists(UpdatePass.staging(plugins())), "staging folder left behind");
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("SHA-256 checked")), steps.toString());
        // The download carries no token and no API headers.
        FakeTransport.Sent download = http.sent.get(http.sent.size() - 1);
        assertEquals(downloadUrl("1.1.0"), download.url());
        assertFalse(download.headers().containsKey("Authorization"));
    }

    @Test
    void upToDateMakesNoFurtherRequests() throws Exception {
        Path old = installed("1.1.0");
        tags("rslib-1.1.0", "rslib-1.0.0");
        UpdatePass.Outcome outcome = UpdatePass.run(env("1.1.0", old, true), reporter);
        assertEquals(UpdatePass.Status.UP_TO_DATE, outcome.status());
        assertEquals(1, http.sent.size());
    }

    @Test
    void newMajorIsSkippedWhenSameMajorOnly() throws Exception {
        Path old = installed("1.1.0");
        tags("rslib-2.0.0", "rslib-1.1.0");
        assertEquals(UpdatePass.Status.SKIPPED, UpdatePass.run(env("1.1.0", old, true), reporter).status());
        assertEquals(1, http.sent.size());
        assertTrue(Files.exists(old));
    }

    @Test
    void sameMajorUpdateIsInstalledWhenANewMajorExists() throws Exception {
        Path old = installed("1.0.0");
        tags("rslib-2.0.0", "rslib-1.2.0", "rslib-1.1.0");
        publish("1.2.0", TestJars.jar(TestJars.rsLibYml("1.2.0"), 20_000), null);
        UpdatePass.Outcome outcome = UpdatePass.run(env("1.0.0", old, true), reporter);
        assertEquals(UpdatePass.Status.INSTALLED, outcome.status(), warnings.toString());
        assertEquals("1.2.0", outcome.version());
    }

    @Test
    void digestMismatchKeepsTheInstalledJar() throws Exception {
        Path old = installed("1.0.0");
        tags("rslib-1.1.0");
        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.0"), 20_000), "sha256:" + "00".repeat(32));
        UpdatePass.Outcome outcome = UpdatePass.run(env("1.0.0", old, true), reporter);
        assertEquals(UpdatePass.Status.FAILED, outcome.status());
        assertTrue(warnings.get(0).contains("does not match"), warnings.toString());
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(plugins().resolve("RsLib-1.1.0.jar")));
        assertFalse(Files.exists(UpdatePass.staging(plugins())));
    }

    @Test
    void tooSmallOrWrongVersionIsRefused() throws Exception {
        Path old = installed("1.0.0");
        tags("rslib-1.1.0");
        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.0"), 10), null);
        assertEquals(UpdatePass.Status.FAILED, UpdatePass.run(env("1.0.0", old, true), reporter).status());
        assertTrue(warnings.get(0).contains("only"), warnings.toString());

        publish("1.1.0", TestJars.jar(TestJars.rsLibYml("1.1.1"), 20_000), null);
        assertEquals(UpdatePass.Status.FAILED, UpdatePass.run(env("1.0.0", old, true), reporter).status());
        assertTrue(warnings.get(1).contains("'1.1.1'"), warnings.toString());
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(UpdatePass.staging(plugins())));
    }

    @Test
    void missingTagsAndHttpFailuresAreWarnings() throws Exception {
        Path old = installed("1.0.0");
        tags("rskills-1.0.0");
        assertEquals(UpdatePass.Status.FAILED, UpdatePass.run(env("1.0.0", old, true), reporter).status());
        assertTrue(warnings.get(0).startsWith("No release tagged rslib-"), warnings.toString());

        http.answer(FakeTransport.pageUrl(REPO, 1), 403, "{\"message\":\"rate limited\"}");
        assertEquals(UpdatePass.Status.FAILED, UpdatePass.run(env("1.0.0", old, true), reporter).status());
        assertTrue(warnings.get(1).contains("HTTP 403"), warnings.toString());
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
