package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EvaluatorTest {
    /** ring_0 owns r1-r3 (fraud); alice, bob and carol are ordinary single-account customers. */
    private static final Truth TRUTH = new Truth(
            Map.of("r1", "ring_0", "r2", "ring_0", "r3", "ring_0", "alice", "p_alice", "bob", "p_bob", "carol", "p_carol"),
            Set.of("ring_0"),
            Map.of("ring_0", "fraud_ring", "p_alice", "customer", "p_bob", "household_member", "p_carol", "customer"));

    @SafeVarargs // Only reads the lists.
    private static ClustersOutput clusters(List<String>... groups) {
        Cluster[] out = new Cluster[groups.length];
        for (int i = 0; i < groups.length; i++) {
            out[i] = new Cluster("c" + (i + 1), groups[i], 1.0);
        }
        return new ClustersOutput(List.of(out));
    }

    private static Metrics evaluate(ClustersOutput clusters) {
        return Evaluator.evaluate(clusters, TRUTH, List.of(), CostModel.DEFAULT);
    }

    @Test
    void perfectClusteringCostsNothing() {
        Metrics m = evaluate(clusters(List.of("r1", "r2", "r3"), List.of("alice"), List.of("bob"), List.of("carol")));

        assertThat(m.cost()).isZero();
        assertThat(m.fraudListCost()).isZero();
        assertThat(m.wronglyLinkedAccounts()).isZero();
        assertThat(m.fullyRecoveredFraudRings()).isEqualTo(1);
        assertThat(m.pairPrecision()).isEqualTo(1.0);
        assertThat(m.pairRecall()).isEqualTo(1.0);
        assertThat(m.largestCluster()).isEqualTo(3);
        assertThat(m.accountCount()).isEqualTo(6);
        assertThat(m.actorCount()).isEqualTo(4);
        assertThat(m.clusterCount()).isEqualTo(4);
    }

    @Test
    void aSplitRingCostsOneMissedRing() {
        Metrics m = evaluate(clusters(List.of("r1", "r2"), List.of("r3"), List.of("alice"), List.of("bob"), List.of("carol")));

        assertThat(m.splitMultiAccountActors()).isEqualTo(1);
        assertThat(m.splitFraudRings()).isEqualTo(1);
        assertThat(m.fullyRecoveredFraudRings()).isZero();
        assertThat(m.cost()).isEqualTo(2000);
        assertThat(m.truePairCount()).isEqualTo(3);
        assertThat(m.truePositivePairs()).isEqualTo(1);
        assertThat(m.falseNegativePairs()).isEqualTo(2);
        assertThat(m.pairRecall()).isCloseTo(1.0 / 3, within(1e-12));
    }

    @Test
    void customersPulledIntoARingAreWronglyLinkedAndCountedByKind() {
        Metrics m = evaluate(clusters(List.of("r1", "r2", "r3", "alice", "bob"), List.of("carol")));

        assertThat(m.wronglyLinkedAccounts()).isEqualTo(2);
        assertThat(m.wronglyLinkedByKind()).containsExactlyInAnyOrderEntriesOf(Map.of("customer", 1L, "household_member", 1L));
        assertThat(m.nonFraudActorsWithFraud()).isEqualTo(2);
        assertThat(m.cost()).isEqualTo(100);
        assertThat(m.fraudListCost()).isEqualTo(100);
        assertThat(m.falsePositivePairs()).isEqualTo(7);
        assertThat(m.pairPrecision()).isCloseTo(3.0 / 10, within(1e-12));
    }

    @Test
    void customersLinkedOnlyToEachOtherCostTheGeneralViewButNotTheFraudListView() {
        Metrics m = evaluate(clusters(List.of("r1", "r2", "r3"), List.of("alice", "bob", "carol")));

        assertThat(m.wronglyLinkedAccounts()).isEqualTo(2);
        assertThat(m.cost()).isEqualTo(100);
        assertThat(m.nonFraudActorsWithFraud()).isZero();
        assertThat(m.fraudListCost()).isZero();
    }

    @Test
    void pricesAreConfigurable() {
        ClustersOutput split = clusters(List.of("r1", "r2"), List.of("r3", "alice"), List.of("bob"), List.of("carol"));

        Metrics cheapRings = Evaluator.evaluate(split, TRUTH, List.of(), new CostModel(100, 100));

        assertThat(cheapRings.cost()).isEqualTo(100 + 100);
    }

    @Test
    void withNoPredictedPairsPrecisionIsOneAsInTheGoEvaluator() {
        Truth singles = new Truth(Map.of("a", "x", "b", "y"), Set.of(), Map.of());

        Metrics m = Evaluator.evaluate(clusters(List.of("a"), List.of("b")), singles, List.of(), CostModel.DEFAULT);

        assertThat(m.pairPrecision()).isEqualTo(1.0);
        assertThat(m.pairRecall()).isEqualTo(1.0);
        assertThat(m.pairF1()).isEqualTo(1.0);
        assertThat(m.multiAccountActorCount()).isZero();
        assertThat(m.wronglyLinkedByKind()).isEmpty();
    }

    @Test
    void breakdownIsEmptyWhenTheTruthHasNoKinds() {
        Truth noKinds = new Truth(TRUTH.accountToActor(), TRUTH.fraudActorIds(), Map.of());

        Metrics m = Evaluator.evaluate(clusters(List.of("r1", "r2", "r3", "alice"), List.of("bob"), List.of("carol")),
                noKinds, List.of(), CostModel.DEFAULT);

        assertThat(m.wronglyLinkedAccounts()).isEqualTo(1);
        assertThat(m.wronglyLinkedByKind()).isEmpty();
    }

    @Test
    void majorityTieIsBrokenByActorIdForTheBreakdownOnly() {
        // alice (p_alice) and bob (p_bob) tie 1-1: the smaller actor ID is the majority, so bob is counted.
        Metrics m = evaluate(clusters(List.of("alice", "bob"), List.of("r1", "r2", "r3"), List.of("carol")));

        assertThat(m.wronglyLinkedAccounts()).isEqualTo(1);
        assertThat(m.wronglyLinkedByKind()).containsExactlyEntriesOf(Map.of("household_member", 1L));
    }

    @Test
    void rejectsPartitionsThatAreNotComplete() {
        assertThatThrownBy(() -> evaluate(clusters(List.of("r1", "r2", "r3"), List.of("alice"), List.of("bob"))))
                .isInstanceOf(LinkerException.class).hasMessageContaining("incomplete partition: 1");
        assertThatThrownBy(() -> evaluate(clusters(List.of("r1", "r2", "r3", "alice"), List.of("alice", "bob", "carol"))))
                .hasMessageContaining("appears more than once");
        assertThatThrownBy(() -> evaluate(clusters(List.of("r1", "r2", "r3", "alice", "bob", "carol", "mallory"))))
                .hasMessageContaining("unknown account \"mallory\"");
        assertThatThrownBy(() -> evaluate(clusters(List.of("r1", "r2", "r3", "alice", "bob", "carol"), List.of())))
                .hasMessageContaining("contains no accounts");
    }

    @Test
    void rejectsBadClusterIds() {
        ClustersOutput blank = new ClustersOutput(List.of(new Cluster(" ", List.of("r1", "r2", "r3", "alice", "bob", "carol"), 1)));
        ClustersOutput duplicate = new ClustersOutput(List.of(
                new Cluster("c1", List.of("r1", "r2", "r3"), 1), new Cluster("c1", List.of("alice", "bob", "carol"), 1)));

        assertThatThrownBy(() -> evaluate(blank)).hasMessageContaining("empty cluster_id");
        assertThatThrownBy(() -> evaluate(duplicate)).hasMessageContaining("duplicate cluster_id");
    }

    @Test
    void rejectsAClusteringThatBreaksAVerifiedDistinctPair() {
        ClustersOutput merged = clusters(List.of("r1", "r2", "r3"), List.of("alice", "bob"), List.of("carol"));
        List<Constraint> pair = List.of(new Constraint("bob", "alice", "verified_distinct"));

        assertThatThrownBy(() -> Evaluator.evaluate(merged, TRUTH, pair, CostModel.DEFAULT))
                .hasMessageContaining("1 verified-distinct pair(s) share a cluster");
    }

    @Test
    void constraintChecksIgnoreUnlabelledAndDuplicatePairsButRejectBadOnes() {
        ClustersOutput ok = clusters(List.of("r1", "r2", "r3"), List.of("alice"), List.of("bob"), List.of("carol"));
        List<Constraint> fine = List.of(
                new Constraint("alice", "future_account", "verified_distinct"),
                new Constraint("alice", "bob", "verified_distinct"),
                new Constraint("bob", "alice", "verified_distinct"));

        assertThat(Evaluator.evaluate(ok, TRUTH, fine, CostModel.DEFAULT).cost()).isZero();
        assertThatThrownBy(() -> Evaluator.evaluate(ok, TRUTH, List.of(new Constraint("a", "b", "same_person")), CostModel.DEFAULT))
                .hasMessageContaining("unsupported constraint relation");
        assertThatThrownBy(() -> Evaluator.evaluate(ok, TRUTH, List.of(new Constraint("alice", "alice", "verified_distinct")), CostModel.DEFAULT))
                .hasMessageContaining("unsatisfiable");
    }

    @Test
    void costsMustNotBeNegative() {
        assertThatThrownBy(() -> new CostModel(-1, 0)).isInstanceOf(LinkerException.class);
        assertThatThrownBy(() -> new CostModel(0, -1)).isInstanceOf(LinkerException.class);
    }

    @Test
    void chooseTwoCountsPairs() {
        assertThat(Evaluator.chooseTwo(0)).isZero();
        assertThat(Evaluator.chooseTwo(1)).isZero();
        assertThat(Evaluator.chooseTwo(4)).isEqualTo(6);
    }
}
