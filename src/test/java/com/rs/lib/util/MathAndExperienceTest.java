package com.rs.lib.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Random;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class MathAndExperienceTest {

    private static Random fixed(double value) {
        return new Random() {
            private static final long serialVersionUID = 1L;

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    void fairRoundingUsesTheFractionAsAChance() {
        assertEquals(3, MathUtil.fairIntFromDouble(2.3, fixed(0.29)));
        assertEquals(2, MathUtil.fairIntFromDouble(2.3, fixed(0.31)));
        assertEquals(2, MathUtil.fairIntFromDouble(2.0, fixed(0.0)));
        assertEquals(-2, MathUtil.fairIntFromDouble(-2.3, fixed(0.69)));
        assertEquals(-3, MathUtil.fairIntFromDouble(-2.3, fixed(0.71)));
        assertEquals(Integer.MAX_VALUE, MathUtil.fairIntFromDouble(1e20));
        assertEquals(Long.MIN_VALUE, MathUtil.fairLongFromDouble(Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> MathUtil.fairLongFromDouble(Double.NaN));
        Random random = new Random(7);
        long sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += MathUtil.fairLongFromDouble(2.3, random);
        }
        assertEquals(2.3, sum / 100_000.0, 0.01);
    }

    @Test
    void romanNumerals() {
        assertEquals("I", MathUtil.convertToRoman(1));
        assertEquals("IV", MathUtil.convertToRoman(4));
        assertEquals("IX", MathUtil.convertToRoman(9));
        assertEquals("XIV", MathUtil.convertToRoman(14));
        assertEquals("MCMXCIV", MathUtil.convertToRoman(1994));
        assertEquals("MMMCMXCIX", MathUtil.convertToRoman(3999));
        assertEquals("0", MathUtil.convertToRoman(0));
        assertEquals("4000", MathUtil.convertToRoman(4000));
        assertEquals("-3", MathUtil.convertToRoman(-3));
    }

    @Test
    void vanillaCostPerLevel() {
        assertEquals(7, Experience.getExpToNext(0));
        assertEquals(37, Experience.getExpToNext(15));
        assertEquals(42, Experience.getExpToNext(16));
        assertEquals(112, Experience.getExpToNext(30));
        assertEquals(121, Experience.getExpToNext(31));
        assertEquals(0, Experience.getExpToNext(-1));
        assertEquals(0, Experience.getExpFromLevel(0));
        assertEquals(352, Experience.getExpFromLevel(16));
        assertEquals(1507, Experience.getExpFromLevel(31));
        assertEquals(1628, Experience.getExpFromLevel(32));
        assertEquals(8670, Experience.getExpFromLevel(60));
    }

    @Test
    void totalsAndLevelsAgreeEverywhere() {
        long total = 0;
        for (int level = 0; level <= 5000; level++) {
            assertEquals(total, Experience.getExpFromLevel(level), "level " + level);
            assertEquals(level, Experience.getIntLevelFromExp(total), "start of level " + level);
            if (total > 0) {
                assertEquals(level - 1, Experience.getIntLevelFromExp(total - 1), "end of level " + (level - 1));
            }
            total += Experience.getExpToNext(level);
        }
        assertEquals(Experience.MAX_LEVEL, Experience.getIntLevelFromExp(Long.MAX_VALUE));
        int big = 21_863;
        assertEquals(big, Experience.getIntLevelFromExp(Experience.getExpFromLevel(big)));
    }

    @Test
    void fractionalLevelIsTheBarProgress() {
        assertEquals(16.5, Experience.getLevelFromExp(Experience.getExpFromLevel(16) + 21));
        assertEquals(0.0, Experience.getLevelFromExp(-5));
        assertEquals(1.0, Experience.getLevelFromExp(7));
    }

    @Test
    void changeExpRecomputesLevelAndBar() {
        Map<String, Number> state = Fakes.state(0, 0f);
        Player player = Fakes.experience(state);
        Experience.changeExp(player, 352 + 21);
        assertEquals(16, state.get("level"));
        assertEquals(0.5f, state.get("exp").floatValue(), 1e-6);
        assertEquals(373, Experience.getExp(player));
        Experience.changeExp(player, -1000);
        assertEquals(0, state.get("level"));
        assertEquals(0f, state.get("exp").floatValue());
        Experience.changeExp(player, 6);
        assertEquals(0, state.get("level"));
        assertTrue(state.get("exp").floatValue() < 1f);
        Experience.changeExp(player, 1);
        assertEquals(1, state.get("level"));
        assertEquals(0f, state.get("exp").floatValue());
    }

    @Test
    void barNeverReachesAFullLevel() {
        Map<String, Number> state = Fakes.state(10_000, 0f);
        Player player = Fakes.experience(state);
        Experience.changeExp(player, Experience.getExpToNext(10_000) - 1);
        assertEquals(10_000, state.get("level"));
        assertTrue(state.get("exp").floatValue() < 1f);
    }
}
