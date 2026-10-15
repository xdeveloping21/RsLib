package com.rs.lib.update;

import java.util.regex.Pattern;

import com.rs.lib.hook.SemverComparator;

/**
 * Version strings as release tags and plugin metas write them.
 *
 * <p>A version is accepted when it looks like SemVer: dotted numbers ({@code 1}, {@code 1.2},
 * {@code 1.2.3.4}), an optional pre-release ({@code -beta.2}) and optional build metadata
 * ({@code +abc}); one leading {@code v} is ignored. Missing parts count as 0, a pre-release is
 * older than its release and build metadata is ignored (ordering by {@link SemverComparator}).
 * Anything else is unparsable and never counts as newer.</p>
 */
public final class Versions {

    private static final Pattern VERSION = Pattern.compile(
            "\\d+(\\.\\d+)*(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?");

    private Versions() {
    }

    /** {@code version} trimmed and without one leading {@code v}; null stays null. */
    public static String normalize(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim();
        return v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V') ? v.substring(1) : v;
    }

    /** True when {@code version} (after {@link #normalize}) can be compared. */
    public static boolean isValid(String version) {
        return matches(normalize(version));
    }

    private static boolean matches(String normalized) {
        return normalized != null && VERSION.matcher(normalized).matches();
    }

    /**
     * Orders two versions: negative, zero or positive like a comparator. Unparsable versions are
     * compared leniently; use {@link #isNewer} to decide on an update.
     */
    public static int compare(String a, String b) {
        return SemverComparator.compareVersions(normalize(a), normalize(b));
    }

    /** True only when both versions are valid and {@code candidate} is strictly newer. */
    public static boolean isNewer(String candidate, String installed) {
        return isValid(candidate) && isValid(installed) && compare(candidate, installed) > 0;
    }

    /** The first number of a valid version ({@code 2} for {@code 2.1.0}), or -1. */
    public static int major(String version) {
        if (!isValid(version)) {
            return -1;
        }
        String v = normalize(version);
        int end = 0;
        while (end < v.length() && Character.isDigit(v.charAt(end))) {
            end++;
        }
        try {
            return Integer.parseInt(v.substring(0, end));
        } catch (NumberFormatException tooLong) {
            return -1;
        }
    }

    /**
     * The version a tag names: without {@code prefix} (when given) and one leading {@code v}.
     * Null when the tag does not start with the prefix or what is left is not a valid version.
     */
    public static String fromTag(String tag, String prefix) {
        if (tag == null) {
            return null;
        }
        String rest = tag.trim();
        if (prefix != null && !prefix.isEmpty()) {
            if (!rest.startsWith(prefix)) {
                return null;
            }
            rest = rest.substring(prefix.length());
        }
        String version = normalize(rest);
        return matches(version) ? version : null;
    }
}
