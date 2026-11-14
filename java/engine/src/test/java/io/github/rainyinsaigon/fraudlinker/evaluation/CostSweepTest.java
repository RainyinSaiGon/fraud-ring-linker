package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.rainyinsaigon.fraudlinker.Fixtures.account;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostSweepTest {
    private static final Instant T0 = Instant.parse("2026-11-01T09:00:00Z");
    private static final ClusteringConfig CURRENT = ClusteringConfig.defaults();

    /** The ring that batch linking catches and streaming splits (see TruthAndCostReportTest). */
    private static CostSweep.Dataset ring() {
        List<Account> accounts = List.of(
                account("r1", "maria.lopez@x.com", "phone-1", "10.1.0.1", "", T0),
                account("r2", "zz.other@y.com", "", "10.2.0.1", "card-1", T0.plusSeconds(60)),
                account("r3", "maria.lopes@x.com", "phone-1", "10.3.0.1", "card-1", T0.plusSeconds(120)));
        Truth truth = new Truth(Map.of("r1", "ring_0", "r2", "ring_0", "r3", "ring_0"), Set.of("ring_0"),
                Map.of("ring_0", "fraud_ring"));
        return new CostSweep.Dataset("ring", accounts, List.of(), truth);
    }

    @Test
    void withThresholdsChangesOnlyTheTwoThresholds() {
        ClusteringConfig changed = CURRENT.withThresholds(0.55, 0.9);

        assertThat(changed.scoring().minMergeScore()).isEqualTo(0.55);
        assertThat(changed.streamThreshold()).isEqualTo(0.9);
        assertThat(changed.index()).isEqualTo(CURRENT.index());
        assertThat(changed.scoring().withMinMergeScore(0.70)).isEqualTo(CURRENT.scoring());
    }

    @Test
    void sumsMistakesOverDatasetsForEverySettingInGridOrder() {
        List<CostSweep.Point> points = CostSweep.run(List.of(ring(), ring()), List.of(0.70, 1.0), List.of(0.82, 1.0), CURRENT);

        assertThat(points).extracting(CostSweep.Point::minMergeScore).containsExactly(0.70, 0.70, 1.0, 1.0);
        assertThat(points).extracting(CostSweep.Point::streamThreshold).containsExactly(0.82, 1.0, 0.82, 1.0);
        CostSweep.Point current = points.get(0);
        assertThat(current.batchWrong()).isZero();
        assertThat(current.batchSplit()).isZero();
        assertThat(current.streamWrong()).isZero();
        assertThat(current.streamSplit()).isEqualTo(2);
        // A merge score of 1 links nothing, so the ring is split in both modes on both datasets.
        assertThat(points.get(3).batchSplit()).isEqualTo(2);
        assertThat(points.get(3).streamSplit()).isEqualTo(2);
    }

    @Test
    void rejectsAnEmptySweep() {
        assertThatThrownBy(() -> CostSweep.run(List.of(), List.of(0.7), List.of(0.8), CURRENT))
                .isInstanceOf(LinkerException.class);
        assertThatThrownBy(() -> CostSweep.run(List.of(ring()), List.of(), List.of(0.8), CURRENT))
                .isInstanceOf(LinkerException.class);
        assertThatThrownBy(() -> CostSweep.run(List.of(ring()), List.of(0.7), List.of(), CURRENT))
                .isInstanceOf(LinkerException.class);
    }

    @Test
    void pricesAPointAtAnyCostRatio() {
        CostSweep.Point p = new CostSweep.Point(0.7, 0.82, 3, 1, 5, 2);

        assertThat(p.batchCost(CostModel.DEFAULT)).isEqualTo(3 * 50 + 2000);
        assertThat(p.streamCost(CostModel.DEFAULT)).isEqualTo(5 * 50 + 2 * 2000);
        assertThat(p.streamCost(new CostModel(1, 0))).isEqualTo(5);
    }

    @Test
    void cheapestPicksTheLowestCostAndTheCurrentSettingOnATie() {
        List<CostSweep.Point> points = List.of(
                new CostSweep.Point(0.60, 0.70, 0, 1, 0, 1),
                new CostSweep.Point(0.70, 0.82, 0, 1, 0, 1),
                new CostSweep.Point(0.80, 0.90, 0, 1, 0, 1));

        assertThat(CostSweep.cheapestStream(points, CostModel.DEFAULT, CURRENT)).isSameAs(points.get(1));
        assertThat(CostSweep.cheapestBatch(points, CostModel.DEFAULT, CURRENT)).isSameAs(points.get(1));

        List<CostSweep.Point> cheaper = List.of(points.get(1), new CostSweep.Point(0.80, 0.90, 4, 0, 4, 0));
        assertThat(CostSweep.cheapestStream(cheaper, CostModel.DEFAULT, CURRENT)).isSameAs(cheaper.get(1));
        assertThat(CostSweep.cheapestBatch(cheaper, CostModel.DEFAULT, CURRENT)).isSameAs(cheaper.get(1));
        // When wrong links cost 40 times a missed ring, four wrong links lose.
        assertThat(CostSweep.cheapestStream(cheaper, new CostModel(2000, 50), CURRENT)).isSameAs(cheaper.get(0));
    }

    @Test
    void withoutTheCurrentSettingTiesGoToTheNearestOne() {
        List<CostSweep.Point> points = List.of(
                new CostSweep.Point(0.50, 0.82, 0, 0, 0, 0),
                new CostSweep.Point(0.70, 0.94, 0, 0, 0, 0),
                new CostSweep.Point(0.70, 0.86, 0, 0, 0, 0),
                new CostSweep.Point(0.75, 0.82, 0, 0, 0, 0));

        assertThat(CostSweep.cheapestStream(points, CostModel.DEFAULT, CURRENT)).isSameAs(points.get(2));
        // Batch ignores the stream threshold, so any 0.70 row is nearest; then the stream distance decides.
        assertThat(CostSweep.cheapestBatch(points, CostModel.DEFAULT, CURRENT)).isSameAs(points.get(2));
    }
}
