package com.rs.lib.reward;

import java.util.ArrayList;
import java.util.List;

/**
 * Every {@link RewardResult} of one delivery, in the order of the list. Immutable.
 *
 * <p>A reward handed over as several stacks or rows (a claim of queued items) can appear more
 * than once with the same id.</p>
 */
public final class RewardReport {

    /** A delivery of nothing. */
    public static final RewardReport EMPTY = new RewardReport(List.of());

    private final List<RewardResult> results;

    RewardReport(List<RewardResult> results) {
        this.results = List.copyOf(results);
    }

    public List<RewardResult> results() {
        return results;
    }

    /** The first result of the reward {@code id}, or null. */
    public RewardResult get(String id) {
        for (RewardResult result : results) {
            if (result.id().equals(id)) {
                return result;
            }
        }
        return null;
    }

    /** How many results have {@code outcome}. */
    public int count(RewardOutcome outcome) {
        int count = 0;
        for (RewardResult result : results) {
            if (result.outcome() == outcome) {
                count++;
            }
        }
        return count;
    }

    /** The results that are {@link RewardOutcome#failure() failures}. */
    public List<RewardResult> failures() {
        List<RewardResult> out = new ArrayList<>();
        for (RewardResult result : results) {
            if (result.outcome().failure()) {
                out.add(result);
            }
        }
        return out;
    }

    /** True when at least one reward was handed over. */
    public boolean anyGiven() {
        return count(RewardOutcome.GIVEN) > 0;
    }

    @Override
    public String toString() {
        return "RewardReport" + results;
    }
}
