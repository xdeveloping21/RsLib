package com.rs.lib.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * The stored item format. Paper's own bytes need a server, so these cover the frame around
 * them; {@code serialize}/{@code deserialize} with a real ItemStack are checked on a server.
 */
class ItemSerializerTest {

    private static final byte[] BODY = "paper-bytes".getBytes(StandardCharsets.UTF_8);

    /** Trampa 1: 4 big-endian bytes of the real amount, then Paper's bytes of the amount-1 copy. */
    @Test
    void frameIsBigEndianAmountThenBody() {
        byte[] framed = ItemSerializer.frame(300, BODY);
        assertEquals(4 + BODY.length, framed.length);
        assertArrayEquals(new byte[] {0, 0, 1, 44}, Arrays.copyOf(framed, 4));
        assertArrayEquals(BODY, Arrays.copyOfRange(framed, 4, framed.length));
    }

    @Test
    void unframeGivesBackTheExactAmount() {
        for (int amount : new int[] {1, 64, 65, 99, 300, 4096}) {
            ItemSerializer.Framed back = ItemSerializer.unframe(ItemSerializer.frame(amount, BODY));
            assertEquals(amount, back.amount());
            assertArrayEquals(BODY, back.body());
        }
    }

    /** Trampa 1: bytes stored by Paper alone (gzip header first) still read, amount left to Paper. */
    @Test
    void gzipBytesWithoutFrameAreReadWhole() {
        byte[] gzip = {(byte) 0x1f, (byte) 0x8b, 8, 0, 1, 2, 3};
        ItemSerializer.Framed back = ItemSerializer.unframe(gzip);
        assertEquals(0, back.amount());
        assertArrayEquals(gzip, back.body());
    }

    @Test
    void brokenBytesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ItemSerializer.unframe(null));
        assertThrows(IllegalArgumentException.class, () -> ItemSerializer.unframe(new byte[] {0, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> ItemSerializer.unframe(new byte[] {0, 0, 0, 0, 5}));
        assertThrows(IllegalArgumentException.class, () -> ItemSerializer.unframe(new byte[] {-1, 0, 0, 0, 5}));
    }

    /** Stored texts are standard Base64; URL-safe and wrapped (MIME) texts are read too. */
    @Test
    void base64IsReadInEveryAlphabet() {
        byte[] bytes = new byte[300];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 7);
        }
        assertArrayEquals(bytes, ItemSerializer.decode(Base64.getEncoder().encodeToString(bytes)));
        assertArrayEquals(bytes, ItemSerializer.decode("  " + Base64.getEncoder().encodeToString(bytes) + "\n"));
        assertArrayEquals(bytes, ItemSerializer.decode(Base64.getUrlEncoder().encodeToString(bytes)));
        assertArrayEquals(bytes, ItemSerializer.decode(Base64.getMimeEncoder().encodeToString(bytes)));
        assertThrows(IllegalArgumentException.class, () -> ItemSerializer.decode(null));
    }

    /** Trampa 1: the persistent data keys items already carry. */
    @Test
    void stampKeysKeepTheirNames() {
        assertEquals("snlib_item_id", ItemRegistry.TAG_KEY);
        assertEquals("snlib_locked", ItemRegistry.TAG_LOCKED);
        assertEquals("snlib_no_drop", ItemRegistry.TAG_NO_DROP);
        assertEquals("snlib_no_manual_equip", ItemRegistry.TAG_NO_MANUAL_EQUIP);
        assertEquals("snlib_keep_on_death", ItemRegistry.TAG_KEEP_ON_DEATH);
        assertEquals("snlib_obtain_via", ItemRegistry.TAG_OBTAIN_VIA);
        assertEquals("snlib_durability", ItemRegistry.TAG_DURABILITY);
        assertEquals("snlib_durability_line", ItemRegistry.TAG_DURABILITY_LINE);
        assertEquals("snlib_recipe_", ItemRegistry.RECIPE_PREFIX);
        assertEquals("snlib", ItemCompat.MODIFIER_NAMESPACE);
    }
}
