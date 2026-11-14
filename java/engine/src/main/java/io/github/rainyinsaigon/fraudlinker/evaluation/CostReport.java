package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.domain.MergeCandidate;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamLinker;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamResult;

import java.util.List;

/**
 * Links one dataset three ways and scores each against the ground truth:
 * <ul>
 *   <li><b>batch</b>: every account linked at once, with full hindsight;</li>
 *   <li><b>stream</b>: starting empty, accounts arrive one at a time in file order,
 *       exactly as the REST service sees them. Streaming never merges existing
 *       clusters, so the gap between the two is the cost of arrival order;</li>
 *   <li><b>stream with proposals</b>: the same stream, but every merge proposal is
 *       accepted the moment it opens. This is the best case for merge proposals:
 *       an analyst who accepts everything at once. Its gap to batch is what
 *       proposals cannot recover.</li>
 * </ul>
 */
public final class CostReport {
    private CostReport() {
    }

    /**
     * @param proposals      merge proposals opened in the proposals run, which is the analyst's review workload
     * @param proposalsMerged proposals whose acceptance changed the clusters (some are refused because an
     *                        earlier merge put a verified-distinct pair on the two sides)
     */
    public record Result(Metrics batch, Metrics stream, Metrics streamWithProposals, long proposals,
                         long proposalsMerged, ClustersOutput batchClusters, ClustersOutput streamClusters,
                         ClustersOutput streamWithProposalsClusters) {
    }

    private record Streamed(ClustersOutput clusters, long proposals, long merged) {
    }

    public static Result run(List<Account> accounts, List<Constraint> constraints, Truth truth,
                             ClusteringConfig config, CostModel costs) {
        ClustersOutput batch = BatchLinker.build(accounts, Constraints.of(constraints), config).clusters();
        Streamed stream = stream(accounts, constraints, config, false);
        Streamed withProposals = stream(accounts, constraints, config, true);

        return new Result(
                Evaluator.evaluate(batch, truth, constraints, costs),
                Evaluator.evaluate(stream.clusters(), truth, constraints, costs),
                Evaluator.evaluate(withProposals.clusters(), truth, constraints, costs),
                withProposals.proposals(),
                withProposals.merged(),
                batch,
                stream.clusters(),
                withProposals.clusters());
    }

    private static Streamed stream(List<Account> accounts, List<Constraint> constraints, ClusteringConfig config,
                                   boolean acceptProposals) {
        LinkState state = BatchLinker.build(List.of(), Constraints.of(constraints), config);
        StreamLinker linker = new StreamLinker(state);
        long proposals = 0;
        long merged = 0;
        for (Account account : accounts) {
            StreamResult result = linker.assignWithCandidates(account);
            if (!acceptProposals) {
                continue;
            }
            for (MergeCandidate candidate : result.mergeCandidates()) {
                proposals++;
                // The account's cluster ID can change after an earlier merge, so look it up each time.
                String joined = state.clusterIdOf(account.accountId()).orElseThrow();
                if (state.mergeClusters(joined, candidate.clusterId(), candidate.score())
                        == LinkState.MergeOutcome.MERGED) {
                    merged++;
                }
            }
        }
        state.validateInvariants();
        return new Streamed(state.clusters(), proposals, merged);
    }
}
