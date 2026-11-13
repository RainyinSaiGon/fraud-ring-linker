package io.github.rainyinsaigon.fraudlinker.streaming;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.clustering.LinkState;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assessment;
import io.github.rainyinsaigon.fraudlinker.domain.Assignment;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.index.SignalStore;
import io.github.rainyinsaigon.fraudlinker.normalize.Normalizer;
import io.github.rainyinsaigon.fraudlinker.normalize.Text;
import io.github.rainyinsaigon.fraudlinker.scoring.Scorer;
import io.github.rainyinsaigon.fraudlinker.scoring.ScoringConfig;
import io.github.rainyinsaigon.fraudlinker.scoring.ScoringContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monotonic online assignment over a live {@link LinkState}. Each new account
 * either joins one existing cluster or starts a singleton. It never merges two
 * existing clusters, so earlier answers are never contradicted.
 *
 * <p>Cost per event depends only on the new account's local candidates (bounded
 * by the index limits), not on the total number of accounts. Not thread-safe.
 */
public final class StreamLinker {
    private final LinkState state;

    public StreamLinker(LinkState state) {
        this.state = state;
    }

    private record Option(int root, double score, Assessment assessment, boolean identitySeed) {
    }

    /** Evaluates one new account, then commits it to the state. */
    public Assignment assign(Account input) {
        Account account = input.withAccountId(Text.trimSpace(input.accountId()));
        if (account.accountId().isEmpty()) {
            throw new LinkerException("missing account_id");
        }
        if (account.createdAt() == null) {
            throw new LinkerException("missing or invalid created_at for \"" + account.accountId() + "\"");
        }
        SignalStore store = state.store();
        if (store.indexOf(account.accountId()) >= 0) {
            throw new LinkerException("duplicate account_id \"" + account.accountId() + "\"");
        }

        ScoringConfig scoring = state.config().scoring();
        Features features = Normalizer.normalize(account);
        List<Integer> candidates = store.candidatesFor(features, true);
        Map<Integer, Option> optionsByRoot = new HashMap<>();

        int projectedPrefixSize = features.ipPrefix() == null ? 0 : store.frequencies().ipPrefix(features.ipPrefix()) + 1;
        double distinctRatio = projectedDistinctIpRatio(features, projectedPrefixSize);
        for (int candidate : candidates) {
            Features candidateFeatures = store.features(candidate);
            int burstSize = 0;
            if (projectedPrefixSize <= scoring.burstMaxPrefix()) {
                burstSize = store.burstSize(candidateFeatures, features, scoring.burstWindow(), features);
            }
            Assessment assessment = Scorer.assess(candidateFeatures, features,
                    new ScoringContext(store.frequencies(), true, burstSize, distinctRatio), scoring);
            if (!assessment.eligible() || assessment.score() < state.config().streamThreshold()) {
                continue;
            }
            int root = state.dsu().find(candidate);
            Option current = optionsByRoot.get(root);
            if (current == null || better(assessment, current.assessment())) {
                boolean seed = assessment.identitySeed() || (current != null && current.identitySeed());
                optionsByRoot.put(root, new Option(root, assessment.score(), assessment, seed));
            } else if (assessment.identitySeed() && !current.identitySeed()) {
                optionsByRoot.put(root, new Option(current.root(), current.score(), current.assessment(), true));
            }
        }

        addSeededPrefixOptions(features, candidates, distinctRatio, optionsByRoot);

        List<Option> options = new ArrayList<>(optionsByRoot.values());
        options.sort((left, right) -> {
            int byScore = Double.compare(right.score(), left.score());
            if (byScore != 0) {
                return byScore;
            }
            return Integer.compare(LinkState.clusterNumber(state.clusterId(left.root())),
                    LinkState.clusterNumber(state.clusterId(right.root())));
        });

        Option selected = null;
        for (Option option : options) {
            if (canJoin(account.accountId(), option.root())) {
                selected = option;
                break;
            }
        }

        int newIndex = store.add(account, features);
        int dsuIndex = state.dsu().add();
        if (newIndex != dsuIndex) {
            throw new LinkerException("internal stream state index mismatch");
        }

        if (selected == null) {
            return new Assignment(account.accountId(), state.newClusterId(newIndex), 1);
        }

        int selectedRoot = state.dsu().find(selected.root());
        String clusterId = state.clusterId(selectedRoot);
        int newRoot = state.dsu().union(selectedRoot, newIndex, selected.score());
        state.moveCluster(selectedRoot, newIndex, newRoot, clusterId, selected.identitySeed());
        return new Assignment(account.accountId(), clusterId, Scorer.roundConfidence(selected.score()));
    }

    private static boolean better(Assessment left, Assessment right) {
        if (left.score() != right.score()) {
            return left.score() > right.score();
        }
        return left.rule().compareTo(right.rule()) < 0;
    }

    private boolean canJoin(String accountId, int root) {
        for (String forbiddenId : state.constraints().neighbors(accountId)) {
            int forbiddenIndex = state.store().indexOf(forbiddenId);
            if (forbiddenIndex >= 0 && state.dsu().find(forbiddenIndex) == root) {
                return false;
            }
        }
        return true;
    }

    /** Distinct-IP share of the prefix including the incoming account; 0 for prefixes too big for cohort rules. */
    double projectedDistinctIpRatio(Features incoming, int projectedPrefixSize) {
        if (incoming.ipPrefix() == null) {
            return 0;
        }
        ScoringConfig config = state.config().scoring();
        int maxEligiblePrefixSize = Math.max(config.burstMaxPrefix(), config.seededPrefixMaxSize());
        if (projectedPrefixSize > maxEligiblePrefixSize) {
            return 0;
        }
        Set<String> unique = new HashSet<>();
        for (int index : state.store().prefixPosting(incoming.ipPrefix())) {
            String address = state.store().features(index).ip();
            if (address != null) {
                unique.add(address);
            }
        }
        if (incoming.ip() != null) {
            unique.add(incoming.ip());
        }
        return (double) unique.size() / projectedPrefixSize;
    }

    private void addSeededPrefixOptions(Features incoming, List<Integer> candidates, double distinctRatio,
                                        Map<Integer, Option> options) {
        if (incoming.ipPrefix() == null) {
            return;
        }
        int postingSize = state.store().prefixPosting(incoming.ipPrefix()).size() + 1;
        ScoringConfig config = state.config().scoring();
        if (postingSize < config.seededPrefixMinSize()
                || postingSize > config.seededPrefixMaxSize()
                || distinctRatio < config.seededPrefixMinDistinctIpRatio()) {
            return;
        }

        Map<Integer, Integer> countByRoot = new HashMap<>();
        for (int candidate : candidates) {
            if (incoming.ipPrefix().equals(state.store().features(candidate).ipPrefix())) {
                countByRoot.merge(state.dsu().find(candidate), 1, Integer::sum);
            }
        }
        int seedRoot = -1;
        int seedCount = 0;
        for (Map.Entry<Integer, Integer> entry : countByRoot.entrySet()) {
            if (!state.isIdentitySeedRoot(entry.getKey())) {
                continue;
            }
            if (seedRoot != -1) {
                // Two established identities on one prefix are ambiguous; network
                // evidence must not choose between them.
                return;
            }
            seedRoot = entry.getKey();
            seedCount = entry.getValue();
        }
        if (seedRoot < 0 || seedCount < 2) {
            return;
        }
        Assessment assessment = Scorer.seededPrefixAssessment(postingSize, seedCount, config);
        if (assessment.score() < state.config().streamThreshold()) {
            return;
        }
        Option current = options.get(seedRoot);
        if (current == null || better(assessment, current.assessment())) {
            boolean seed = current != null && current.identitySeed();
            options.put(seedRoot, new Option(seedRoot, assessment.score(), assessment, seed));
        }
    }
}
