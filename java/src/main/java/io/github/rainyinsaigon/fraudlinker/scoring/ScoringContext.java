package io.github.rainyinsaigon.fraudlinker.scoring;

import io.github.rainyinsaigon.fraudlinker.domain.Frequencies;

/**
 * Global and cohort state the scorer needs, without coupling it to the index.
 *
 * @param projectedIncoming true when the right-hand account is a streaming record not yet counted
 * @param burstSize         accounts in the shared prefix within one burst window (0 when not computed)
 * @param distinctIpRatio   distinct-IP share of the prefix cohort (0 when not computed)
 */
public record ScoringContext(
        Frequencies frequencies,
        boolean projectedIncoming,
        int burstSize,
        double distinctIpRatio) {
}
