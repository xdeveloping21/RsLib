package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Spec parsing and id normalization; looking ids up in the sound registry needs a running server. */
class SoundUtilTest {

    @Test
    void readsIdVolumeAndPitch() {
        SoundUtil.Spec spec = SoundUtil.parse("ENTITY_PLAYER_LEVELUP 0.5 1.2", null);
        assertEquals("ENTITY_PLAYER_LEVELUP", spec.id());
        assertEquals(0.5f, spec.volume());
        assertEquals(1.2f, spec.pitch());
        SoundUtil.Spec bare = SoundUtil.parse("  minecraft:entity.player.levelup  ", null);
        assertEquals("minecraft:entity.player.levelup", bare.id());
        assertEquals(1f, bare.volume());
        assertEquals(1f, bare.pitch());
        assertEquals(2f, SoundUtil.parse("UI_BUTTON_CLICK\t2", null).volume());
    }

    /** Null, empty and {@code none} are silence on purpose, not errors. */
    @Test
    void silenceIsIntentional() {
        List<String> warnings = new ArrayList<>();
        assertNull(SoundUtil.parse(null, warnings::add));
        assertNull(SoundUtil.parse("", warnings::add));
        assertNull(SoundUtil.parse("   ", warnings::add));
        assertNull(SoundUtil.parse("none", warnings::add));
        assertNull(SoundUtil.parse("NONE 1 1", warnings::add));
        assertTrue(warnings.isEmpty());
        assertTrue(SoundUtil.resolves(null));
        assertTrue(SoundUtil.resolves(""));
        assertTrue(SoundUtil.resolves("None"));
    }

    @Test
    void badNumbersReadAsOneWithAWarning() {
        List<String> warnings = new ArrayList<>();
        SoundUtil.Spec spec = SoundUtil.parse("BLOCK_NOTE_BLOCK_PLING loud -2", warnings::add);
        assertEquals(1f, spec.volume());
        assertEquals(1f, spec.pitch());
        assertEquals(List.of("Invalid volume/pitch in 'BLOCK_NOTE_BLOCK_PLING loud -2'; using 1.0"), warnings);
        assertEquals(0.5f, SoundUtil.parse("X 0.5 x", warnings::add).volume());
        assertEquals(1f, SoundUtil.parse("X NaN", warnings::add).volume());
        assertEquals(1f, SoundUtil.parse("X Infinity", warnings::add).volume());
    }

    @Test
    void unknownSoundLineNamesTheNamespaceRule() {
        assertEquals("Invalid sound 'ENTITY_PLAYER_LEVELUPP': not resolved by enum nor by Registry.SOUNDS, and it"
                + " carries no namespace, so it cannot be a resource pack sound either; ignored. A custom pack sound"
                + " must be written as 'namespace:id'", SoundUtil.unknownSound("ENTITY_PLAYER_LEVELUPP"));
    }

    @Test
    void idsBecomeConstantNames() {
        assertEquals("ENTITY_PLAYER_LEVELUP", SoundUtil.constantName("ENTITY_PLAYER_LEVELUP"));
        assertEquals("ENTITY_PLAYER_LEVELUP", SoundUtil.constantName("minecraft:entity.player.levelup"));
        assertEquals("ENTITY_PLAYER_LEVELUP", SoundUtil.constantName("Minecraft:Entity.Player.LevelUp"));
        assertEquals("BLOCK_NOTE_BLOCK_PLING", SoundUtil.constantName("block.note_block.pling"));
        assertEquals("OKIMC:CLICK-2", SoundUtil.constantName("okimc:click-2"));
    }

    /** A namespaced id outside the registry is a resource pack sound; its key must be valid. */
    @Test
    void namespacedIdsMakeKeys() {
        assertEquals("okimc:click-2", SoundUtil.keyOf("okimc:click-2").toString());
        assertEquals("okimc:ui.click", SoundUtil.keyOf("OkiMC:UI.Click").toString());
        assertEquals("minecraft:entity.player.levelup", SoundUtil.keyOf("entity.player.levelup").toString());
        assertNull(SoundUtil.keyOf("a:b:c"));
        assertNull(SoundUtil.keyOf("bad key!"));
    }
}
