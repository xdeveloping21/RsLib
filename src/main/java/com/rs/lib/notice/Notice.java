package com.rs.lib.notice;

import java.util.List;

import com.rs.lib.Ph;

/**
 * One message left for a player: its category, the message key in the plugin's language file,
 * the placeholder values and when it was sent (epoch milliseconds).
 *
 * @param id       row id in {@code rslib_notices}; 0 before it is stored
 * @param category the category declared with {@link Notices#category(String)}
 * @param message  the message key, rendered when it is shown
 * @param values   the placeholder values, literal ones kept literal
 * @param sentAt   when it was sent
 */
record Notice(long id, String category, String message, List<Ph> values, long sentAt) {

    Notice {
        values = List.copyOf(values);
    }

    Ph[] phs() {
        return values.toArray(new Ph[0]);
    }
}
