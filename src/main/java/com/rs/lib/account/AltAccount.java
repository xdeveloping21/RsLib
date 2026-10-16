package com.rs.lib.account;

import java.util.Objects;
import java.util.UUID;

/**
 * An account that joined from an address another player also used, from
 * {@link Accounts#alts(UUID)}.
 *
 * @param uuid     the account
 * @param name     its name when it last joined
 * @param lastSeen when it last joined from one of the shared addresses (epoch milliseconds)
 */
public record AltAccount(UUID uuid, String name, long lastSeen) {

    public AltAccount {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
