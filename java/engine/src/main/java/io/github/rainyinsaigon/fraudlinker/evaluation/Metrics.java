package io.github.rainyinsaigon.fraudlinker.evaluation;

import java.util.Map;
import java.util.TreeMap;

/**
 * How a clustering compares with the ground truth, and what its mistakes cost.
 *
 * <p>Definitions match the original Go evaluator ({@code go/internal/evaluation}),
 * so the numbers can be compared directly:
 * <ul>
 *   <li><b>wronglyLinkedAccounts</b> (Go: impurity): in each cluster, the accounts
 *       outside the cluster's largest true actor.</li>
 *   <li><b>splitMultiAccountActors</b>: actors with 2+ accounts spread over more than one cluster.</li>
 *   <li><b>cost</b> = wronglyLinkedAccounts x wrong-link cost + splitMultiAccountActors x missed-ring cost
 *       (Go: general proxy cost).</li>
 *   <li><b>fraudListCost</b> = non-fraud actors sharing a cluster with fraud x wrong-link cost
 *       + split fraud rings x missed-ring cost (Go: literal proxy cost).</li>
 * </ul>
 *
 * @param wronglyLinkedByKind wrongly linked accounts per actor kind (empty when the truth has no kinds)
 */
public record Metrics(
        int accountCount,
        int actorCount,
        int clusterCount,
        long truePairCount,
        long predictedPairCount,
        long truePositivePairs,
        double pairPrecision,
        double pairRecall,
        double pairF1,
        long wronglyLinkedAccounts,
        Map<String, Long> wronglyLinkedByKind,
        int multiAccountActorCount,
        int fullyRecoveredMultiAccountActors,
        int splitMultiAccountActors,
        int fraudRingCount,
        int fullyRecoveredFraudRings,
        int splitFraudRings,
        int nonFraudActorsWithFraud,
        int largestCluster,
        long cost,
        long fraudListCost) {

    public Metrics {
        wronglyLinkedByKind = Map.copyOf(new TreeMap<>(wronglyLinkedByKind));
    }

    public long falsePositivePairs() {
        return predictedPairCount - truePositivePairs;
    }

    public long falseNegativePairs() {
        return truePairCount - truePositivePairs;
    }
}
