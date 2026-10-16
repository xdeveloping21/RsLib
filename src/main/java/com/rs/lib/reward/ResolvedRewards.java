package com.rs.lib.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A reward list after every check and roll was made: what to show (for example during an
 * animation) and then hand over, exactly. Made by {@link Rewards#resolve} or
 * {@link Rewards#pick} and delivered by {@link Rewards#deliver}; nothing is checked or rolled
 * again at delivery.
 *
 * <p>Immutable, except that it can be delivered only once: a second delivery hands over
 * nothing and reports every won reward as {@link RewardOutcome#FAILED}, so a menu closed
 * during an animation and the animation's end cannot both pay out.</p>
 */
public final class ResolvedRewards {

    private final UUID player;
    private final List<ResolvedReward> entries;
    private final AtomicBoolean delivered = new AtomicBoolean();

    ResolvedRewards(UUID player, List<ResolvedReward> entries) {
        this.player = player;
        this.entries = List.copyOf(entries);
    }

    /** The player it was decided for, or null when it was decided without one. */
    public UUID player() {
        return player;
    }

    /** Every reward of the list, won or not, in order. */
    public List<ResolvedReward> entries() {
        return entries;
    }

    /** The rewards that will be handed over, in order. */
    public List<ResolvedReward> won() {
        List<ResolvedReward> out = new ArrayList<>();
        for (ResolvedReward entry : entries) {
            if (entry.won()) {
                out.add(entry);
            }
        }
        return out;
    }

    /** True once it was handed to a delivery. */
    public boolean delivered() {
        return delivered.get();
    }

    /** True exactly once: for the first delivery. */
    boolean take() {
        return delivered.compareAndSet(false, true);
    }

    @Override
    public String toString() {
        return "ResolvedRewards" + entries;
    }
}
