package com.rs.lib.yml.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Checks the extra folders a plugin registers for a module (extra message folders for lang,
 * extra menu folders for guis), relative to the plugin's data folder.
 */
public final class ResourceFolders {

    private ResourceFolders() {
    }

    /**
     * Normalizes a folder path: trims it, turns {@code \} into {@code /}, drops empty and
     * {@code .} segments and resolves {@code ..} against the previous segment.
     *
     * @param folder   the path as the plugin wrote it
     * @param reserved the module's own folder ({@code lang} or {@code guis}), compared ignoring case
     * @param kind     what the folder holds, for the error message (for example "message")
     * @return the normalized path, such as {@code addons/rewards}
     * @throws IllegalArgumentException when the path is empty, contains {@code :} (absolute or a
     *                                  drive), leaves the data folder, or is the reserved folder
     */
    public static String normalize(String folder, String reserved, String kind) {
        String what = kind == null || kind.isBlank() ? "" : kind.trim() + " ";
        if (folder == null) {
            throw new IllegalArgumentException("The " + what + "folder is null");
        }
        String raw = folder.trim();
        if (raw.indexOf(':') >= 0) {
            throw new IllegalArgumentException("The " + what + "folder '" + folder
                    + "' must be relative to the plugin folder (no ':')");
        }
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : raw.replace('\\', '/').split("/")) {
            String part = segment.trim();
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("The " + what + "folder '" + folder
                            + "' points outside the plugin folder");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(part);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("The " + what + "folder '" + folder
                    + "' is empty; name a folder inside the plugin folder");
        }
        String normalized = String.join("/", segments);
        if (reserved != null && normalized.toLowerCase(Locale.ROOT).equals(reserved.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The " + what + "folder '" + folder
                    + "' is reserved for the plugin's own files; use another folder");
        }
        return normalized;
    }
}
