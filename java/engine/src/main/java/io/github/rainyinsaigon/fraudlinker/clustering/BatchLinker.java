package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Account;
import io.github.rainyinsaigon.fraudlinker.domain.Assessment;
import io.github.rainyinsaigon.fraudlinker.domain.Features;
import io.github.rainyinsaigon.fraudlinker.index.Pair;
import io.github.rainyinsaigon.fraudlinker.index.SignalStore;
import io.github.rainyinsaigon.fraudlinker.normalize.Normalizer;
import io.github.rainyinsaigon.fraudlinker.normalize.Text;
import io.github.rainyinsaigon.fraudlinker.scoring.Scorer;
import io.github.rainyinsaigon.fraudlinker.scoring.ScoringConfig;
import io.github.rainyinsaigon.fraudlinker.scoring.ScoringContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch linking: sparse candidate generation, evidence scoring, then a
 * strongest-first, constraint-aware union over the scored edges, and finally
 * deterministic cluster numbering.
 */
public final class BatchLinker {
    private BatchLinker() {
    }

    /** A scored, merge-eligible link between two account indexes ({@code a < b} after deduplication). */
    record Edge(int a, int b, Assessment assessment) {
    }

    public static LinkState build(List<Account> accounts, Constraints constraints, ClusteringConfig config) {
        if (constraints == null) {
            constraints = Constraints.empty();
        }
        SignalStore store = new SignalStore(config.index());
        for (int position = 0; position < accounts.size(); position++) {
            Account account = accounts.get(position);
            account = account.withAccountId(Text.trimSpace(account.accountId()));
            validateAccount(account, position + 1);
            store.add(account, Normalizer.normalize(account));
        }

        List<Edge> baseEdges = scoreCandidateEdges(store, config);
        sortEdges(store, baseEdges);

        // A provisional constrained pass over identity edges only finds which small
        // cohorts contain a real strong-identity seed. A velocity burst is
        // deliberately not allowed to bootstrap prefix expansion on its own.
        DisjointSet identityProvisional = new DisjointSet(accounts.size());
        List<Edge> identityEdges = new ArrayList<>();
        for (Edge edge : baseEdges) {
            if (edge.assessment().identitySeed()) {
                identityEdges.add(edge);
            }
        }
        applyEdges(identityProvisional, identityEdges, store, constraints);

        List<Edge> allEdges = new ArrayList<>(baseEdges);
        allEdges.addAll(seededPrefixEdges(store, identityProvisional, config.scoring()));
        allEdges = deduplicateEdges(allEdges);
        sortEdges(store, allEdges);

        DisjointSet dsu = new DisjointSet(accounts.size());
        applyEdges(dsu, allEdges, store, constraints);

        LinkState state = new LinkState(store, dsu, constraints, config);
        for (int index = 0; index < store.size(); index++) {
            if (identityProvisional.members(index).size() >= 2) {
                state.markIdentitySeedRoot(dsu.find(index));
            }
        }
        state.assignInitialClusterIds();
        state.validateInvariants();
        return state;
    }

    private static void validateAccount(Account account, int position) {
        if (account.accountId().isEmpty()) {
            throw new LinkerException("account " + position + ": missing account_id");
        }
        if (account.createdAt() == null) {
            throw new LinkerException("account " + position + ": missing or invalid created_at for \""
                    + account.accountId() + "\"");
        }
    }

    private static List<Edge> scoreCandidateEdges(SignalStore store, ClusteringConfig config) {
        List<Edge> edges = new ArrayList<>();
        for (Pair pair : store.candidatePairs()) {
            Assessment assessment = assessPair(store, pair.a(), pair.b(), config.scoring());
            if (assessment.eligible()) {
                edges.add(new Edge(pair.a(), pair.b(), assessment));
            }
        }
        return edges;
    }

    /**
     * Scores two stored accounts exactly as batch linking does, using the
     * store's current frequencies (including the prefix burst and diversity context).
     */
    public static Assessment assessPair(SignalStore store, int a, int b, ScoringConfig scoring) {
        Features left = store.features(a);
        Features right = store.features(b);
        int burstSize = 0;
        double distinctRatio = 0;
        if (left.ipPrefix() != null && left.ipPrefix().equals(right.ipPrefix())) {
            // Cohort rules are unavailable on large prefixes. Check the O(1)
            // frequency first so an identity edge behind a carrier or office
            // prefix never scans and sorts that whole posting list.
            if (store.frequencies().ipPrefix(left.ipPrefix()) <= scoring.burstMaxPrefix()) {
                burstSize = store.burstSize(left, right, scoring.burstWindow(), null);
                distinctRatio = store.distinctIpRatio(store.prefixPosting(left.ipPrefix()));
            }
        }
        return Scorer.assess(left, right,
                new ScoringContext(store.frequencies(), false, burstSize, distinctRatio), scoring);
    }

    private static List<Edge> seededPrefixEdges(SignalStore store, DisjointSet provisional, ScoringConfig config) {
        List<Edge> result = new ArrayList<>();
        List<String> prefixes = new ArrayList<>(store.prefixes());
        prefixes.sort(Comparator.naturalOrder());

        for (String prefix : prefixes) {
            List<Integer> posting = store.prefixPosting(prefix);
            if (posting.size() < config.seededPrefixMinSize()
                    || posting.size() > config.seededPrefixMaxSize()
                    || store.distinctIpRatio(posting) < config.seededPrefixMinDistinctIpRatio()) {
                continue;
            }

            Map<Integer, List<Integer>> membersByRoot = new LinkedHashMap<>();
            for (int accountIndex : posting) {
                membersByRoot.computeIfAbsent(provisional.find(accountIndex), ignored -> new ArrayList<>())
                        .add(accountIndex);
            }
            int seedRoot = -1;
            int establishedRoots = 0;
            for (Map.Entry<Integer, List<Integer>> entry : membersByRoot.entrySet()) {
                if (provisional.members(entry.getKey()).size() < 2) {
                    continue;
                }
                establishedRoots++;
                if (entry.getValue().size() >= 2) {
                    seedRoot = entry.getKey();
                }
            }
            // Expansion is safe only when exactly one established identity touches
            // the prefix and at least two of its members are in the cohort. Even one
            // member of a second established identity makes the network ambiguous.
            if (establishedRoots != 1 || seedRoot < 0) {
                continue;
            }

            List<Integer> seedMembers = new ArrayList<>(membersByRoot.get(seedRoot));
            seedMembers.sort(Comparator.comparing(index -> store.account(index).accountId()));
            int representative = seedMembers.get(0);
            Assessment assessment = Scorer.seededPrefixAssessment(posting.size(), seedMembers.size(), config);
            for (int candidate : posting) {
                if (provisional.find(candidate) != seedRoot) {
                    result.add(new Edge(representative, candidate, assessment));
                }
            }
        }
        return result;
    }

    /** Keeps the highest-scoring edge per account pair (the first one on a tie). */
    private static List<Edge> deduplicateEdges(List<Edge> edges) {
        Map<Pair, Edge> best = new HashMap<>();
        for (Edge edge : edges) {
            Pair pair = Pair.of(edge.a(), edge.b());
            Edge normalized = new Edge(pair.a(), pair.b(), edge.assessment());
            Edge current = best.get(pair);
            if (current == null || normalized.assessment().score() > current.assessment().score()) {
                best.put(pair, normalized);
            }
        }
        return new ArrayList<>(best.values());
    }

    /** Strongest score first; ties broken by account IDs so output is deterministic. */
    private static void sortEdges(SignalStore store, List<Edge> edges) {
        edges.sort((left, right) -> {
            int byScore = Double.compare(right.assessment().score(), left.assessment().score());
            if (byScore != 0) {
                return byScore;
            }
            return store.comparePairsById(new Pair(left.a(), left.b()), new Pair(right.a(), right.b()));
        });
    }

    private static void applyEdges(DisjointSet dsu, List<Edge> edges, SignalStore store, Constraints constraints) {
        for (Edge edge : edges) {
            if (dsu.find(edge.a()) == dsu.find(edge.b())) {
                continue;
            }
            if (!dsu.canMerge(edge.a(), edge.b(), store, constraints)) {
                continue;
            }
            dsu.union(edge.a(), edge.b(), edge.assessment().score());
        }
    }
}
