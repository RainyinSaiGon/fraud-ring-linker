package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.mules.MuleConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs {@link MuleEvaluation} over a grid of mule thresholds and several datasets.
 * Clusters are built once per dataset; only scoring repeats.
 */
public final class MuleSweep {
    private MuleSweep() {
    }

    /** One setting, with its results summed over every dataset. */
    public record Point(int fanInMinPayers, double passThroughMinShare, double flagThreshold,
                        MuleEvaluation.Result result) {

        public long cost(MuleCostModel prices) {
            return result.cost(prices);
        }

        boolean isAt(MuleConfig config) {
            return fanInMinPayers == config.fanInMinPayers() && passThroughMinShare == config.passThroughMinShare()
                    && flagThreshold == config.flagThreshold();
        }
    }

    /** Every (payers, share, threshold) combination, in the order given; other settings come from {@code base}. */
    public static List<Point> run(List<MuleEvaluation.Dataset> datasets, List<Integer> payers, List<Double> shares,
                                  List<Double> thresholds, MuleConfig base) {
        if (datasets.isEmpty() || payers.isEmpty() || shares.isEmpty() || thresholds.isEmpty()) {
            throw new LinkerException("a mule sweep needs at least one dataset and one value of each threshold");
        }
        List<MuleEvaluation.Prepared> prepared = datasets.stream().map(MuleEvaluation.Prepared::new).toList();
        List<Point> points = new ArrayList<>();
        for (int p : payers) {
            for (double share : shares) {
                for (double threshold : thresholds) {
                    MuleConfig config = new MuleConfig(p, base.fanInWindow(), share, base.passThroughWindow(),
                            base.fanInWeight(), base.passThroughWeight(), base.chainWeight(), threshold);
                    MuleEvaluation.Result total = MuleEvaluation.empty();
                    for (MuleEvaluation.Prepared dataset : prepared) {
                        total = total.plus(MuleEvaluation.evaluate(dataset, config));
                    }
                    points.add(new Point(p, share, threshold, total));
                }
            }
        }
        return points;
    }

    /**
     * The cheapest setting at these prices. Ties go to the current setting, then to
     * the nearest one, so a tie never suggests a change.
     */
    public static Point cheapest(List<Point> points, MuleCostModel prices, MuleConfig current) {
        return points.stream().min(Comparator
                        .comparingLong((Point p) -> p.cost(prices))
                        .thenComparing(p -> !p.isAt(current))
                        .thenComparingDouble(p -> distance(p, current)))
                .orElseThrow();
    }

    /** Payers count in tenths so one payer weighs like a 0.1 change in a share or threshold. */
    private static double distance(Point p, MuleConfig current) {
        return Math.abs(p.fanInMinPayers() - current.fanInMinPayers()) / 10.0
                + Math.abs(p.passThroughMinShare() - current.passThroughMinShare())
                + Math.abs(p.flagThreshold() - current.flagThreshold());
    }
}
