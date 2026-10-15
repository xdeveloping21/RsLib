package com.rs.lib.util;

import org.bukkit.entity.Player;

/**
 * Vanilla experience arithmetic, exact for every level.
 *
 * <p>Minecraft stores experience as a level plus the progress of the bar towards the next
 * one. The points a level costs grow in three bands: {@code 2L + 7} up to level 15,
 * {@code 5L - 38} from 16 to 30 and {@code 9L - 158} from 31 on. This class converts between
 * that form and a single total of points, so experience can be taken, given or stored as one
 * number. Levels above {@value #MAX_LEVEL} count as that level (their totals would not fit in
 * a {@code long}). The pure methods are safe from any thread; the {@link Player} ones belong
 * on the player's thread.</p>
 */
public final class Experience {

    /** Highest level the arithmetic goes up to. */
    public static final int MAX_LEVEL = 1_000_000_000;

    /** Total points at the start of level 16 and of level 31, where the bands change. */
    private static final long TOTAL_AT_16 = 352L;
    private static final long TOTAL_AT_31 = 1_507L;

    private Experience() {
    }

    /**
     * Points needed to go from {@code level} to {@code level + 1}; 0 for a negative level,
     * {@code Integer.MAX_VALUE} when it would not fit in an {@code int}.
     */
    public static int getExpToNext(int level) {
        return (int) Math.min(Integer.MAX_VALUE, need(level));
    }

    /** Total points a player has at the very start of {@code level}; 0 for level 0 or less. */
    public static long getExpFromLevel(int level) {
        if (level <= 0) {
            return 0L;
        }
        long l = Math.min(level, MAX_LEVEL);
        if (l <= 16) {
            return l * l + 6 * l;
        }
        if (l <= 31) {
            // 2.5 L^2 - 40.5 L + 360 in whole numbers (the numerator is always even).
            return (5 * l * l - 81 * l + 720) / 2;
        }
        // 4.5 L^2 - 162.5 L + 2220 in whole numbers (the numerator is always even).
        return (9 * l * l - 325 * l + 4440) / 2;
    }

    /** The whole level reached with {@code exp} total points; 0 for 0 or less. */
    public static int getIntLevelFromExp(long exp) {
        if (exp <= 0) {
            return 0;
        }
        double estimate;
        if (exp <= TOTAL_AT_16) {
            estimate = -3 + Math.sqrt(9.0 + exp);
        } else if (exp <= TOTAL_AT_31) {
            estimate = (40.5 + Math.sqrt(10.0 * exp - 1_959.75)) / 5;
        } else {
            estimate = (162.5 + Math.sqrt(18.0 * exp - 13_553.75)) / 9;
        }
        int level = (int) Math.max(0, Math.min(MAX_LEVEL, (long) estimate));
        // The square root is within one step; settle it with whole numbers.
        while (level > 0 && getExpFromLevel(level) > exp) {
            level--;
        }
        while (level < MAX_LEVEL && getExpFromLevel(level + 1) <= exp) {
            level++;
        }
        return level;
    }

    /**
     * The level reached with {@code exp} total points, with the progress of the bar as the
     * fraction: level 16 costs 42 points, so {@code getLevelFromExp(getExpFromLevel(16) + 21)}
     * is {@code 16.5}. 0 for 0 or less.
     */
    public static double getLevelFromExp(long exp) {
        if (exp <= 0) {
            return 0;
        }
        int level = getIntLevelFromExp(exp);
        if (level >= MAX_LEVEL) {
            return MAX_LEVEL;
        }
        return level + (double) (exp - getExpFromLevel(level)) / need(level);
    }

    /** Total points of {@code player}: its level plus the points in its bar. */
    public static long getExp(Player player) {
        int level = Math.min(Math.max(player.getLevel(), 0), MAX_LEVEL);
        return getExpFromLevel(level) + Math.round((double) player.getExp() * need(level));
    }

    /**
     * Adds {@code amount} points (negative takes them away) and sets the level and the bar
     * from the new total, never below 0. The bar always stays below a full level.
     */
    public static void changeExp(Player player, int amount) {
        long total = Math.max(0L, getExp(player) + amount);
        int level = getIntLevelFromExp(total);
        double progress = level >= MAX_LEVEL ? 0.0
                : (double) (total - getExpFromLevel(level)) / need(level);
        player.setLevel(level);
        player.setExp((float) Math.min(Math.max(progress, 0.0), Math.nextDown(1.0f)));
    }

    /** {@link #getExpToNext(int)} without the {@code int} limit. */
    private static long need(int level) {
        if (level < 0) {
            return 0L;
        }
        long l = level;
        if (l <= 15) {
            return 2 * l + 7;
        }
        if (l <= 30) {
            return 5 * l - 38;
        }
        return 9 * l - 158;
    }
}
