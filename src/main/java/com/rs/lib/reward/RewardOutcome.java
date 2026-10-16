package com.rs.lib.reward;

/**
 * What happened to one reward. The checks run in this order: permission, requirement, roll;
 * so a misspelt permission is reported as {@link #NO_PERMISSION}, never as a lost roll.
 */
public enum RewardOutcome {
    /** Handed over (items that did not fit may have been dropped at the player's feet). */
    GIVEN,
    /** The chance (or the weighted draw) said no. */
    LOST_ROLL,
    /** The player lacks the reward's permission (or there was no player to ask). */
    NO_PERMISSION,
    /** The reward's requirement does not hold (or there was no player to check it on). */
    REQUIREMENT_FAILED,
    /** The items did not fit and the overflow policy was {@link OverflowPolicy#FAIL}: nothing was given. */
    NO_SPACE,
    /** Kept in the database and handed over later (on join or on a claim). */
    QUEUED,
    /** Something is broken (a command that failed, an economy that said no, an unreadable item). */
    FAILED;

    /** True for {@link #GIVEN}. */
    public boolean given() {
        return this == GIVEN;
    }

    /** True for the outcomes a configuration chose: a lost roll, a missing permission, a failed requirement. */
    public boolean skipped() {
        return this == LOST_ROLL || this == NO_PERMISSION || this == REQUIREMENT_FAILED;
    }

    /** True for {@link #NO_SPACE} and {@link #FAILED}: the player did not get something they won. */
    public boolean failure() {
        return this == NO_SPACE || this == FAILED;
    }
}
