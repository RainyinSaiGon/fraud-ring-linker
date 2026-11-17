package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs {@link TakeoverEvaluation} over the main takeover thresholds and several
 * datasets. Each threshold is varied on its own, with the others at the current
 * setting, and every value is tried at every flag threshold. A full grid of five
 * thresholds would be hundreds of settings for little more insight.
 */
public final class TakeoverSweep {
    /** The thresholds the sweep varies, besides the flag threshold. */
    public enum Knob {
        MIN_HISTORY("history"), MAX_SPEED_KMH("speed km/h"), BURST_FAILURES("burst"),
        SHARED_DEVICE_ACCOUNTS("shared device");

        public final String label;

        Knob(String label) {
            this.label = label;
        }

        /** This threshold's value in {@code c}. */
        public double valueIn(TakeoverConfig c) {
            return switch (this) {
                case MIN_HISTORY -> c.minHistory();
                case MAX_SPEED_KMH -> c.maxSpeedKmh();
                case BURST_FAILURES -> c.burstFailures();
                case SHARED_DEVICE_ACCOUNTS -> c.sharedDeviceAccounts();
            };
        }

        TakeoverConfig with(TakeoverConfig c, double value, double flagThreshold) {
            int whole = (int) value;
            return new TakeoverConfig(
                    this == MIN_HISTORY ? whole : c.minHistory(),
                    this == MAX_SPEED_KMH ? value : c.maxSpeedKmh(),
                    c.minTravelKm(),
                    this == BURST_FAILURES ? whole : c.burstFailures(),
                    c.burstWindow(),
                    this == SHARED_DEVICE_ACCOUNTS ? whole : c.sharedDeviceAccounts(),
                    c.sharedDeviceWindow(), c.incidentWindow(), c.newDeviceWeight(), c.impossibleTravelWeight(),
                    c.burstThenSuccessWeight(), c.burstOnlyWeight(), c.sharedDeviceWeight(), flagThreshold);
        }
    }

    private TakeoverSweep() {
    }

    /** One setting, with its results summed over every dataset. */
    public record Point(Knob knob, double value, double flagThreshold, TakeoverConfig config,
                        TakeoverEvaluation.Result result) {

        public long cost(TakeoverCostModel prices) {
            return result.cost(prices);
        }

        boolean isAt(TakeoverConfig current) {
            return config.equals(current);
        }
    }

    /**
     * For each knob in order, each of its values, each flag threshold: one point.
     * The same setting reached through two knobs is scored once.
     */
    public static List<Point> run(List<TakeoverEvaluation.Dataset> datasets, Map<Knob, List<Double>> values,
                                  List<Double> thresholds, TakeoverConfig base) {
        if (datasets.isEmpty() || thresholds.isEmpty() || values.values().stream().anyMatch(List::isEmpty)) {
            throw new LinkerException("a takeover sweep needs at least one dataset and one value of each threshold");
        }
        List<TakeoverEvaluation.Prepared> prepared = datasets.stream().map(TakeoverEvaluation.Prepared::new).toList();
        Map<TakeoverConfig, TakeoverEvaluation.Result> seen = new HashMap<>();
        List<Point> points = new ArrayList<>();
        new LinkedHashMap<>(values).forEach((knob, list) -> {
            for (double value : list) {
                for (double threshold : thresholds) {
                    TakeoverConfig config = knob.with(base, value, threshold);
                    TakeoverEvaluation.Result result = seen.computeIfAbsent(config, c -> {
                        TakeoverEvaluation.Result total = TakeoverEvaluation.empty();
                        for (TakeoverEvaluation.Prepared dataset : prepared) {
                            total = total.plus(TakeoverEvaluation.evaluate(dataset, c));
                        }
                        return total;
                    });
                    points.add(new Point(knob, value, threshold, config, result));
                }
            }
        });
        return points;
    }

    /**
     * The cheapest setting at these prices. Ties go to the current setting, then to
     * the nearest one, so a tie never suggests a change.
     */
    public static Point cheapest(List<Point> points, TakeoverCostModel prices, TakeoverConfig current) {
        return points.stream().min(Comparator
                        .comparingLong((Point p) -> p.cost(prices))
                        .thenComparing(p -> !p.isAt(current))
                        .thenComparingDouble(p -> distance(p, current)))
                .orElseThrow();
    }

    /** How far a point moved from the current setting, each threshold as a share of its current value. */
    private static double distance(Point p, TakeoverConfig current) {
        double now = p.knob().valueIn(current);
        return Math.abs(p.value() - now) / now + Math.abs(p.flagThreshold() - current.flagThreshold());
    }
}
