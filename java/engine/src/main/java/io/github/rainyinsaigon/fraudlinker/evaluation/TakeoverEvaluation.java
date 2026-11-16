package io.github.rainyinsaigon.fraudlinker.evaluation;

import io.github.rainyinsaigon.fraudlinker.clustering.BatchLinker;
import io.github.rainyinsaigon.fraudlinker.clustering.ClusteringConfig;
import io.github.rainyinsaigon.fraudlinker.clustering.Constraints;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Constraint;
import io.github.rainyinsaigon.fraudlinker.mules.MuleScorer;
import io.github.rainyinsaigon.fraudlinker.takeover.Login;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverConfig;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverReason;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverScore;
import io.github.rainyinsaigon.fraudlinker.takeover.TakeoverScorer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Scores a labelled dataset's logins and compares the flags with the planted
 * takeovers. Only accounts the bank holds (those in {@code accounts.jsonl}) count,
 * as in the service.
 *
 * <p>"How soon" is the number of successful attacker logins (sessions) at or
 * before the moment the account's worst incident reached its score: 1 means the
 * flag came with the attacker's first session. A flag that came before any
 * attacker session, from a failed-login burst, counts as 0.
 *
 * <p>"Related", for the shared-device signal, comes from batch linking with
 * hindsight, as in {@link MuleEvaluation}.
 */
public final class TakeoverEvaluation {
    /** Key for a false flag on an account that is no hard legitimate case. */
    public static final String OTHER = "other";

    private TakeoverEvaluation() {
    }

    /** One labelled dataset with logins, for example one simulator seed. */
    public record Dataset(String name, List<Account> accounts, List<Constraint> constraints, List<Login> logins,
                          TakeoverTruth truth) {
    }

    /**
     * What one setting found.
     *
     * @param plantedByKind       planted takeovers by kind, such as {@code phishing}
     * @param flaggedByKind       planted takeovers flagged, by kind
     * @param missed              planted takeovers not flagged, as {@code account (kind)}, sorted
     * @param sessionsBeforeFlag  flagged takeovers by the number of attacker sessions up to the flag
     * @param falseFlagsByCase    legitimate accounts flagged, by hard case or {@link #OTHER}
     * @param falseFlagsByReasons legitimate accounts flagged, by the reasons that fired, such as {@code new_device+burst}
     * @param hardCasesByCase     hard legitimate accounts in the truth, by case
     */
    public record Result(Map<String, Integer> plantedByKind, Map<String, Integer> flaggedByKind, List<String> missed,
                         Map<Integer, Integer> sessionsBeforeFlag, int falseFlags,
                         Map<String, Integer> falseFlagsByCase, Map<String, Integer> falseFlagsByReasons,
                         Map<String, Integer> hardCasesByCase) {

        public int planted() {
            return plantedByKind.values().stream().mapToInt(Integer::intValue).sum();
        }

        public int flagged() {
            return flaggedByKind.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** Share of planted takeovers flagged; 1 when none were planted. */
        public double recall() {
            return planted() == 0 ? 1 : (double) flagged() / planted();
        }

        /** Share of flags that are takeovers; 1 when nothing was flagged. */
        public double precision() {
            int all = flagged() + falseFlags;
            return all == 0 ? 1 : (double) flagged() / all;
        }

        /** Every flag an analyst has to look at. */
        public int reviews() {
            return flagged() + falseFlags;
        }

        /** Mean attacker sessions up to the flag, over flagged takeovers; 0 when none were flagged. */
        public double meanSessionsBeforeFlag() {
            int n = 0;
            long sum = 0;
            for (Map.Entry<Integer, Integer> e : sessionsBeforeFlag.entrySet()) {
                n += e.getValue();
                sum += (long) e.getKey() * e.getValue();
            }
            return n == 0 ? 0 : (double) sum / n;
        }

        public long cost(TakeoverCostModel prices) {
            return falseFlags * prices.falseFlagCost() + (long) (planted() - flagged()) * prices.missedTakeoverCost();
        }

        Result plus(Result other) {
            List<String> allMissed = new ArrayList<>(missed);
            allMissed.addAll(other.missed);
            allMissed.sort(null);
            return new Result(add(plantedByKind, other.plantedByKind), add(flaggedByKind, other.flaggedByKind),
                    allMissed, add(sessionsBeforeFlag, other.sessionsBeforeFlag), falseFlags + other.falseFlags,
                    add(falseFlagsByCase, other.falseFlagsByCase), add(falseFlagsByReasons, other.falseFlagsByReasons),
                    add(hardCasesByCase, other.hardCasesByCase));
        }

        private static <K> Map<K, Integer> add(Map<K, Integer> a, Map<K, Integer> b) {
            Map<K, Integer> sum = new TreeMap<>(a);
            b.forEach((key, n) -> sum.merge(key, n, Integer::sum));
            return sum;
        }
    }

    /** Clusters, held accounts and logins by ID for one dataset, computed once and reused at every setting. */
    static final class Prepared {
        final Dataset dataset;
        final BiPredicate<String, String> related;
        final Set<String> held = new HashSet<>();
        final Map<String, Login> loginById = new HashMap<>();

        Prepared(Dataset dataset) {
            this.dataset = dataset;
            LinkState state = BatchLinker.build(dataset.accounts(), Constraints.of(dataset.constraints()),
                    ClusteringConfig.defaults());
            this.related = MuleScorer.sameCluster(state);
            dataset.accounts().forEach(account -> held.add(account.accountId()));
            dataset.logins().forEach(login -> loginById.put(login.loginId(), login));
        }
    }

    public static Result evaluate(Dataset dataset, TakeoverConfig config) {
        return evaluate(new Prepared(dataset), config);
    }

    /** The results of every dataset, added together. */
    public static Result evaluateAll(List<Dataset> datasets, TakeoverConfig config) {
        Result total = empty();
        for (Dataset dataset : datasets) {
            total = total.plus(evaluate(dataset, config));
        }
        return total;
    }

    static Result evaluate(Prepared prepared, TakeoverConfig config) {
        TakeoverTruth truth = prepared.dataset.truth();
        Map<String, TakeoverScore> scores = new TakeoverScorer(config).scoreAll(prepared.dataset.logins(),
                prepared.related);
        Map<String, Integer> planted = new TreeMap<>();
        Map<String, Integer> flagged = new TreeMap<>();
        Map<Integer, Integer> sessions = new TreeMap<>();
        List<String> missed = new ArrayList<>();
        truth.takeovers().forEach((account, takeover) -> {
            planted.merge(takeover.kind(), 1, Integer::sum);
            TakeoverScore score = scores.get(account);
            if (score == null || !score.flagged()) {
                missed.add(account + " (" + takeover.kind() + ")");
                return;
            }
            flagged.merge(takeover.kind(), 1, Integer::sum);
            int before = 0;
            for (String id : takeover.attackerLoginIds()) {
                Login login = prepared.loginById.get(id);
                if (login != null && login.success() && !login.occurredAt().isAfter(score.reachedAt())) {
                    before++;
                }
            }
            sessions.merge(before, 1, Integer::sum);
        });
        missed.sort(null);
        Map<String, Integer> byCase = new TreeMap<>();
        Map<String, Integer> byReasons = new TreeMap<>();
        int falseFlags = 0;
        for (TakeoverScore score : scores.values()) {
            String id = score.accountId();
            if (!score.flagged() || !prepared.held.contains(id) || truth.takeovers().containsKey(id)) {
                continue;
            }
            falseFlags++;
            byCase.merge(truth.caseOf().getOrDefault(id, OTHER), 1, Integer::sum);
            byReasons.merge(score.reasons().stream().map(TakeoverReason::code).distinct()
                    .collect(Collectors.joining("+")), 1, Integer::sum);
        }
        Map<String, Integer> hardCases = new TreeMap<>();
        truth.caseOf().values().forEach(kind -> hardCases.merge(kind, 1, Integer::sum));
        return new Result(planted, flagged, missed, sessions, falseFlags, byCase, byReasons, hardCases);
    }

    static Result empty() {
        return new Result(Map.of(), Map.of(), List.of(), Map.of(), 0, Map.of(), Map.of(), Map.of());
    }
}
