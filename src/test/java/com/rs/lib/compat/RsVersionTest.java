package com.rs.lib.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RsVersionTest {

    @Test
    void twoDigitPatchIsReadWhole() {
        int[] parsed = RsVersion.parse("1.21.11-R0.1-SNAPSHOT");
        assertArrayEquals(new int[] {1, 21, 11}, parsed);
        assertTrue(RsVersion.isAtLeast(parsed[0], parsed[1], parsed[2], 21, 8));
        assertTrue(RsVersion.isAtLeast(parsed[0], parsed[1], parsed[2], 21, 11));
        assertFalse(RsVersion.isAtLeast(parsed[0], parsed[1], parsed[2], 21, 12));
    }

    @Test
    void yearBasedAndMissingVersions() {
        assertArrayEquals(new int[] {26, 1, 0}, RsVersion.parse("26.1"));
        assertTrue(RsVersion.isAtLeast(26, 1, 0, 21, 8));
        assertNull(RsVersion.parse("unknown"));
    }
}
