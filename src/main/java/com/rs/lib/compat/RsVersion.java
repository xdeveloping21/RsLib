package com.rs.lib.compat;

import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;

/**
 * The server's Minecraft version and whether it runs Folia, read once.
 *
 * <p>The version comes from {@link Bukkit#getBukkitVersion()}, which looks the same on every
 * fork ({@code 1.21.8-R0.1-SNAPSHOT}). The year-based numbering Mojang uses from 26.1 on
 * ({@code 26.1}, {@code 26.2.build...}) is newer than any 1.x and passes every check. A version
 * string without a {@code MAJOR.MINOR} pair logs one warning and counts as the newest version,
 * so every check passes; RsLib never refuses to run.</p>
 */
public final class RsVersion {

    public static final int MAJOR;
    public static final int MINOR;
    public static final int PATCH;

    /** Stands for an unreadable version: newer than anything, so every check passes. */
    static final int[] NEWEST = {Integer.MAX_VALUE, 0, 0};

    private static final boolean FOLIA;

    static {
        int[] parsed = resolve(safeBukkitVersion(), Logger.getLogger("RsLib"));
        MAJOR = parsed[0];
        MINOR = parsed[1];
        PATCH = parsed[2];
        FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    private RsVersion() {
    }

    /** True on {@code 1.<minor>} or newer (year-based versions included). */
    public static boolean supports(int minor) {
        return supports(minor, 0);
    }

    /** True on {@code 1.<minor>.<patch>} or newer (year-based versions included). */
    public static boolean supports(int minor, int patch) {
        return isAtLeast(MAJOR, MINOR, PATCH, minor, patch);
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /** "1.21.8" or "26.1.0"; "unknown" when the server version could not be read. */
    public static String describe() {
        return describe(MAJOR, MINOR, PATCH);
    }

    static String describe(int major, int minor, int patch) {
        return major == NEWEST[0] ? "unknown" : major + "." + minor + "." + patch;
    }

    /**
     * The version to run with: the parsed one, {@link #NEWEST} (with one warning) when the string
     * has no {@code MAJOR.MINOR} pair, or 1.21.8 without a server at all (unit tests).
     */
    static int[] resolve(String version, Logger log) {
        if (version == null) {
            return new int[] {1, 21, 8};
        }
        int[] parsed = parse(version);
        if (parsed != null) {
            return parsed;
        }
        log.warning("Could not read the server version '" + version + "'; assuming the newest version.");
        return NEWEST.clone();
    }

    static boolean isAtLeast(int major, int minor, int patch, int wantedMinor, int wantedPatch) {
        if (major > 1) {
            return true;
        }
        if (major < 1) {
            return false;
        }
        return minor != wantedMinor ? minor > wantedMinor : patch >= wantedPatch;
    }

    /** {major, minor, patch} from the first {@code A.B[.C]} group, or null. */
    static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(version);
        if (!m.find()) {
            return null;
        }
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            return new int[] {major, minor, patch};
        } catch (NumberFormatException tooLong) {
            return null;
        }
    }

    private static String safeBukkitVersion() {
        try {
            return Bukkit.getBukkitVersion();
        } catch (RuntimeException | LinkageError noServer) {
            return null;
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, RsVersion.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
