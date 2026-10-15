package com.rs.lib.update.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.rs.lib.update.Versions;

/**
 * One self-update pass of RsLib, without Bukkit: find the highest {@code rslib-<version>} tag,
 * fetch that release's {@code RsLib-<version>.jar} asset, check it and install it for the next
 * start. Every step is told to a {@link Reporter}; nothing here throws.
 *
 * <p>The asset is taken only from the release's {@code assets} array, its download address must
 * start with {@link #DOWNLOAD_PREFIX}, it must publish a {@code sha256:} digest, weigh between
 * 16 KiB and 64 MiB, match the digest and carry the plugin.yml of RsLib at exactly that
 * version. Anything else is refused with a warning.</p>
 */
public final class UpdatePass {

    /** The repository that publishes RsLib's releases. */
    public static final String RELEASES_REPO = "xdeveloping21/rs-releases";
    /** RsLib's tags in {@link #RELEASES_REPO}: {@code rslib-<version>}. */
    public static final String TAG_PREFIX = "rslib-";
    /** Downloads are only accepted from this address. */
    static final String DOWNLOAD_PREFIX = "https://github.com/" + RELEASES_REPO + "/releases/download/";
    static final long MAX_BYTES = 64L << 20;
    static final long MIN_BYTES = 16L << 10;
    /** Folder under {@code plugins/} where a download waits while it is checked. */
    static final String STAGING = ".rslib-update";
    private static final String DIGEST_PREFIX = "sha256:";

    private UpdatePass() {
    }

    /** Where the steps of a pass go. */
    public interface Reporter {

        /** Progress, for whoever forced the pass. */
        void step(String message);

        /** Something went wrong or was refused. */
        void warn(String message);
    }

    /**
     * What a pass works with.
     *
     * @param installedVersion the running RsLib version
     * @param pluginsDir       the {@code plugins/} folder
     * @param codeSource       the file the classes were loaded from, or null
     * @param updateFolder     Bukkit's update folder, used when the jar cannot be swapped now
     * @param sameMajorOnly    refuse a version with another major number
     */
    public record Env(String installedVersion, Path pluginsDir, Path codeSource, Path updateFolder,
                      boolean sameMajorOnly, GitHubClient github, ReleaseFeed feed, RsLibJars.FileOps ops) {
    }

    public enum Status { UP_TO_DATE, SKIPPED, INSTALLED, DEFERRED, FAILED }

    /**
     * How a pass ended.
     *
     * @param version the version found, or null
     * @param jar     the new jar when one was installed or deferred, else null
     */
    public record Outcome(Status status, String version, Path jar) {
    }

    /** The release asset to download. */
    record Asset(String name, String url, String sha256) {
    }

    /** The staging folder under {@code pluginsDir}. */
    public static Path staging(Path pluginsDir) {
        return pluginsDir.resolve(STAGING);
    }

    /** {@code RsLib-<version>.jar}. */
    static String assetName(String version) {
        return RsLibJars.NAME + "-" + version + ".jar";
    }

    /** Runs one pass; the staging folder is cleaned afterwards whatever happened. */
    public static Outcome run(Env env, Reporter report) {
        try {
            return pass(env, report);
        } catch (RuntimeException e) {
            report.warn("The RsLib update failed: " + e);
            return new Outcome(Status.FAILED, null, null);
        } finally {
            RsLibJars.cleanStaging(staging(env.pluginsDir()));
        }
    }

    private static Outcome pass(Env env, Reporter report) {
        String installed = env.installedVersion();
        report.step("Looking for RsLib releases in " + RELEASES_REPO + "...");
        ReleaseFeed.Pick newest;
        ReleaseFeed.Pick pick;
        try {
            ReleaseFeed.Scan scan = env.feed().scan(env.github(), RELEASES_REPO, null);
            newest = ReleaseFeed.highest(scan.releases(), TAG_PREFIX);
            pick = env.sameMajorOnly() && Versions.major(installed) >= 0
                    ? ReleaseFeed.highest(scan.releases(), TAG_PREFIX, Versions.major(installed))
                    : newest;
            if (newest == null) {
                report.warn("No release tagged " + TAG_PREFIX + "<version> was found in " + RELEASES_REPO
                        + (scan.capped() ? " (the scan stopped at the page limit)" : "") + ".");
                return new Outcome(Status.FAILED, null, null);
            }
        } catch (IOException e) {
            report.warn("Could not read the releases of " + RELEASES_REPO + ": " + e.getMessage());
            return new Outcome(Status.FAILED, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Status.FAILED, null, null);
        }
        if (pick == null || !Versions.isNewer(pick.version(), installed)) {
            if (Versions.isNewer(newest.version(), installed)) {
                report.step("RsLib " + newest.version() + " is a new major version and is not installed"
                        + " automatically (auto-update.same-major-only is true).");
                return new Outcome(Status.SKIPPED, newest.version(), null);
            }
            report.step("RsLib " + installed + " is up to date (newest release: " + newest.version() + ").");
            return new Outcome(Status.UP_TO_DATE, newest.version(), null);
        }
        String version = pick.version();
        if (pick != newest && Versions.isNewer(newest.version(), version)) {
            report.step("RsLib " + newest.version() + " is a new major version and is not installed"
                    + " automatically (auto-update.same-major-only is true); installing " + version + ".");
        }
        report.step("RsLib " + version + " found (installed: " + installed + ").");
        try {
            return download(env, report, pick, installed);
        } catch (UpdateRefused e) {
            report.warn("RsLib " + version + " was not installed: " + e.getMessage());
        } catch (IOException e) {
            report.warn("RsLib " + version + " could not be installed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Outcome(Status.FAILED, version, null);
    }

    private static Outcome download(Env env, Reporter report, ReleaseFeed.Pick pick, String installed)
            throws UpdateRefused, IOException, InterruptedException {
        String version = pick.version();
        Asset asset = asset(env.github().releaseByTag(RELEASES_REPO, pick.release().tag(), null), version);
        Path current = RsLibJars.resolveInstalled(env.pluginsDir(), env.codeSource());
        report.step("Installed jar: " + current.getFileName() + ".");

        Path staging = staging(env.pluginsDir());
        Files.createDirectories(staging);
        Path part = staging.resolve(asset.name() + ".part");
        report.step("Downloading " + asset.name() + "...");
        long size = env.github().download(asset.url(), part, MAX_BYTES);
        if (size < MIN_BYTES) {
            throw new UpdateRefused("the download is only " + size + " bytes");
        }
        String actual = RsLibJars.sha256(part);
        if (!actual.equals(asset.sha256())) {
            throw new UpdateRefused("the SHA-256 of the download does not match the release digest");
        }
        report.step("SHA-256 checked (" + size + " bytes).");
        RsLibJars.verify(part, version);
        report.step("plugin.yml checked: RsLib " + version + ".");

        RsLibJars.Installed done = RsLibJars.install(part, current, installed, version, env.updateFolder(), env.ops());
        if (done.deferred()) {
            report.step("The current jar is in use; " + done.jar().getFileName() + " waits in "
                    + env.updateFolder().getFileName() + "/ for the next start.");
            return new Outcome(Status.DEFERRED, version, done.jar());
        }
        report.step("Installed " + done.jar().getFileName() + ".");
        return new Outcome(Status.INSTALLED, version, done.jar());
    }

    /**
     * The {@code RsLib-<version>.jar} asset of a release, read only from its {@code assets}
     * array. Refused when it is missing, its address is outside {@link #DOWNLOAD_PREFIX} or it
     * has no {@code sha256:} digest.
     */
    static Asset asset(JsonObject release, String version) throws UpdateRefused {
        String expected = assetName(version);
        JsonElement assets = release.get("assets");
        if (assets == null || !assets.isJsonArray()) {
            throw new UpdateRefused("the release lists no assets");
        }
        for (JsonElement element : assets.getAsJsonArray()) {
            if (!element.isJsonObject() || !expected.equals(GitHubClient.string(element.getAsJsonObject(), "name"))) {
                continue;
            }
            JsonObject asset = element.getAsJsonObject();
            String url = GitHubClient.string(asset, "browser_download_url");
            if (url == null || !url.startsWith(DOWNLOAD_PREFIX) || url.contains("..")) {
                throw new UpdateRefused("the download address of " + expected + " is not under " + DOWNLOAD_PREFIX);
            }
            return new Asset(expected, url, digest(asset, expected));
        }
        throw new UpdateRefused("the release has no asset named " + expected);
    }

    private static String digest(JsonObject asset, String name) throws UpdateRefused {
        String digest = GitHubClient.string(asset, "digest");
        if (digest == null || !digest.startsWith(DIGEST_PREFIX)) {
            throw new UpdateRefused("the release publishes no SHA-256 digest for " + name);
        }
        String hex = digest.substring(DIGEST_PREFIX.length()).toLowerCase(Locale.ROOT);
        if (hex.length() != 64 || !isHex(hex)) {
            throw new UpdateRefused("the SHA-256 digest of " + name + " is malformed");
        }
        return hex;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!HexFormat.isHexDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
