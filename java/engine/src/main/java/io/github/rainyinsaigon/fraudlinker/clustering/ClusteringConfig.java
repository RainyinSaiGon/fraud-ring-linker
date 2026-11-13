package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.index.IndexConfig;
import io.github.rainyinsaigon.fraudlinker.scoring.ScoringConfig;

/**
 * The complete operating point in one immutable value.
 *
 * @param streamThreshold minimum score for a streaming account to join an existing cluster
 */
public record ClusteringConfig(IndexConfig index, ScoringConfig scoring, double streamThreshold) {
    public static ClusteringConfig defaults() {
        return new ClusteringConfig(IndexConfig.defaults(), ScoringConfig.defaults(), 0.82);
    }
}
