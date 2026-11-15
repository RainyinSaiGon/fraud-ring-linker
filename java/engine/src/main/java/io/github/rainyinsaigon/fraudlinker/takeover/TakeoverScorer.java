package io.github.rainyinsaigon.fraudlinker.takeover;

import io.github.rainyinsaigon.fraudlinker.LinkerException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiPredicate;

/**
 * Scores every account that has logins for signs of a takeover:
 * <ul>
 *   <li><b>new device</b>: a successful login from a device the account never used, after a history;</li>
 *   <li><b>impossible travel</b>: two logins further apart than anyone could travel;</li>
 *   <li><b>failed burst</b>: many failed logins in minutes, weighing more when a success follows;</li>
 *   <li><b>shared device</b>: the login's device also logged in to many unrelated accounts.</li>
 * </ul>
 * A takeover is an incident, so signals combine only when they happen within the
 * incident window of each other: a new phone in March and a forgotten password in
 * May are two ordinary events, not one takeover. An account's score is its worst
 * incident's noisy-OR of signal weights, one weight per kind of signal.
 *
 * <p>"Related" means in the same cluster, so a device shared inside one cluster is
 * not counted as shared. Scoring only reads the cluster lookup: logins never merge
 * or split clusters.
 */
public final class TakeoverScorer {
    /** The order reasons are listed in, and the kinds an incident can hold once each. */
    static final List<Class<? extends TakeoverReason>> KINDS = List.of(
            TakeoverReason.NewDevice.class,
            TakeoverReason.ImpossibleTravel.class,
            TakeoverReason.BurstThenSuccess.class,
            TakeoverReason.BurstOnly.class,
            TakeoverReason.SharedDevice.class);

    /** Signals at the same moment can stay in any order: an incident takes the first of each kind by kind. */
    private static final Comparator<TakeoverReason> BY_TIME = Comparator.comparing(TakeoverReason::at);

    private final TakeoverConfig config;

    public TakeoverScorer(TakeoverConfig config) {
        this.config = config;
    }

    /**
     * @return one score per account that appears in any login, ordered by account ID
     * @throws LinkerException if two logins share an ID
     */
    public Map<String, TakeoverScore> scoreAll(List<Login> logins, BiPredicate<String, String> related) {
        Set<String> ids = new HashSet<>();
        for (Login login : logins) {
            if (!ids.add(login.loginId())) {
                throw new LinkerException("duplicate login_id " + login.loginId());
            }
        }
        List<Login> sorted = new ArrayList<>(logins);
        sorted.sort(TakeoverDetectors.BY_TIME);
        Map<String, List<Login>> byAccount = new LinkedHashMap<>();
        for (Login login : sorted) {
            byAccount.computeIfAbsent(login.accountId(), k -> new ArrayList<>()).add(login);
        }
        Map<String, List<TakeoverReason>> shared = TakeoverDetectors.sharedDevices(sorted, related, config);

        Map<String, TakeoverScore> scores = new TreeMap<>();
        byAccount.forEach((account, accountLogins) -> {
            List<TakeoverReason> signals = new ArrayList<>();
            signals.addAll(TakeoverDetectors.newDevices(accountLogins, config));
            signals.addAll(TakeoverDetectors.impossibleTravel(accountLogins, config));
            signals.addAll(TakeoverDetectors.bursts(accountLogins, config));
            signals.addAll(shared.getOrDefault(account, List.of()));
            signals.sort(BY_TIME);
            scores.put(account, worstIncident(account, signals));
        });
        return scores;
    }

    /**
     * Tries every signal as the end of an incident window and keeps the highest
     * score; on a tie, the earliest. Within a window, the first signal of each kind counts.
     */
    TakeoverScore worstIncident(String account, List<TakeoverReason> signals) {
        TakeoverScore best = new TakeoverScore(account, 0, false, List.of(), null);
        int start = 0;
        for (int end = 0; end < signals.size(); end++) {
            TakeoverReason last = signals.get(end);
            while (signals.get(start).at().isBefore(last.at().minus(config.incidentWindow()))) {
                start++;
            }
            Map<Class<?>, TakeoverReason> firstOfKind = new HashMap<>();
            for (TakeoverReason reason : signals.subList(start, end + 1)) {
                firstOfKind.putIfAbsent(reason.getClass(), reason);
            }
            List<TakeoverReason> reasons = KINDS.stream()
                    .filter(firstOfKind::containsKey)
                    .map(kind -> firstOfKind.get(kind))
                    .toList();
            double score = score(reasons);
            if (score > best.score()) {
                best = new TakeoverScore(account, score, score >= config.flagThreshold(), reasons, last.at());
            }
        }
        return best;
    }

    private double score(List<TakeoverReason> reasons) {
        double remaining = 1;
        for (TakeoverReason reason : reasons) {
            remaining *= 1 - weight(reason);
        }
        return Math.round((1 - remaining) * 10_000) / 10_000.0;
    }

    private double weight(TakeoverReason reason) {
        return switch (reason) {
            case TakeoverReason.NewDevice r -> config.newDeviceWeight();
            case TakeoverReason.ImpossibleTravel r -> config.impossibleTravelWeight();
            case TakeoverReason.BurstThenSuccess r -> config.burstThenSuccessWeight();
            case TakeoverReason.BurstOnly r -> config.burstOnlyWeight();
            case TakeoverReason.SharedDevice r -> config.sharedDeviceWeight();
        };
    }
}
