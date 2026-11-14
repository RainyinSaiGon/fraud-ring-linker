package io.github.rainyinsaigon.fraudlinker.service.engine;

import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState.MergeOutcome;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Merges whatever clusters a set of accounts is in now into one. */
public final class ClusterMerger {
    private ClusterMerger() {
    }

    /**
     * Merges the clusters that currently hold these accounts, oldest cluster first.
     * Unknown accounts are ignored.
     *
     * <p>Stops at the first constraint conflict. Merges already made before it stay,
     * so a caller inside {@code WriteThrough} must fail the request to have them undone.
     *
     * @return MERGED if any merge happened, ALREADY_ONE_CLUSTER if none was needed,
     *         CONFLICTS_WITH_CONSTRAINT if a merge was refused
     */
    public static MergeOutcome mergeAccounts(LinkState state, Collection<String> accountIds, double linkConfidence) {
        List<String> clusters = accountIds.stream()
                .map(state::clusterIdOf)
                .flatMap(Optional::stream)
                .distinct()
                .sorted(Comparator.comparingInt(LinkState::clusterNumber))
                .toList();
        MergeOutcome result = MergeOutcome.ALREADY_ONE_CLUSTER;
        for (int i = 1; i < clusters.size(); i++) {
            // The older ID survives each merge, so clusters.get(0) always names the growing cluster.
            MergeOutcome outcome = state.mergeClusters(clusters.get(0), clusters.get(i), linkConfidence);
            if (outcome == MergeOutcome.CONFLICTS_WITH_CONSTRAINT) {
                return outcome;
            }
            result = MergeOutcome.MERGED;
        }
        return result;
    }
}
