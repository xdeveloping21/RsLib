package com.rs.lib.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

/**
 * Player heads with a custom texture.
 *
 * <p>Accepted texture inputs ({@link #extractTextureValue(String)}):</p>
 * <ul>
 *   <li>the Base64 texture value itself, starting with {@code eyJ};</li>
 *   <li>a skin URL ({@code http://textures.minecraft.net/texture/<hash>}), wrapped as
 *       {@code {"textures":{"SKIN":{"url":"..."}}}} and Base64-encoded;</li>
 *   <li>either of those behind the prefixes {@code texture-}, {@code texture:}, {@code base64-},
 *       {@code base64:} or {@code basehead-}, in any case and nested
 *       ({@code basehead-texture:eyJ...}).</li>
 * </ul>
 *
 * <p>A texture is applied as a Paper {@link PlayerProfile} without a name, whose UUID is derived
 * from the texture value ({@link UUID#nameUUIDFromBytes(byte[])} of its UTF-8 bytes): equal
 * textures get equal profiles, so heads stack and the client caches the skin once. The last
 * {@value #CACHE_SIZE} profiles are kept. When a texture cannot be applied the head stays a
 * plain player head and the problem is reported once. Item methods belong on the thread that
 * owns the item; {@link #extractTextureValue(String)} is pure.</p>
 */
public final class HeadUtil {

    private static final Logger LOG = Logger.getLogger("RsLib");
    private static final int CACHE_SIZE = 512;
    private static final String[] PREFIXES = {"texture-", "texture:", "base64-", "base64:", "basehead-"};
    private static final String TEXTURES = "textures";

    /** Texture value to profile, least recently used first; guarded by itself. */
    private static final Map<String, PlayerProfile> PROFILES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PlayerProfile> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private HeadUtil() {
    }

    /** A player head with the texture in {@code texture} (any accepted form); a plain head when it has none. */
    public static ItemStack fromBase64(String texture) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta && applyBase64(meta, texture)) {
            head.setItemMeta(meta);
        }
        return head;
    }

    /** Same as {@link #fromBase64(String)}, named after the texture "value" sites hand out. */
    public static ItemStack fromValue(String texture) {
        return fromBase64(texture);
    }

    /** A player head showing {@code owner}'s skin; a plain head for null. */
    public static ItemStack fromPlayer(OfflinePlayer owner) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (owner != null && head.getItemMeta() instanceof SkullMeta meta) {
            applyOwner(meta, owner);
            head.setItemMeta(meta);
        }
        return head;
    }

    /** Makes {@code meta} show {@code owner}'s skin (null clears it). */
    public static void applyOwner(SkullMeta meta, OfflinePlayer owner) {
        meta.setOwningPlayer(owner);
    }

    /** Sets {@code profile} on {@code meta} (null clears it). */
    public static void applyProfile(SkullMeta meta, PlayerProfile profile) {
        meta.setPlayerProfile(profile);
    }

    /**
     * Applies the texture in {@code texture} (any accepted form) to {@code meta}.
     *
     * @return true when applied; false when the text holds no texture or the server refused it
     *         (reported once per text), leaving {@code meta} as it was
     */
    public static boolean applyBase64(SkullMeta meta, String texture) {
        String value = extractTextureValue(texture);
        if (value == null) {
            if (texture != null && !texture.isBlank()) {
                warnOnce("'" + shorten(texture) + "' is not a head texture (expected a Base64 value, a skin URL"
                        + " or one of the prefixes texture-, base64-, basehead-); using a plain head.");
            }
            return false;
        }
        try {
            meta.setPlayerProfile(profile(value));
            return true;
        } catch (RuntimeException refused) {
            warnOnce("Could not apply the head texture '" + shorten(value) + "': " + refused);
            return false;
        }
    }

    /**
     * The Base64 texture value in {@code input}, after removing the accepted prefixes and
     * whitespace and encoding a skin URL; null when it holds none.
     */
    public static String extractTextureValue(String input) {
        if (input == null) {
            return null;
        }
        String text = input.trim();
        boolean unwrapped = true;
        while (unwrapped) {
            unwrapped = false;
            for (String prefix : PREFIXES) {
                if (text.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    text = text.substring(prefix.length()).trim();
                    unwrapped = true;
                    break;
                }
            }
        }
        if (text.regionMatches(true, 0, "http://", 0, 7) || text.regionMatches(true, 0, "https://", 0, 8)) {
            return fromUrl(text);
        }
        String compact = withoutWhitespace(text);
        return compact.startsWith("eyJ") ? compact : null;
    }

    /** Drops every cached profile. */
    public static void clearCache() {
        synchronized (PROFILES) {
            PROFILES.clear();
        }
    }

    /** Lets every problem be reported again. */
    public static void forgetWarnings() {
        WARNED.clear();
    }

    /** The UUID a texture value's profile gets. */
    static UUID profileId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PlayerProfile profile(String value) {
        synchronized (PROFILES) {
            PlayerProfile cached = PROFILES.get(value);
            if (cached != null) {
                return cached;
            }
        }
        PlayerProfile profile = Bukkit.createProfile(profileId(value), null);
        profile.setProperty(new ProfileProperty(TEXTURES, value));
        synchronized (PROFILES) {
            PROFILES.put(value, profile);
        }
        return profile;
    }

    /** The Base64 texture value for a skin URL, or null when the URL cannot be one. */
    private static String fromUrl(String url) {
        int schemeEnd = url.indexOf("://") + 3;
        if (schemeEnd >= url.length()) {
            return null;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c <= ' ' || c == '"' || c == '\\' || c >= 0x7F) {
                return null;
            }
        }
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String withoutWhitespace(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (out == null) {
                    out = new StringBuilder(text.length()).append(text, 0, i);
                }
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }

    private static String shorten(String text) {
        String trimmed = text.trim();
        return trimmed.length() <= 48 ? trimmed : trimmed.substring(0, 45) + "...";
    }

    private static void warnOnce(String message) {
        if (WARNED.add(message)) {
            LOG.warning("[RsLib] " + message);
        }
    }
}
