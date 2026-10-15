package com.rs.lib.item;

import java.nio.ByteBuffer;
import java.util.Base64;

import org.bukkit.inventory.ItemStack;

/**
 * Items to bytes and back, keeping amounts above the material's stack size.
 *
 * <p>Paper's {@link ItemStack#serializeAsBytes()} cannot carry an amount above the stack size
 * of the material, so the format is: the real amount as 4 bytes (big-endian), then Paper's
 * bytes for the same item with amount 1. Reading restores the exact amount. The Base64 form is
 * that same byte sequence in standard Base64. This is the format the Rs plugins already keep in
 * their databases and files (auctions, crates, trades, gifts), so it never changes.</p>
 *
 * <p>Reading is lenient: Base64 with line breaks or the URL alphabet is accepted, and bytes
 * written by Paper alone (gzip data, no amount in front) read as that item.</p>
 */
public final class ItemSerializer {

    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;

    private ItemSerializer() {
    }

    /**
     * The bytes of {@code item}.
     *
     * @throws IllegalArgumentException for null, air or an empty stack
     */
    public static byte[] serialize(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            throw new IllegalArgumentException("Cannot serialize an empty item: " + item);
        }
        ItemStack one = item.clone();
        one.setAmount(1);
        return frame(item.getAmount(), one.serializeAsBytes());
    }

    /**
     * The item in {@code bytes}, with its exact amount.
     *
     * @throws IllegalArgumentException when the bytes do not hold an item
     */
    public static ItemStack deserialize(byte[] bytes) {
        Framed framed = unframe(bytes);
        ItemStack item = ItemStack.deserializeBytes(framed.body());
        if (framed.amount() > 0) {
            item.setAmount(framed.amount());
        }
        return item;
    }

    /** {@link #serialize} as standard Base64. */
    public static String serializeBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(serialize(item));
    }

    /** {@link #deserialize} from Base64 (standard, URL-safe or with line breaks). */
    public static ItemStack deserializeBase64(String base64) {
        return deserialize(decode(base64));
    }

    // ------------------------------------------------------------------ framing

    /** The amount and Paper's bytes; {@code amount} is 0 when the bytes had no amount in front. */
    record Framed(int amount, byte[] body) {
    }

    /** {@code amount} as 4 big-endian bytes followed by {@code body}. */
    static byte[] frame(int amount, byte[] body) {
        return ByteBuffer.allocate(4 + body.length).putInt(amount).put(body).array();
    }

    /**
     * Splits framed bytes. Bytes that start with the gzip header are Paper's alone (an amount
     * that large is impossible) and come back whole with amount 0.
     */
    static Framed unframe(byte[] bytes) {
        if (bytes == null || bytes.length < 2) {
            throw new IllegalArgumentException("Not a serialized item: too short");
        }
        if ((bytes[0] & 0xff) == GZIP_MAGIC_0 && (bytes[1] & 0xff) == GZIP_MAGIC_1) {
            return new Framed(0, bytes);
        }
        if (bytes.length < 5) {
            throw new IllegalArgumentException("Not a serialized item: too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int amount = buffer.getInt();
        if (amount <= 0) {
            throw new IllegalArgumentException("Not a serialized item: amount " + amount);
        }
        byte[] body = new byte[bytes.length - 4];
        buffer.get(body);
        return new Framed(amount, body);
    }

    /** Standard Base64; failing that, the URL alphabet when the text uses it, else MIME (line breaks). */
    static byte[] decode(String base64) {
        if (base64 == null) {
            throw new IllegalArgumentException("Not a serialized item: null");
        }
        String text = base64.trim();
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException notStandard) {
            if (text.indexOf('-') >= 0 || text.indexOf('_') >= 0) {
                StringBuilder compact = new StringBuilder(text.length());
                for (int i = 0; i < text.length(); i++) {
                    if (!Character.isWhitespace(text.charAt(i))) {
                        compact.append(text.charAt(i));
                    }
                }
                return Base64.getUrlDecoder().decode(compact.toString());
            }
            return Base64.getMimeDecoder().decode(text);
        }
    }
}
