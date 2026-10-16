package com.rs.lib.reward;

/**
 * What happens to reward items that do not fit in the player's inventory. No policy loses
 * anything: an item is either given, dropped, kept in the database, or not taken out of the
 * reward at all.
 */
public enum OverflowPolicy {
    /** Dropped at the player's feet, split into normal stacks. */
    DROP,
    /**
     * What fits is given and the rest is kept in the database until the player claims it
     * (on join or with {@link Rewards#claim}). The default. Needs
     * {@code RsSpec.builder().db().rewards()}; without it the rest is dropped at the feet (the
     * console is told once), and so it is when the database refuses the write.
     */
    QUEUE,
    /** All or nothing: unless every item of the reward fits, none is given ({@link RewardOutcome#NO_SPACE}). */
    FAIL
}
