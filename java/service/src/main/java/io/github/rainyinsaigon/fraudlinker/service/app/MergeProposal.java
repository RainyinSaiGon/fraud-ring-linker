package io.github.rainyinsaigon.fraudlinker.service.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A suggestion to merge two clusters, made when a new account linked strongly
 * to both. Cluster IDs are as they were when proposed; the account lists say
 * exactly which accounts were on each side, because IDs can change on a rebuild.
 *
 * @param triggerAccountId the account whose arrival produced the proposal
 */
public record MergeProposal(
        UUID proposalId,
        Status status,
        String triggerAccountId,
        String clusterA,
        String clusterB,
        double score,
        String rule,
        Instant proposedAt,
        List<String> accountsA,
        List<String> accountsB,
        String decidedBy,
        String note,
        Instant decidedAt) {

    public enum Status { OPEN, ACCEPTED, REJECTED }

    public MergeProposal {
        accountsA = List.copyOf(accountsA);
        accountsB = List.copyOf(accountsB);
    }
}
