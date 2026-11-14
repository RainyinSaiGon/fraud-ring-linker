package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.streaming.StreamLinker;

import java.util.List;

/**
 * Links one dataset two ways and scores both against the ground truth:
 * <ul>
 *   <li><b>batch</b>: every account linked at once, with full hindsight;</li>
 *   <li><b>stream</b>: starting empty, accounts arrive one at a time in file order,
 *       exactly as the REST service sees them. Streaming never merges existing
 *       clusters, so the gap between the two is the cost of arrival order.</li>
 * </ul>
 */
public final class CostReport {
    private CostReport() {
    }

    public record Result(Metrics batch, Metrics stream, ClustersOutput batchClusters, ClustersOutput streamClusters) {
    }

    public static Result run(List<Account> accounts, List<Constraint> constraints, Truth truth,
                             ClusteringConfig config, CostModel costs) {
        ClustersOutput batch = BatchLinker.build(accounts, Constraints.of(constraints), config).clusters();

        LinkState state = BatchLinker.build(List.of(), Constraints.of(constraints), config);
        StreamLinker linker = new StreamLinker(state);
        for (Account account : accounts) {
            linker.assign(account);
        }
        state.validateInvariants();
        ClustersOutput stream = state.clusters();

        return new Result(
                Evaluator.evaluate(batch, truth, constraints, costs),
                Evaluator.evaluate(stream, truth, constraints, costs),
                batch,
                stream);
    }
}
