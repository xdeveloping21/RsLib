package com.rs.lib.audit;

import com.rs.lib.RsLibPlugin;
import com.rs.lib.yml.RsYml;

/**
 * The {@code audit:} section of RsLib's config.yml, read on every use so a reload applies.
 *
 * @param keepDays     days an entry is kept; 0 keeps them for ever
 * @param flushSeconds seconds between writes of what was recorded (1 to 300)
 */
record AuditSettings(int keepDays, int flushSeconds) {

    static final int DEFAULT_KEEP_DAYS = 30;
    static final int DEFAULT_FLUSH_SECONDS = 10;
    static final AuditSettings DEFAULTS = new AuditSettings(DEFAULT_KEEP_DAYS, DEFAULT_FLUSH_SECONDS);

    AuditSettings {
        keepDays = Math.max(0, keepDays);
        flushSeconds = Math.min(300, Math.max(1, flushSeconds));
    }

    static AuditSettings current() {
        RsYml config = RsLibPlugin.libConfig();
        if (config == null) {
            return DEFAULTS;
        }
        return new AuditSettings(config.getInt("audit.keep-days", DEFAULT_KEEP_DAYS),
                config.getInt("audit.flush-seconds", DEFAULT_FLUSH_SECONDS));
    }
}
