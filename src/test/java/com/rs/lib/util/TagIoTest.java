package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

/** The key layout; reading and writing item meta needs a running server. */
class TagIoTest {

    /** Trampa: items players hold carry {@code <plugin lower case>:<key lower case>}; it must not change. */
    @Test
    void keyIsPluginAndKeyInLowerCase() {
        NamespacedKey key = TagIo.keyOf("RsVouchers", "Voucher_ID");
        assertEquals("rsvouchers", key.getNamespace());
        assertEquals("voucher_id", key.getKey());
        assertEquals(new NamespacedKey("rsvouchers", "voucher_id"), key);
        assertEquals("rsstaff:staff-item", TagIo.keyOf("RsStaff", "STAFF-ITEM").toString());
    }
}
