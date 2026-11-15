package io.github.rainyinsaigon.fraudlinker.mules;

import java.util.List;

/**
 * How mule-like one account's money movement looks.
 *
 * @param score   noisy-OR of the weights of the reasons that fired, rounded to 4 decimals
 * @param flagged {@code score} reached the flag threshold; this asks for review, it never restricts
 * @param reasons detectors that fired, in a fixed order: fan-in, pass-through, chain
 */
public record MuleScore(String accountId, double score, boolean flagged, List<MuleReason> reasons) {

    public MuleScore {
        reasons = List.copyOf(reasons);
    }
}
