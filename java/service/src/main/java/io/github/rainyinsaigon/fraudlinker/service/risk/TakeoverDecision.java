package io.github.rainyinsaigon.fraudlinker.service.risk;

import java.time.Instant;
import java.util.UUID;

/** An analyst's takeover decision about one account. It never covers the rest of the cluster. */
public record TakeoverDecision(
        UUID decisionId,
        String accountId,
        TakeoverOutcome outcome,
        String analyst,
        String note,
        Instant decidedAt) {
}
