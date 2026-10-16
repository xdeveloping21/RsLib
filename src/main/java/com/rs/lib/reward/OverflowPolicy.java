package com.rs.lib.reward;

/**
 * What happens to reward items that do not fit in the player's inventory. No policy loses
 * anything: an item is either given, dropped, kept in the database, or not taken out of the
 * reward at all.
 */
public enum OverflowPolicy {
    /** Dropped at the player's feet, split into normal stacks. The default. */
    DROP,
    /**
     * What fits is given and the rest is kept in the database until the player claims it
     * (on join or with {@link Rewards#claim}). Needs {@code RsSpec.builder().db().rewards()};
     * without it, or when the database refuses the write, the rest is dropped at the feet.
     */
    QUEUE,
    /** All or nothing: unless every item of the reward fits, none is given ({@link RewardOutcome#NO_SPACE}). */
    FAIL
}
