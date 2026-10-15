package com.rs.lib.snapshot;

import static com.rs.lib.snapshot.SnapKit.CODEC;
import static com.rs.lib.snapshot.SnapKit.stack;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import com.rs.lib.snapshot.SnapKit.FakePlayer;

/**
 * The snapshot text form and what capture, restore and clear do to a player. Paper's item
 * bytes and potion effect types need a server, so items go through a test codec here; the
 * Base64 item frame itself is covered by {@code ItemSerializerTest}.
 */
class PlayerSnapshotTest {

    /** A file written the way the notes describe it (flow maps, quoted slots), as SnLib-based plugins left them. */
    private static final String STORED = """
            format: 1
            taken-at: 1760380800000
            parts: [INVENTORY, ENDER_CHEST, HEALTH, EXPERIENCE, EFFECTS, GAMEMODE, LOCATION]
            inventory:
              items:
                size: 41
                '0': sword*1
                '39': helmet*1
                '40': torch*99
              cursor: ''
              held-slot: 3
            health: {health: 12.5, food: 17, saturation: 2.5, exhaustion: 0.5, air: 250, fire-ticks: 40}
            experience: {level: 3, exp: 0.25, total: 30}
            effects: []
            gamemode: {mode: CREATIVE, allow-flight: true, flying: true}
            location: "world;10.5;64.0;-3.25;90.0;0.0"
            ender-chest: {size: 27, '3': pearl*16}
            """;

    private static FakePlayer stocked() {
        FakePlayer p = new FakePlayer();
        p.slots[0] = stack("sword", 1);
        p.slots[8] = stack("bread", 300);
        p.slots[40] = stack("shield", 1);
        p.cursor = stack("dirt", 5);
        p.held = 2;
        p.ender[26] = stack("gold", 7);
        p.health = 9.5;
        p.food = 11;
        p.saturation = 1.5f;
        p.exhaustion = 2f;
        p.air = 120;
        p.fireTicks = 60;
        p.level = 7;
        p.exp = 0.5f;
        p.total = 150;
        p.mode = GameMode.ADVENTURE;
        return p;
    }

    /** Trampa: {@code format: 1}, the documented keys, quoted slot numbers and only non-empty slots. */
    @Test
    void textFormIsFormatOneWithTheDocumentedKeys() throws Exception {
        FakePlayer p = stocked();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(p.player, EnumSet.of(SnapshotPart.INVENTORY,
                SnapshotPart.ENDER_CHEST, SnapshotPart.HEALTH, SnapshotPart.EXPERIENCE, SnapshotPart.GAMEMODE));
        String yaml = snapshot.toYaml(CODEC);
        YamlConfiguration read = new YamlConfiguration();
        read.loadFromString(yaml);

        assertEquals(1, read.getInt("format"));
        assertEquals(snapshot.takenAt(), read.getLong("taken-at"));
        assertEquals(List.of("INVENTORY", "ENDER_CHEST", "HEALTH", "EXPERIENCE", "GAMEMODE"), read.getStringList("parts"));
        assertEquals(41, read.getInt("inventory.items.size"));
        assertEquals(Set.of("size", "0", "8", "40"), read.getConfigurationSection("inventory.items").getKeys(false));
        assertTrue(yaml.contains("'0': sword*1"), yaml);
        assertEquals("bread*300", read.getString("inventory.items.8"));
        assertEquals("dirt*5", read.getString("inventory.cursor"));
        assertEquals(2, read.getInt("inventory.held-slot"));
        assertEquals(Set.of("health", "food", "saturation", "exhaustion", "air", "fire-ticks"),
                read.getConfigurationSection("health").getKeys(false));
        assertEquals(9.5, read.getDouble("health.health"));
        assertEquals(60, read.getInt("health.fire-ticks"));
        assertEquals(7, read.getInt("experience.level"));
        assertEquals(0.5, read.getDouble("experience.exp"));
        assertEquals(150, read.getInt("experience.total"));
        assertEquals("ADVENTURE", read.getString("gamemode.mode"));
        assertFalse(read.getBoolean("gamemode.allow-flight"));
        assertEquals(27, read.getInt("ender-chest.size"));
        assertEquals("gold*7", read.getString("ender-chest.26"));
        assertFalse(read.contains("effects"));
        assertFalse(read.contains("location"));
    }

    @Test
    void emptyCursorIsAnEmptyText() throws Exception {
        FakePlayer p = new FakePlayer();
        YamlConfiguration read = new YamlConfiguration();
        read.loadFromString(PlayerSnapshot.capture(p.player, Set.of(SnapshotPart.INVENTORY)).toYaml(CODEC));
        assertEquals("", read.getString("inventory.cursor"));
        assertEquals(Set.of("size"), read.getConfigurationSection("inventory.items").getKeys(false));
    }

    @Test
    void textRoundTripKeepsEverything() {
        FakePlayer p = stocked();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(p.player, SnapshotPart.ALL.stream()
                .filter(part -> part != SnapshotPart.LOCATION && part != SnapshotPart.EFFECTS)
                .collect(java.util.stream.Collectors.toSet()));
        PlayerSnapshot back = PlayerSnapshot.fromYaml(snapshot.toYaml(CODEC), CODEC);
        assertEquals(snapshot.parts(), back.parts());
        assertEquals(snapshot.takenAt(), back.takenAt());
        assertArrayEquals(snapshot.inventory(), back.inventory());
        assertEquals(snapshot.cursor(), back.cursor());
        assertEquals(2, back.heldSlot());
        assertArrayEquals(snapshot.enderChest(), back.enderChest());
        assertEquals(snapshot.vitals(), back.vitals());
        assertEquals(snapshot.experience(), back.experience());
        assertEquals(snapshot.mode(), back.mode());
        assertEquals(5, back.itemCount());
    }

    /** Files written by plugins built on SnLib (the documented shape) read as they are. */
    @Test
    void readsTheDocumentedFile() {
        PlayerSnapshot snapshot = PlayerSnapshot.fromYaml(STORED, CODEC);
        assertEquals(SnapshotPart.ALL, snapshot.parts());
        assertEquals(1760380800000L, snapshot.takenAt());
        assertEquals(41, snapshot.inventory().length);
        assertEquals(stack("torch", 99), snapshot.inventory()[40]);
        assertNull(snapshot.cursor());
        assertEquals(3, snapshot.heldSlot());
        assertEquals(new PlayerSnapshot.Vitals(12.5, 17, 2.5f, 0.5f, 250, 40), snapshot.vitals());
        assertEquals(new PlayerSnapshot.Xp(3, 0.25f, 30), snapshot.experience());
        assertEquals(List.of(), snapshot.effects());
        assertEquals(new PlayerSnapshot.Mode(GameMode.CREATIVE, true, true), snapshot.mode());
        assertEquals("world;10.5;64.0;-3.25;90.0;0.0", snapshot.location());
        assertEquals(stack("pearl", 16), snapshot.enderChest()[3]);
        assertEquals(4, snapshot.itemCount());
    }

    /** Trampa: anything outside {@code format: 1} is refused instead of half-read. */
    @Test
    void unreadableFilesAreRefused() {
        String[] broken = {
            STORED.replace("format: 1", "format: 2"),
            STORED.replace("format: 1\n", ""),
            STORED.replace("GAMEMODE, LOCATION", "GAMEMODE, PETS"),
            STORED.replace("size: 41", "size: 300"),
            STORED.replace("size: 41", "size: -1"),
            STORED.replace("'40': torch*99", "'41': torch*99"),
            STORED.replace("'40': torch*99", "'x': torch*99"),
            STORED.replace("'40': torch*99", "'40': 5"),
            STORED.replace("mode: CREATIVE", "mode: FLYING"),
            STORED.replace("level: 3", "level: three"),
            STORED.replace("health: {health: 12.5, food: 17, saturation: 2.5, exhaustion: 0.5, air: 250, fire-ticks: 40}\n", ""),
            "just text",
            "parts: [",
        };
        for (String text : broken) {
            assertThrows(IllegalArgumentException.class, () -> PlayerSnapshot.fromYaml(text, CODEC), text);
        }
    }

    /** All or nothing: one item that cannot be decoded makes the whole snapshot unreadable. */
    @Test
    void oneBadItemSpoilsTheWholeSnapshot() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PlayerSnapshot.fromYaml(STORED.replace("pearl*16", "BAD"), CODEC));
        assertTrue(e.getMessage().contains("ender-chest.3"), e.getMessage());
    }

    /** An effect whose type no longer exists is left out and counted; the rest still reads. */
    @Test
    void unknownEffectsAreLeftOut() {
        String text = STORED.replace("effects: []", """
                effects:
                - ==: PotionEffect
                  effect: minecraft:removed
                  duration: 200
                  amplifier: 1
                - not a map
                """);
        PlayerSnapshot snapshot = PlayerSnapshot.fromYaml(text, CODEC);
        assertEquals(List.of(), snapshot.effects());
        assertEquals(2, snapshot.skippedEffects());
    }

    /** The real codec refuses broken Base64 before ever reaching Paper. */
    @Test
    void serverCodecRefusesWhatIsNotAnItem() {
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.SERVER.decodeItem("AAAA"));
        assertThrows(IllegalArgumentException.class, () -> PlayerSnapshot.fromYaml(
                STORED.replace("pearl*16", "AAAA"), SnapshotCodec.SERVER));
    }

    @Test
    void anItemThatCannotBeWrittenFailsTheText() {
        FakePlayer p = new FakePlayer();
        p.slots[4] = stack("UNWRITABLE", 1);
        PlayerSnapshot snapshot = PlayerSnapshot.capture(p.player, Set.of(SnapshotPart.INVENTORY));
        assertThrows(IllegalStateException.class, () -> snapshot.toYaml(CODEC));
    }

    @Test
    void captureCopiesTheStacks() {
        FakePlayer p = stocked();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(p.player, Set.of(SnapshotPart.INVENTORY));
        p.slots[0] = stack("stick", 1);
        assertEquals(stack("sword", 1), snapshot.inventory()[0]);
        assertTrue(snapshot.inventory()[0] != p.slots[0]);
        assertNull(snapshot.enderChest());
        assertNull(snapshot.vitals());
    }

    /** Restoring replaces the captured parts; the cursor and what does not fit go into the inventory. */
    @Test
    void restoreReplacesTheCapturedParts() {
        PlayerSnapshot snapshot = PlayerSnapshot.fromYaml(STORED.replace(", LOCATION", "")
                .replace("cursor: ''", "cursor: dirt*5"), CODEC);
        FakePlayer p = new FakePlayer();
        p.slots[5] = stack("staff-wand", 1);
        p.ender[0] = stack("junk", 1);
        p.health = 3;
        p.maxHealth = 10;
        p.air = 10;
        p.maxAir = 200;
        snapshot.restoreTo(p.player);

        assertEquals(stack("sword", 1), p.slots[0]);
        assertEquals(stack("helmet", 1), p.slots[39]);
        assertEquals(stack("torch", 99), p.slots[40]);
        assertEquals(stack("dirt", 5), p.slots[1], "the stored cursor goes into the inventory");
        assertNull(p.cursor);
        assertNull(p.slots[5], "what the player had is replaced");
        assertEquals(3, p.held);
        assertEquals(stack("pearl", 16), p.ender[3]);
        assertNull(p.ender[0]);
        assertEquals(10.0, p.health, "health is kept within the current maximum");
        assertEquals(17, p.food);
        assertEquals(2.5f, p.saturation);
        assertEquals(200, p.air, "air is kept within the maximum");
        assertEquals(40, p.fireTicks);
        assertEquals(3, p.level);
        assertEquals(0.25f, p.exp);
        assertEquals(30, p.total);
        assertEquals(GameMode.CREATIVE, p.mode);
        assertTrue(p.flying);
    }

    @Test
    void zeroHealthComesBackFullAndFlightOnlyWhenAllowed() {
        PlayerSnapshot snapshot = PlayerSnapshot.fromYaml(STORED.replace(", LOCATION", "")
                .replace("health: 12.5", "health: 0.0")
                .replace("mode: CREATIVE, allow-flight: true, flying: true", "mode: SURVIVAL, allow-flight: false, flying: true")
                .replace("exp: 0.25", "exp: 1.5"), CODEC);
        FakePlayer p = new FakePlayer();
        p.maxHealth = 24;
        snapshot.restoreTo(p.player);
        assertEquals(24.0, p.health);
        assertFalse(p.flying);
        assertEquals(0f, p.exp, "a progress outside 0-1 is not passed to the server");
    }

    /** Items a smaller inventory cannot hold are added elsewhere instead of being lost. */
    @Test
    void itemsBeyondASmallerInventoryAreKept() {
        PlayerSnapshot snapshot = PlayerSnapshot.fromYaml(STORED.replace(", LOCATION", ""), CODEC);
        FakePlayer p = new FakePlayer(40, 27);
        snapshot.restoreTo(p.player);
        assertEquals(stack("helmet", 1), p.slots[39]);
        assertEquals(stack("torch", 99), p.slots[1]);
    }

    /** Giving back after a failed save keeps what the player picked up in between. */
    @Test
    void applyKeepingCurrentLosesNothing() {
        FakePlayer p = stocked();
        PlayerSnapshot snapshot = PlayerSnapshot.capture(p.player, Set.of(SnapshotPart.INVENTORY));
        PlayerSnapshot.clear(p.player, Set.of(SnapshotPart.INVENTORY));
        p.slots[0] = stack("apple", 2);
        snapshot.apply(p.player, true);
        assertEquals(stack("sword", 1), p.slots[0]);
        assertEquals(stack("bread", 300), p.slots[8]);
        assertEquals(stack("shield", 1), p.slots[40]);
        List<ItemStack> all = java.util.Arrays.asList(p.slots);
        assertTrue(all.contains(stack("apple", 2)));
        assertTrue(all.contains(stack("dirt", 5)));
    }

    @Test
    void clearEmptiesWhatWasAskedFor() {
        FakePlayer p = stocked();
        p.maxHealth = 30;
        PlayerSnapshot.clear(p.player, EnumSet.of(SnapshotPart.INVENTORY, SnapshotPart.HEALTH, SnapshotPart.EXPERIENCE,
                SnapshotPart.GAMEMODE));
        assertEquals(1, p.itemCount(), "only the ender chest keeps its item");
        assertNull(p.cursor);
        assertEquals(30.0, p.health);
        assertEquals(20, p.food);
        assertEquals(5f, p.saturation);
        assertEquals(0f, p.exhaustion);
        assertEquals(300, p.air);
        assertEquals(0, p.fireTicks);
        assertEquals(0, p.level);
        assertEquals(0f, p.exp);
        assertEquals(0, p.total);
        assertEquals(GameMode.ADVENTURE, p.mode, "the game mode is left alone");

        PlayerSnapshot.clear(p.player, Set.of(SnapshotPart.ENDER_CHEST));
        assertEquals(0, p.itemCount());
    }

    @Test
    void partsAndOptions() {
        assertEquals(EnumSet.of(SnapshotPart.INVENTORY, SnapshotPart.HEALTH, SnapshotPart.EXPERIENCE,
                SnapshotPart.EFFECTS, SnapshotPart.GAMEMODE), SnapshotPart.DEFAULT);
        assertEquals(7, SnapshotPart.ALL.size());
        SnapshotOptions defaults = SnapshotOptions.defaults();
        assertEquals(SnapshotPart.DEFAULT, defaults.parts());
        assertFalse(defaults.clear());
        assertFalse(defaults.overwrite());
        SnapshotOptions custom = defaults.withClear(true).withOverwrite(true).withParts(Set.of(SnapshotPart.LOCATION));
        assertTrue(custom.clear());
        assertTrue(custom.overwrite());
        assertEquals(Set.of(SnapshotPart.LOCATION), custom.parts());
        assertThrows(IllegalArgumentException.class, () -> defaults.withParts(Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> custom.parts().add(SnapshotPart.HEALTH));
        Map<String, Integer> slots = Map.of("0", 0, "40", 40, "255", 255, "", -1, "-1", -1, "1a", -1, "1000", -1);
        slots.forEach((key, slot) -> assertEquals(slot, PlayerSnapshot.slotNumber(key), key));
    }
}
