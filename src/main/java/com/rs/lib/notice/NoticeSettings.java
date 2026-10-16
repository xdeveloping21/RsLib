package com.rs.lib.notice;

import com.rs.lib.RsLibPlugin;
import com.rs.lib.yml.RsYml;

/**
 * The {@code notices:} section of RsLib's config.yml, read on every use so a reload applies.
 *
 * @param keepDays         days an unread notice is kept (at least 1)
 * @param maxPerPlayer     most unread notices one player can have per plugin (at least 1)
 * @param joinDelayTicks   ticks between a join and the waiting notices
 */
record NoticeSettings(int keepDays, int maxPerPlayer, long joinDelayTicks) {

    static final int DEFAULT_KEEP_DAYS = 30;
    static final int DEFAULT_MAX_PER_PLAYER = 500;
    static final int DEFAULT_JOIN_DELAY_SECONDS = 3;
    static final NoticeSettings DEFAULTS = new NoticeSettings(DEFAULT_KEEP_DAYS, DEFAULT_MAX_PER_PLAYER,
            DEFAULT_JOIN_DELAY_SECONDS * 20L);

    NoticeSettings {
        keepDays = Math.max(1, keepDays);
        maxPerPlayer = Math.max(1, maxPerPlayer);
        joinDelayTicks = Math.max(1L, joinDelayTicks);
    }

    long keepMillis() {
        return keepDays * 86_400_000L;
    }

    /** The current settings; the defaults while RsLib's config.yml is not loaded. */
    static NoticeSettings current() {
        RsYml config = RsLibPlugin.libConfig();
        if (config == null) {
            return DEFAULTS;
        }
        return new NoticeSettings(
                config.getInt("notices.keep-days", DEFAULT_KEEP_DAYS),
                config.getInt("notices.max-per-player", DEFAULT_MAX_PER_PLAYER),
                Math.min(600, Math.max(0, config.getInt("notices.join-delay-seconds", DEFAULT_JOIN_DELAY_SECONDS))) * 20L);
    }
}
