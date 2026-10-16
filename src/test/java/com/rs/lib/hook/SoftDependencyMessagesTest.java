package com.rs.lib.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The console lines of a hook that stays off. */
class SoftDependencyMessagesTest {

    @Test
    void tooOldAndMissingClassLines() {
        assertEquals("Hook 'Vault' requires version >= 1.7 (installed: 1.6.2); hook disabled",
                SoftDependency.tooOld("Vault", "1.7", "1.6.2"));
        assertEquals("Hook 'Vault': required class net.milkbowl.vault.economy.Economy not found; hook disabled",
                SoftDependency.missingClass("Vault", "net.milkbowl.vault.economy.Economy"));
    }
}
