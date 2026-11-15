package io.github.rainyinsaigon.fraudlinker.mules;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * Scores every account that sent or received money for mule behaviour:
 * <ul>
 *   <li><b>fan-in</b>: many unrelated payers in a short time;</li>
 *   <li><b>pass-through</b>: most of the money received leaves again quickly;</li>
 *   <li><b>chain</b>: money links the account to other pass-through accounts.</li>
 * </ul>
 * The weights of the reasons that fire combine with a noisy-OR, the same as link scoring.
 *
 * <p>"Unrelated" means not in the same cluster, so a household or one person's own
 * accounts paying each other is never fan-in or pass-through. Scoring only reads the
 * cluster lookup: transfers never merge or split clusters.
 */
public final class MuleScorer {
    private final MuleConfig config;

    public MuleScorer(MuleConfig config) {
        this.config = config;
    }

    /**
     * Two accounts are related when the linker put them in the same cluster.
     * Accounts the bank does not hold are related to nothing.
     */
    public static BiPredicate<String, String> sameCluster(LinkState state) {
        return (a, b) -> {
            Optional<String> clusterA = state.clusterIdOf(a);
            return clusterA.isPresent() && clusterA.equals(state.clusterIdOf(b));
        };
    }

    /**
     * @return one score per account that appears in any transfer, ordered by account ID
     * @throws LinkerException if two transfers share an ID
     */
    public Map<String, MuleScore> scoreAll(List<Transfer> transfers, BiPredicate<String, String> related) {
        Map<String, List<Transfer>> incoming = new HashMap<>();
        Map<String, List<Transfer>> outgoing = new HashMap<>();
        Map<String, Set<String>> payersOf = new HashMap<>();
        Map<String, Set<String>> payeesOf = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (Transfer t : transfers) {
            if (!ids.add(t.transferId())) {
                throw new LinkerException("duplicate transfer_id " + t.transferId());
            }
            incoming.computeIfAbsent(t.toAccount(), k -> new ArrayList<>()).add(t);
            outgoing.computeIfAbsent(t.fromAccount(), k -> new ArrayList<>()).add(t);
            if (!related.test(t.fromAccount(), t.toAccount())) {
                payersOf.computeIfAbsent(t.toAccount(), k -> new HashSet<>()).add(t.fromAccount());
                payeesOf.computeIfAbsent(t.fromAccount(), k -> new HashSet<>()).add(t.toAccount());
            }
        }
        incoming.values().forEach(list -> list.sort(Detectors.BY_TIME));
        outgoing.values().forEach(list -> list.sort(Detectors.BY_TIME));

        Set<String> accounts = new HashSet<>(incoming.keySet());
        accounts.addAll(outgoing.keySet());
        Map<String, MuleReason.FanIn> fanIn = new HashMap<>();
        Map<String, MuleReason.PassThrough> passThrough = new HashMap<>();
        for (String account : accounts) {
            List<Transfer> in = incoming.getOrDefault(account, List.of());
            List<Transfer> out = outgoing.getOrDefault(account, List.of());
            Detectors.fanIn(account, in, related, config).ifPresent(r -> fanIn.put(account, r));
            Detectors.passThrough(account, in, out, related, config).ifPresent(r -> passThrough.put(account, r));
        }

        Map<String, MuleScore> scores = new TreeMap<>();
        for (String account : accounts) {
            List<MuleReason> reasons = new ArrayList<>(3);
            Optional.ofNullable(fanIn.get(account)).ifPresent(reasons::add);
            Optional.ofNullable(passThrough.get(account)).ifPresent(reasons::add);
            Detectors.chain(account, passThrough.keySet(), payersOf, payeesOf).ifPresent(reasons::add);
            double score = score(reasons);
            scores.put(account, new MuleScore(account, score, score >= config.flagThreshold(), reasons));
        }
        return scores;
    }

    private double score(List<MuleReason> reasons) {
        double remaining = 1;
        for (MuleReason reason : reasons) {
            remaining *= 1 - weight(reason);
        }
        return Math.round((1 - remaining) * 10_000) / 10_000.0;
    }

    private double weight(MuleReason reason) {
        return switch (reason) {
            case MuleReason.FanIn f -> config.fanInWeight();
            case MuleReason.PassThrough p -> config.passThroughWeight();
            case MuleReason.Chain c -> config.chainWeight();
        };
    }
}
