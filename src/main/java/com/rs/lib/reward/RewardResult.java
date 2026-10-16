package com.rs.lib.reward;

import java.util.Objects;

/**
 * What happened to one reward of a delivery.
 *
 * @param id      the reward's id (its key in the YAML list)
 * @param type    what it hands over
 * @param outcome what happened
 */
public record RewardResult(String id, RewardType type, RewardOutcome outcome) {

    public RewardResult {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(outcome, "outcome");
    }
}
