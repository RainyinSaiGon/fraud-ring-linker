package io.github.rainyinsaigon.fraudlinker.scoring;

import java.time.Duration;

/** The single, documented operating point shared by batch and stream. */
public record ScoringConfig(
        double minMergeScore,
        int strongEmailFrequencyLimit,
        int strongPaymentFrequencyLimit,
        int deviceFrequencyLimit,
        int supportingIpFrequencyLimit,
        Duration tightTimeWindow,
        Duration burstWindow,
        int burstMinSize,
        int burstMaxPrefix,
        double burstMinDistinctIpRatio,
        int seededPrefixMinSize,
        int seededPrefixMaxSize,
        double seededPrefixMinDistinctIpRatio,
        double seededPrefixScore) {

    public static ScoringConfig defaults() {
        return new ScoringConfig(
                0.70,
                24,
                8,
                16,
                8,
                Duration.ofHours(24),
                Duration.ofHours(1),
                3,
                8,
                0.75,
                3,
                8,
                0.75,
                0.82);
    }
}
