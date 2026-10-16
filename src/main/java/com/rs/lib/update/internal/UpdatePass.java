package com.rs.lib.update.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.rs.lib.update.Versions;

/**
 * One self-update pass of RsLib, without Bukkit: ask rsdevelopment.dev for the current RsLib
 * ({@code /v1/releases/rslib}), download it from {@code /v1/releases/rslib/download}, check it
 * and install it for the next start. Every step is told to a {@link Reporter}; nothing here
 * throws.
 *
 * <p>The site keeps one jar per product, so the pass only ever looks at that one version. The
 * download comes from that fixed address (no redirects), must weigh between 16 KiB and 64 MiB
 * and exactly the size the site announced, match the site's SHA-256 and carry the plugin.yml of
 * RsLib at exactly the site's version. Anything else is refused with a warning. With more than
 * one RsLib jar in {@code plugins/} the pass refuses before asking the site anything.</p>
 */
public final class UpdatePass {

    /** RsLib's product id on rsdevelopment.dev. */
    public static final String PRODUCT = "rslib";
    static final long MAX_BYTES = 64L << 20;
    static final long MIN_BYTES = 16L << 10;
    /** Folder under {@code plugins/} where a download waits while it is checked. */
    static final String STAGING = ".rslib-update";

    private UpdatePass() {
    }

    /** Where the steps of a pass go. */
    public interface Reporter {

        /** Progress, for whoever forced the pass. */
        void step(String message);

        /** Something went wrong or was refused. */
        void warn(String message);

        /** How the pass ended, the one line whoever forced it reads. */
        default void result(String message) {
            step(message);
        }

        /** Something the console is told on every pass, forced or not. */
        default void notice(String message) {
            step(message);
        }

        /** A refusal the console gets word for word, without the updater's tag. */
        default void alert(String message) {
            warn(message);
        }
    }

    /**
     * What a pass works with.
     *
     * @param installedVersion the running RsLib version
     * @param pluginsDir       the {@code plugins/} folder
     * @param codeSource       the file the classes were loaded from, or null
     * @param updateFolder     Bukkit's update folder, used when the jar cannot be swapped now
     * @param sameMajorOnly    refuse a version with another major number
     * @param site             the client of rsdevelopment.dev
     * @param cache            the release cache shared with the update checkers
     */
    public record Env(String installedVersion, Path pluginsDir, Path codeSource, Path updateFolder,
                      boolean sameMajorOnly, SiteClient site, ReleaseCache cache, RsLibJars.FileOps ops) {
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

    /** The staging folder under {@code pluginsDir}. */
    public static Path staging(Path pluginsDir) {
        return pluginsDir.resolve(STAGING);
    }

    /** {@code RsLib-<version>.jar}. */
    static String jarName(String version) {
        return RsLibJars.NAME + "-" + version + ".jar";
    }

    private static String installedLine(String version) {
        return "RsLib " + version + " installed on disk. Restart the server to activate it.";
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
        List<Path> jars;
        try {
            jars = RsLibJars.rsLibJars(env.pluginsDir());
        } catch (IOException e) {
            jars = List.of();
        }
        if (jars.size() > 1) {
            // The server loads one of them at random: touching any could leave the wrong one.
            report.alert(RsLibJars.severalJars(jars));
            return new Outcome(Status.FAILED, null, null);
        }
        report.step("Asking rsdevelopment.dev for the current RsLib...");
        SiteClient.Info info;
        try {
            info = env.cache().get(env.site(), PRODUCT);
        } catch (IOException e) {
            report.warn("Could not read the current RsLib from rsdevelopment.dev: " + e.getMessage());
            return new Outcome(Status.FAILED, null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Status.FAILED, null, null);
        }
        String version = info.version();
        if (!Versions.isNewer(version, installed)) {
            report.step("RsLib " + installed + " is up to date (rsdevelopment.dev offers " + version + ").");
            report.result("RsLib is up to date (" + installed + ").");
            return new Outcome(Status.UP_TO_DATE, version, null);
        }
        if (env.sameMajorOnly() && Versions.major(version) != Versions.major(installed)) {
            report.notice("RsLib " + version + " is a new major version and is not installed"
                    + " automatically (auto-update.same-major-only is true); install it by hand.");
            return new Outcome(Status.SKIPPED, version, null);
        }
        report.step("RsLib " + version + " found (installed: " + installed + ").");
        try {
            return download(env, report, info, installed);
        } catch (UpdateRefused e) {
            report.warn("RsLib " + version + " was not installed: " + e.getMessage());
        } catch (IOException e) {
            report.warn("RsLib " + version + " could not be installed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Outcome(Status.FAILED, version, null);
    }

    private static Outcome download(Env env, Reporter report, SiteClient.Info info, String installed)
            throws UpdateRefused, IOException, InterruptedException {
        String version = info.version();
        Path current = RsLibJars.resolveInstalled(env.pluginsDir(), env.codeSource());
        report.step("Installed jar: " + current.getFileName() + ".");

        Path staging = staging(env.pluginsDir());
        Files.createDirectories(staging);
        String name = jarName(version);
        Path part = staging.resolve(name + ".part");
        report.step("Downloading " + name + " from rsdevelopment.dev...");
        long size = env.site().download(PRODUCT, part, MAX_BYTES);
        if (size < MIN_BYTES) {
            throw new UpdateRefused("the download is only " + size + " bytes");
        }
        if (size != info.size()) {
            throw new UpdateRefused("the download is " + size + " bytes, not the " + info.size()
                    + " the site announced");
        }
        if (!RsLibJars.sha256(part).equals(info.sha256())) {
            throw new UpdateRefused("the SHA-256 of the download does not match the one the site published");
        }
        report.step("SHA-256 checked (" + size + " bytes).");
        RsLibJars.verify(part, version);
        report.step("plugin.yml checked: RsLib " + version + ".");

        RsLibJars.Installed done = RsLibJars.install(part, current, installed, version, env.updateFolder(), env.ops());
        if (done.deferred()) {
            report.step("The current jar is in use; " + done.jar().getFileName() + " waits in "
                    + env.updateFolder().getFileName() + "/ for the next start.");
            report.result(installedLine(version));
            return new Outcome(Status.DEFERRED, version, done.jar());
        }
        report.step("Installed " + done.jar().getFileName() + ".");
        report.result(installedLine(version));
        return new Outcome(Status.INSTALLED, version, done.jar());
    }
}
