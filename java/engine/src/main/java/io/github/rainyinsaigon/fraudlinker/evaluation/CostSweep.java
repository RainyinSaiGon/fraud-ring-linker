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
     */
    public record Point(double minMergeScore, double streamThreshold, long batchWrong, long batchSplit,
                        long streamWrong, long streamSplit) {

        public long batchCost(CostModel costs) {
            return batchWrong * costs.wrongLinkCost() + batchSplit * costs.missedRingCost();
        }

        public long streamCost(CostModel costs) {
            return streamWrong * costs.wrongLinkCost() + streamSplit * costs.missedRingCost();
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
                for (Dataset d : datasets) {
                    CostReport.Result r = CostReport.run(d.accounts(), d.constraints(), d.truth(), config, CostModel.DEFAULT);
                    batchWrong += r.batch().wronglyLinkedAccounts();
                    batchSplit += r.batch().splitMultiAccountActors();
                    streamWrong += r.stream().wronglyLinkedAccounts();
                    streamSplit += r.stream().splitMultiAccountActors();
                }
                points.add(new Point(merge, stream, batchWrong, batchSplit, streamWrong, streamSplit));
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
