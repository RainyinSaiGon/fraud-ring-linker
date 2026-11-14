package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Scores a clustering against the ground truth. A port of the Go evaluator: same
 * validation, same metrics, same cost formulas, with the two prices configurable.
 */
public final class Evaluator {
    private Evaluator() {
    }

    /**
     * @throws LinkerException if the clustering is not a complete partition of the
     *                         truth's accounts, or puts a verified-distinct pair together
     */
    public static Metrics evaluate(ClustersOutput clusters, Truth truth, List<Constraint> constraints, CostModel costs) {
        Map<String, List<String>> actorMembers = new HashMap<>();
        truth.accountToActor().forEach((account, actor) ->
                actorMembers.computeIfAbsent(actor, ignored -> new ArrayList<>()).add(account));

        Partition partition = Partition.of(clusters, truth.accountToActor());
        int violations = constraintViolations(constraints, truth.accountToActor(), partition.clusterByAccount());
        if (violations != 0) {
            throw new LinkerException("constraint validation failed: " + violations
                    + " verified-distinct pair(s) share a cluster");
        }

        long truePairs = 0;
        for (List<String> members : actorMembers.values()) {
            truePairs += chooseTwo(members.size());
        }

        long predictedPairs = 0;
        long truePositivePairs = 0;
        long wronglyLinked = 0;
        Map<String, Long> wronglyLinkedByKind = new TreeMap<>();
        Set<String> nonFraudWithFraud = new HashSet<>();
        for (int i = 0; i < clusters.clusters().size(); i++) {
            Map<String, Integer> actorCounts = partition.actorCounts().get(i);
            long size = 0;
            boolean containsFraud = false;
            for (Map.Entry<String, Integer> entry : actorCounts.entrySet()) {
                size += entry.getValue();
                truePositivePairs += chooseTwo(entry.getValue());
                containsFraud |= truth.fraudActorIds().contains(entry.getKey());
            }
            predictedPairs += chooseTwo(size);

            String majority = majorityActor(actorCounts);
            for (String account : clusters.clusters().get(i).accountIds()) {
                if (!truth.accountToActor().get(account).equals(majority)) {
                    wronglyLinked++;
                    wronglyLinkedByKind.merge(truth.kindOfAccount(account), 1L, Long::sum);
                }
            }
            if (containsFraud) {
                for (String actor : actorCounts.keySet()) {
                    if (!truth.fraudActorIds().contains(actor)) {
                        nonFraudWithFraud.add(actor);
                    }
                }
            }
        }
        if (truth.actorKinds().isEmpty()) {
            wronglyLinkedByKind.clear();
        }

        int multiAccount = 0;
        int splitMulti = 0;
        int fraudRings = 0;
        int splitRings = 0;
        for (Map.Entry<String, List<String>> actor : actorMembers.entrySet()) {
            if (actor.getValue().size() <= 1) {
                continue;
            }
            multiAccount++;
            Set<Integer> clustersOfActor = new HashSet<>();
            for (String account : actor.getValue()) {
                clustersOfActor.add(partition.clusterByAccount().get(account));
            }
            boolean split = clustersOfActor.size() > 1;
            if (split) {
                splitMulti++;
            }
            if (truth.fraudActorIds().contains(actor.getKey())) {
                fraudRings++;
                if (split) {
                    splitRings++;
                }
            }
        }

        double precision = ratio(truePositivePairs, predictedPairs);
        double recall = ratio(truePositivePairs, truePairs);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        long cost = costs.wrongLinkCost() * wronglyLinked + costs.missedRingCost() * splitMulti;
        long fraudListCost = costs.wrongLinkCost() * nonFraudWithFraud.size() + costs.missedRingCost() * splitRings;

        return new Metrics(
                truth.accountToActor().size(),
                actorMembers.size(),
                clusters.clusters().size(),
                truePairs,
                predictedPairs,
                truePositivePairs,
                precision,
                recall,
                f1,
                wronglyLinked,
                wronglyLinkedByKind,
                multiAccount,
                multiAccount - splitMulti,
                splitMulti,
                fraudRings,
                fraudRings - splitRings,
                splitRings,
                nonFraudWithFraud.size(),
                partition.largestCluster(),
                cost,
                fraudListCost);
    }

    /**
     * The actor with the most accounts in a cluster; ties go to the smaller actor ID
     * so the per-kind breakdown is deterministic. (The totals don't depend on the tie-break.)
     */
    private static String majorityActor(Map<String, Integer> actorCounts) {
        return actorCounts.entrySet().stream()
                .max(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElseThrow();
    }

    private record Partition(Map<String, Integer> clusterByAccount, List<Map<String, Integer>> actorCounts,
                             int largestCluster) {

        static Partition of(ClustersOutput clusters, Map<String, String> accountToActor) {
            Map<String, Integer> clusterByAccount = new HashMap<>();
            List<Map<String, Integer>> actorCounts = new ArrayList<>();
            Set<String> clusterIds = new HashSet<>();
            int largest = 0;
            for (int i = 0; i < clusters.clusters().size(); i++) {
                Cluster cluster = clusters.clusters().get(i);
                if (cluster.clusterId().isBlank()) {
                    throw new LinkerException("cluster at index " + i + " has an empty cluster_id");
                }
                if (!clusterIds.add(cluster.clusterId())) {
                    throw new LinkerException("duplicate cluster_id \"" + cluster.clusterId() + "\"");
                }
                if (cluster.accountIds().isEmpty()) {
                    throw new LinkerException("cluster \"" + cluster.clusterId() + "\" contains no accounts");
                }
                largest = Math.max(largest, cluster.accountIds().size());
                Map<String, Integer> counts = new HashMap<>();
                for (String account : cluster.accountIds()) {
                    String actor = accountToActor.get(account);
                    if (actor == null) {
                        throw new LinkerException("cluster \"" + cluster.clusterId() + "\" contains unknown account \""
                                + account + "\"");
                    }
                    Integer previous = clusterByAccount.putIfAbsent(account, i);
                    if (previous != null) {
                        throw new LinkerException("account \"" + account + "\" appears more than once (clusters \""
                                + clusters.clusters().get(previous).clusterId() + "\" and \"" + cluster.clusterId()
                                + "\")");
                    }
                    counts.merge(actor, 1, Integer::sum);
                }
                actorCounts.add(counts);
            }
            if (clusterByAccount.size() != accountToActor.size()) {
                long missing = accountToActor.keySet().stream().filter(a -> !clusterByAccount.containsKey(a)).count();
                throw new LinkerException("incomplete partition: " + missing + " truth account(s) missing");
            }
            return new Partition(clusterByAccount, actorCounts, largest);
        }
    }

    private static int constraintViolations(List<Constraint> constraints, Map<String, String> accountToActor,
                                            Map<String, Integer> clusterByAccount) {
        Set<String> seen = new HashSet<>();
        int violations = 0;
        for (Constraint constraint : constraints) {
            if (!Constraints.VERIFIED_DISTINCT.equals(constraint.relation())) {
                throw new LinkerException("unsupported constraint relation \"" + constraint.relation() + "\"");
            }
            if (constraint.accountA().equals(constraint.accountB())) {
                throw new LinkerException("self constraint for account \"" + constraint.accountA()
                        + "\" is unsatisfiable");
            }
            // Constraints may name accounts the truth does not label (for example, future
            // stream accounts); only labelled endpoints can be checked.
            if (!accountToActor.containsKey(constraint.accountA()) || !accountToActor.containsKey(constraint.accountB())) {
                continue;
            }
            String left = constraint.accountA().compareTo(constraint.accountB()) <= 0 ? constraint.accountA() : constraint.accountB();
            String right = left.equals(constraint.accountA()) ? constraint.accountB() : constraint.accountA();
            if (!seen.add(left + "\u0000" + right)) {
                continue;
            }
            if (clusterByAccount.get(left).equals(clusterByAccount.get(right))) {
                violations++;
            }
        }
        return violations;
    }

    static long chooseTwo(long n) {
        return n < 2 ? 0 : n * (n - 1) / 2;
    }

    /** As in the Go evaluator: with nothing to measure (for example no predicted pairs), the ratio is 1. */
    static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 1 : (double) numerator / denominator;
    }
}
