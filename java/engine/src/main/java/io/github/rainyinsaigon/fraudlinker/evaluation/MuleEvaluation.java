package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.mules.MuleConfig;
import io.github.rainyinsaigon.fraudlinker.mules.MuleReason;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScore;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScorer;
import io.github.rainyinsaigon.fraudlinker.mules.Transfer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Scores a labelled dataset's transfers and compares the flags with the planted
 * mules. Only accounts the bank holds (those in {@code accounts.jsonl}) count,
 * as in the service: nobody can review an outside account.
 *
 * <p>"Related" comes from batch linking with hindsight. The service links in
 * arrival order, which can only leave more accounts unrelated.
 */
public final class MuleEvaluation {
    /** Key for a false flag on an account that is no hard legitimate case. */
    public static final String OTHER = "other";

    private MuleEvaluation() {
    }

    /** One labelled dataset with transfers, for example one simulator seed. */
    public record Dataset(String name, List<Account> accounts, List<Constraint> constraints, List<Transfer> transfers,
                          MuleTruth truth) {
    }

    /**
     * What one setting found.
     *
     * @param missedMules         planted mules that were not flagged, sorted
     * @param falseFlagsByCase    legitimate accounts flagged, by hard case or {@link #OTHER}
     * @param falseFlagsByReasons legitimate accounts flagged, by the reasons that fired, such as {@code fan_in+chain}
     * @param hardCasesByCase     hard legitimate accounts in the truth, by case
     */
    public record Result(int mulesPlanted, int mulesFlagged, List<String> missedMules, int falseFlags,
                         Map<String, Integer> falseFlagsByCase, Map<String, Integer> falseFlagsByReasons,
                         Map<String, Integer> hardCasesByCase) {

        /** Share of planted mules flagged; 1 when none were planted. */
        public double recall() {
            return mulesPlanted == 0 ? 1 : (double) mulesFlagged / mulesPlanted;
        }

        /** Share of flags that are mules; 1 when nothing was flagged. */
        public double precision() {
            int flagged = mulesFlagged + falseFlags;
            return flagged == 0 ? 1 : (double) mulesFlagged / flagged;
        }

        public long cost(MuleCostModel prices) {
            return falseFlags * prices.falseFlagCost() + (long) (mulesPlanted - mulesFlagged) * prices.missedMuleCost();
        }

        Result plus(Result other) {
            List<String> missed = new ArrayList<>(missedMules);
            missed.addAll(other.missedMules);
            missed.sort(null);
            return new Result(mulesPlanted + other.mulesPlanted, mulesFlagged + other.mulesFlagged, missed,
                    falseFlags + other.falseFlags, add(falseFlagsByCase, other.falseFlagsByCase),
                    add(falseFlagsByReasons, other.falseFlagsByReasons), add(hardCasesByCase, other.hardCasesByCase));
        }

        private static Map<String, Integer> add(Map<String, Integer> a, Map<String, Integer> b) {
            Map<String, Integer> sum = new TreeMap<>(a);
            b.forEach((key, n) -> sum.merge(key, n, Integer::sum));
            return sum;
        }
    }

    /** Clusters and held accounts for one dataset, computed once and reused at every setting. */
    static final class Prepared {
        final Dataset dataset;
        final BiPredicate<String, String> related;
        final Set<String> held = new HashSet<>();

        Prepared(Dataset dataset) {
            this.dataset = dataset;
            LinkState state = BatchLinker.build(dataset.accounts(), Constraints.of(dataset.constraints()),
                    ClusteringConfig.defaults());
            this.related = MuleScorer.sameCluster(state);
            dataset.accounts().forEach(account -> held.add(account.accountId()));
        }
    }

    public static Result evaluate(Dataset dataset, MuleConfig config) {
        return evaluate(new Prepared(dataset), config);
    }

    /** The results of every dataset, added together. */
    public static Result evaluateAll(List<Dataset> datasets, MuleConfig config) {
        Result total = empty();
        for (Dataset dataset : datasets) {
            total = total.plus(evaluate(dataset, config));
        }
        return total;
    }

    static Result evaluate(Prepared prepared, MuleConfig config) {
        MuleTruth truth = prepared.dataset.truth();
        Map<String, MuleScore> scores = new MuleScorer(config).scoreAll(prepared.dataset.transfers(), prepared.related);
        List<String> missed = new ArrayList<>();
        int found = 0;
        for (String mule : truth.muleAccountIds()) {
            MuleScore score = scores.get(mule);
            if (score != null && score.flagged()) {
                found++;
            } else {
                missed.add(mule);
            }
        }
        missed.sort(null);
        Map<String, Integer> byCase = new TreeMap<>();
        Map<String, Integer> byReasons = new TreeMap<>();
        int falseFlags = 0;
        for (MuleScore score : scores.values()) {
            String id = score.accountId();
            if (!score.flagged() || !prepared.held.contains(id) || truth.muleAccountIds().contains(id)) {
                continue;
            }
            falseFlags++;
            byCase.merge(truth.caseOf().getOrDefault(id, OTHER), 1, Integer::sum);
            byReasons.merge(score.reasons().stream().map(MuleReason::code).collect(Collectors.joining("+")), 1,
                    Integer::sum);
        }
        Map<String, Integer> hardCases = new TreeMap<>();
        truth.caseOf().values().forEach(kind -> hardCases.merge(kind, 1, Integer::sum));
        return new Result(truth.muleAccountIds().size(), found, missed, falseFlags, byCase, byReasons, hardCases);
    }

    static Result empty() {
        return new Result(0, 0, List.of(), 0, Map.of(), Map.of(), Map.of());
    }
}
