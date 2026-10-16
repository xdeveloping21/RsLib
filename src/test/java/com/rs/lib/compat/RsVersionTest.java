package com.rs.lib.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    @Test
    void unreadableVersionWarnsOnceAndAssumesTheNewest() {
        List<String> warnings = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        int[] newest = RsVersion.resolve("git-Paper-experimental", log);
        assertEquals(List.of("Could not read the server version 'git-Paper-experimental'; assuming the newest "
                + "version."), warnings);
        assertTrue(RsVersion.isAtLeast(newest[0], newest[1], newest[2], 21, 11));
        assertTrue(RsVersion.isAtLeast(newest[0], newest[1], newest[2], Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals("unknown", RsVersion.describe(newest[0], newest[1], newest[2]));

        warnings.clear();
        assertArrayEquals(new int[] {1, 21, 11}, RsVersion.resolve("1.21.11-R0.1-SNAPSHOT", log));
        assertEquals("1.21.11", RsVersion.describe(1, 21, 11));
        assertArrayEquals(new int[] {1, 21, 8}, RsVersion.resolve(null, log));
        assertTrue(warnings.isEmpty(), "a readable or absent version does not warn");
    }
}
