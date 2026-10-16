package com.rs.lib.notice;

import java.util.Objects;

/**
 * A kind of notice a plugin sends, declared with {@link Notices#category(String)}.
 *
 * <p>Without a summary, the notices of the category waiting at join are shown one by one (the
 * oldest five, then {@code rslib.notices.more}). With {@link #summary(String)} they are shown
 * as that one message instead, with these placeholders:</p>
 * <ul>
 *   <li>{@code {count}}: how many notices of the category were waiting.</li>
 *   <li>{@code {sum_<key>}}: the sum of the value {@code <key>} over those notices, for values
 *   that are numbers ({@code {sum_price:grouped}} writes {@code 4,500}).</li>
 * </ul>
 */
public final class NoticeCategory {

    private final String id;
    private volatile String summaryKey;

    NoticeCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * Shows the waiting notices of this category at join as the message {@code key} of the
     * plugin's language file, for example
     * {@code "&7While you were away &f{count}&7 items sold for &a${sum_price:grouped}"}.
     */
    public NoticeCategory summary(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("The summary key of notice category '" + id + "' is blank");
        }
        this.summaryKey = key;
        return this;
    }

    /** The summary message key, or null when the notices are shown one by one. */
    public String summaryKey() {
        return summaryKey;
    }
}
