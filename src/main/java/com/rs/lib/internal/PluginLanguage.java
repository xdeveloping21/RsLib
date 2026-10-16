package com.rs.lib.internal;

import java.util.function.Supplier;

import com.rs.lib.Rs;
import com.rs.lib.lang.RsLang;
import com.rs.lib.util.Formats;
import com.rs.lib.util.RsFormats;

/**
 * Runs a plugin's render with {@code {key:relative}} in that plugin's language, the way
 * {@code RsLang} and {@code RsYml} do: {@link Formats#enterPlaceholderTexts} before and
 * {@link Formats#exitPlaceholderTexts} in a {@code finally}. Used by every per-plugin render
 * path (menus, items, action lists, rewards) so none of them falls back to RsLib's own language.
 * The words are asked only when such a placeholder is met, so caches of rendered text are not
 * affected: the text that comes out already holds the words.
 */
public final class PluginLanguage {

    private PluginLanguage() {
    }

    /**
     * The words of {@code :relative} in {@code rs}'s language, read when asked (a reload applies
     * at once); the supplier gives null while the plugin has no language module, which means
     * RsLib's own language.
     */
    public static Supplier<Formats.RelativeTexts> of(Rs rs) {
        return () -> {
            RsLang lang = rs.langOrNull();
            return lang == null ? null : RsFormats.texts(lang::rawOrNull);
        };
    }

    /**
     * {@code work} with {@code texts} as the words of {@code :relative} on this thread, the outer
     * words put back afterwards. A null {@code texts} (a host without a plugin) leaves the
     * thread as it is.
     */
    public static <T> T call(Supplier<Formats.RelativeTexts> texts, Supplier<T> work) {
        if (texts == null) {
            return work.get();
        }
        Object previous = Formats.enterPlaceholderTexts(texts);
        try {
            return work.get();
        } finally {
            Formats.exitPlaceholderTexts(previous);
        }
    }
}
