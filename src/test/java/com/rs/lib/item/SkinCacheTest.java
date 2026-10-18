package com.rs.lib.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SkinCacheTest {

    /** The id an offline-mode server gives a player: UUID v3 of "OfflinePlayer:" + name. */
    private static UUID offlineId(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void offlineModeIdsAreNeverLookedUp() {
        assertTrue(SkinCache.offlineModeId(offlineId("Steve")));
        assertTrue(SkinCache.offlineModeId(UUID.fromString("0587f143-9068-3a1d-95b6-e86d93e6ce62")),
                "the id from the reported warning is an offline-mode one");
    }

    @Test
    void onlineAccountIdsAreLookedUp() {
        // Notch's account id (version 4), and a random one.
        assertFalse(SkinCache.offlineModeId(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")));
        assertFalse(SkinCache.offlineModeId(UUID.randomUUID()));
    }

    @Test
    void onlyFullUuidsAreReadAsIds() {
        assertEquals(offlineId("Alex"), SkinCache.uuidOrNull(offlineId("Alex").toString()));
        assertNull(SkinCache.uuidOrNull("Alex"));
        assertNull(SkinCache.uuidOrNull("0587f1439068-3a1d-95b6-e86d93e6ce62xxx"));
    }
}
