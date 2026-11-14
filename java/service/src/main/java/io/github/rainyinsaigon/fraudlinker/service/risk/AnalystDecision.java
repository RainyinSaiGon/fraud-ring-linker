package io.github.rainyinsaigon.fraudlinker.service.risk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An analyst's review of a cluster.
 *
 * @param clusterId  the cluster ID at decision time (IDs can change when state is rebuilt)
 * @param accountIds exactly which accounts the decision covered; this, not the cluster ID, is what later lookups use
 */
public record AnalystDecision(
        UUID decisionId,
        String clusterId,
        Outcome outcome,
        String analyst,
        String note,
        Instant decidedAt,
        List<String> accountIds) {

    public AnalystDecision {
        accountIds = List.copyOf(accountIds);
    }
}
