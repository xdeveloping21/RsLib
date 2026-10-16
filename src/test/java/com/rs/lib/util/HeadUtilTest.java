package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** Pure texture parsing; applying a profile to an item needs a running server. */
class HeadUtilTest {

    private static final String VALUE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWJjIn19fQ==";

    @Test
    void rawValueIsKept() {
        assertEquals(VALUE, HeadUtil.extractTextureValue(VALUE));
        assertEquals(VALUE, HeadUtil.extractTextureValue("  " + VALUE + "\n"));
    }

    /** Trampa: configs use every one of these prefixes, in any case. */
    @Test
    void everyPrefixIsAcceptedInAnyCase() {
        for (String prefix : new String[] {"texture-", "texture:", "base64-", "base64:", "basehead-",
                "TEXTURE-", "Texture:", "BASE64-", "Base64:", "BaseHead-"}) {
            assertEquals(VALUE, HeadUtil.extractTextureValue(prefix + VALUE), prefix);
        }
    }

    @Test
    void prefixesUnwrapRecursively() {
        assertEquals(VALUE, HeadUtil.extractTextureValue("basehead-texture:" + VALUE));
        assertEquals(VALUE, HeadUtil.extractTextureValue("base64: texture- " + VALUE));
    }

    @Test
    void skinUrlIsWrappedAndEncoded() {
        String url = "http://textures.minecraft.net/texture/abc";
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        String expected = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, HeadUtil.extractTextureValue(url));
        assertEquals(expected, HeadUtil.extractTextureValue("texture-" + url));
        assertEquals(VALUE, expected, "the sample value is this URL's encoding");
        String https = "https://textures.minecraft.net/texture/abc";
        assertEquals(Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"" + https + "\"}}}")
                .getBytes(StandardCharsets.UTF_8)), HeadUtil.extractTextureValue(https));
    }

    @Test
    void whitespaceInsideTheValueIsDropped() {
        String folded = VALUE.substring(0, 20) + "\n  " + VALUE.substring(20);
        assertEquals(VALUE, HeadUtil.extractTextureValue(folded));
    }

    @Test
    void anythingElseIsNotATexture() {
        assertNull(HeadUtil.extractTextureValue(null));
        assertNull(HeadUtil.extractTextureValue(""));
        assertNull(HeadUtil.extractTextureValue("Notch"));
        assertNull(HeadUtil.extractTextureValue("texture-"));
        assertNull(HeadUtil.extractTextureValue("abc" + VALUE));
        assertNull(HeadUtil.extractTextureValue("http://"));
        assertNull(HeadUtil.extractTextureValue("http://example.com/a\"b"));
        assertNull(HeadUtil.extractTextureValue("ftp://textures.minecraft.net/texture/abc"));
    }

    @Test
    void profileIdIsDerivedFromTheTexture() {
        assertEquals(HeadUtil.profileId(VALUE), HeadUtil.profileId(VALUE));
        assertNotEquals(HeadUtil.profileId(VALUE), HeadUtil.profileId(VALUE + "x"));
        assertEquals(3, HeadUtil.profileId(VALUE).version());
    }

    /** fromValue is the alias of fromBase64 that SnLib's examples use. */
    @Test
    void fromValueIsAPublicAliasOfFromBase64() throws NoSuchMethodException {
        Method alias = HeadUtil.class.getMethod("fromValue", String.class);
        assertTrue(Modifier.isStatic(alias.getModifiers()));
        assertEquals(ItemStack.class, alias.getReturnType());
        assertEquals(HeadUtil.class.getMethod("fromBase64", String.class).getReturnType(), alias.getReturnType());
    }
}
