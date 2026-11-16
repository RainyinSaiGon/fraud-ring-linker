package io.github.rainyinsaigon.fraudlinker.takeover;

import java.time.Instant;
import java.util.List;

/**
 * How takeover-like one account's logins look, in its worst incident.
 *
 * @param score     noisy-OR of the weights of the reasons in the worst incident, rounded to 4 decimals
 * @param flagged   {@code score} reached the flag threshold; this asks for review, it never acts
 * @param reasons   the signals in that incident, in a fixed order: new device, impossible travel,
 *                  failed burst then success, failed burst, shared device
 * @param reachedAt when the incident reached this score (its latest signal); null when no signal fired
 */
public record TakeoverScore(String accountId, double score, boolean flagged, List<TakeoverReason> reasons,
                            Instant reachedAt) {

    public TakeoverScore {
        reasons = List.copyOf(reasons);
    }
}
