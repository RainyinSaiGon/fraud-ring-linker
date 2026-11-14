package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs {@link CostReport} over a grid of thresholds and several labelled datasets.
 *
 * <p>Mistake counts do not depend on prices, so each setting is linked once and
 * can then be priced at any cost ratio with {@link Point#batchCost} and
 * {@link Point#streamCost}.
 */
public final class CostSweep {
    private CostSweep() {
    }

    /** One labelled dataset, for example one simulator seed. */
    public record Dataset(String name, List<Account> accounts, List<Constraint> constraints, Truth truth) {
    }

    /**
     * Mistakes at one setting, summed over every dataset.
     *
     * @param batchWrong  wrongly linked accounts in batch mode
     * @param batchSplit  multi-account actors split in batch mode
     * @param streamWrong wrongly linked accounts in stream mode
     * @param streamSplit multi-account actors split in stream mode
     * @param proposalWrong wrongly linked accounts in stream mode with every merge proposal accepted
     * @param proposalSplit multi-account actors split in stream mode with every merge proposal accepted
     * @param proposals     merge proposals opened, which is the analyst's review workload
     */
    public record Point(double minMergeScore, double streamThreshold, long batchWrong, long batchSplit,
                        long streamWrong, long streamSplit, long proposalWrong, long proposalSplit,
                        long proposals) {

        /** A point with no proposal results, for tests that only look at batch and stream. */
        public Point(double minMergeScore, double streamThreshold, long batchWrong, long batchSplit,
                     long streamWrong, long streamSplit) {
            this(minMergeScore, streamThreshold, batchWrong, batchSplit, streamWrong, streamSplit, 0, 0, 0);
        }

        public long batchCost(CostModel costs) {
            return batchWrong * costs.wrongLinkCost() + batchSplit * costs.missedRingCost();
        }

        public long streamCost(CostModel costs) {
            return streamWrong * costs.wrongLinkCost() + streamSplit * costs.missedRingCost();
        }

        public long proposalCost(CostModel costs) {
            return proposalWrong * costs.wrongLinkCost() + proposalSplit * costs.missedRingCost();
        }

        boolean isAt(ClusteringConfig config) {
            return minMergeScore == config.scoring().minMergeScore() && streamThreshold == config.streamThreshold();
        }
    }

    /** Links every dataset at every (merge score, stream threshold) pair, in the order given. */
    public static List<Point> run(List<Dataset> datasets, List<Double> mergeScores, List<Double> streamThresholds,
                                  ClusteringConfig base) {
        if (datasets.isEmpty() || mergeScores.isEmpty() || streamThresholds.isEmpty()) {
            throw new LinkerException("a sweep needs at least one dataset, merge score and stream threshold");
        }
        List<Point> points = new ArrayList<>();
        for (double merge : mergeScores) {
            for (double stream : streamThresholds) {
                ClusteringConfig config = base.withThresholds(merge, stream);
                long batchWrong = 0;
                long batchSplit = 0;
                long streamWrong = 0;
                long streamSplit = 0;
                long proposalWrong = 0;
                long proposalSplit = 0;
                long proposals = 0;
                for (Dataset d : datasets) {
                    CostReport.Result r = CostReport.run(d.accounts(), d.constraints(), d.truth(), config, CostModel.DEFAULT);
                    batchWrong += r.batch().wronglyLinkedAccounts();
                    batchSplit += r.batch().splitMultiAccountActors();
                    streamWrong += r.stream().wronglyLinkedAccounts();
                    streamSplit += r.stream().splitMultiAccountActors();
                    proposalWrong += r.streamWithProposals().wronglyLinkedAccounts();
                    proposalSplit += r.streamWithProposals().splitMultiAccountActors();
                    proposals += r.proposals();
                }
                points.add(new Point(merge, stream, batchWrong, batchSplit, streamWrong, streamSplit,
                        proposalWrong, proposalSplit, proposals));
            }
        }
        return points;
    }

    /**
     * The cheapest stream setting at these prices. Ties go to the current
     * setting, then to the setting nearest to it, so a tie never suggests a change.
     */
    public static Point cheapestStream(List<Point> points, CostModel costs, ClusteringConfig current) {
        return points.stream().min(Comparator
                        .comparingLong((Point p) -> p.streamCost(costs))
                        .thenComparing(p -> !p.isAt(current))
                        .thenComparingDouble(p -> distance(p, current)))
                .orElseThrow();
    }

    /** The cheapest merge score for batch linking, which ignores the stream threshold; ties as above. */
    public static Point cheapestBatch(List<Point> points, CostModel costs, ClusteringConfig current) {
        return points.stream().min(Comparator
                        .comparingLong((Point p) -> p.batchCost(costs))
                        .thenComparingDouble(p -> Math.abs(p.minMergeScore() - current.scoring().minMergeScore()))
                        .thenComparingDouble(p -> distance(p, current)))
                .orElseThrow();
    }

    private static double distance(Point p, ClusteringConfig current) {
        return Math.abs(p.minMergeScore() - current.scoring().minMergeScore())
                + Math.abs(p.streamThreshold() - current.streamThreshold());
    }
}
