package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.domain.Cluster;
import io.github.rainyinsaigon.fraudlinker.domain.ClustersOutput;
import io.github.rainyinsaigon.fraudlinker.index.SignalStore;
import io.github.rainyinsaigon.fraudlinker.scoring.Scorer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The live linking result: built by {@link BatchLinker} and then extended one
 * account at a time by the streaming linker.
 *
 * <p>Not thread-safe. Even read methods may change internal state, because
 * union-find lookups compress paths, so callers must serialize all access.
 */
public final class LinkState {
    private final SignalStore store;
    private final DisjointSet dsu;
    private final Constraints constraints;
    private final ClusteringConfig config;

    /** Cluster ID by component root, and the reverse. Kept in step by every method that changes either. */
    private final Map<Integer, String> clusterIds = new HashMap<>();
    private final Map<String, Integer> rootsByClusterId = new HashMap<>();
    /** Roots of components that contain a strong identity link (not just network evidence). */
    private final Set<Integer> identitySeedRoots = new HashSet<>();
    private int nextClusterNumber = 1;

    LinkState(SignalStore store, DisjointSet dsu, Constraints constraints, ClusteringConfig config) {
        this.store = store;
        this.dsu = dsu;
        this.constraints = constraints;
        this.config = config;
    }

    public SignalStore store() {
        return store;
    }

    public DisjointSet dsu() {
        return dsu;
    }

    public Constraints constraints() {
        return constraints;
    }

    public ClusteringConfig config() {
        return config;
    }

    public String clusterId(int root) {
        return clusterIds.get(root);
    }

    public boolean isIdentitySeedRoot(int root) {
        return identitySeedRoots.contains(root);
    }

    private void putClusterId(int root, String clusterId) {
        clusterIds.put(root, clusterId);
        rootsByClusterId.put(clusterId, root);
    }

    /** Creates a new cluster ID ("c" + next number) for a new singleton root. */
    public String newClusterId(int root) {
        String clusterId = "c" + nextClusterNumber++;
        putClusterId(root, clusterId);
        return clusterId;
    }

    /**
     * Records that {@code newRoot} now carries the cluster that was rooted at
     * {@code oldRoot} after a union with {@code joinedIndex}.
     */
    public void moveCluster(int oldRoot, int joinedIndex, int newRoot, String clusterId, boolean identitySeed) {
        boolean hadSeed = identitySeedRoots.contains(oldRoot);
        if (newRoot != oldRoot) {
            clusterIds.remove(oldRoot);
            identitySeedRoots.remove(oldRoot);
        }
        clusterIds.remove(joinedIndex);
        putClusterId(newRoot, clusterId);
        if (hadSeed || identitySeed) {
            identitySeedRoots.add(newRoot);
        }
    }

    void markIdentitySeedRoot(int root) {
        identitySeedRoots.add(root);
    }

    /** Numbers components c1, c2, ... in order of their smallest account ID. */
    void assignInitialClusterIds() {
        List<Component> components = components();
        components.sort(Comparator.comparing(component -> component.members().get(0)));
        for (int i = 0; i < components.size(); i++) {
            putClusterId(components.get(i).root(), "c" + (i + 1));
        }
        nextClusterNumber = components.size() + 1;
    }

    /** The cluster an account belongs to, or empty if the account is unknown. */
    public Optional<String> clusterIdOf(String accountId) {
        int index = store.indexOf(accountId);
        return index < 0 ? Optional.empty() : Optional.ofNullable(clusterIds.get(dsu.find(index)));
    }

    /** Sorted member account IDs of a cluster, or empty if the cluster ID is unknown. */
    public List<String> memberIds(String clusterId) {
        Integer root = rootsByClusterId.get(clusterId);
        if (root == null) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        for (int index : dsu.members(root)) {
            members.add(store.account(index).accountId());
        }
        members.sort(Comparator.naturalOrder());
        return members;
    }

    /** Account indexes of a cluster, or empty if the cluster ID is unknown. */
    public List<Integer> memberIndexes(String clusterId) {
        Integer root = rootsByClusterId.get(clusterId);
        return root == null ? List.of() : List.copyOf(dsu.members(root));
    }

    /** Rounded bottleneck confidence of a cluster (1.0 for a singleton). */
    public double confidence(String clusterId) {
        Integer root = rootsByClusterId.get(clusterId);
        if (root == null) {
            throw new LinkerException("unknown cluster \"" + clusterId + "\"");
        }
        return Scorer.roundConfidence(dsu.confidence(root));
    }

    /** Outcome of adding a verified-distinct pair after linking has started. */
    public enum ConstraintOutcome {
        /** Stored; future links will respect it. */
        ADDED,
        /** The pair was already known. */
        ALREADY_PRESENT,
        /**
         * Both accounts are already in one cluster. Nothing is stored: honouring it
         * means splitting that cluster, which needs a re-link, not a stream update.
         */
        CONFLICTS_WITH_EXISTING_CLUSTER
    }

    /** Adds a verified-distinct pair unless it contradicts a cluster that already exists. */
    public ConstraintOutcome addConstraint(String accountA, String accountB) {
        if (accountA.equals(accountB)) {
            throw new LinkerException("self constraint for account \"" + accountA + "\" is unsatisfiable");
        }
        if (constraints.isDistinct(accountA, accountB)) {
            return ConstraintOutcome.ALREADY_PRESENT;
        }
        Optional<String> left = clusterIdOf(accountA);
        Optional<String> right = clusterIdOf(accountB);
        if (left.isPresent() && left.equals(right)) {
            return ConstraintOutcome.CONFLICTS_WITH_EXISTING_CLUSTER;
        }
        constraints.addVerifiedDistinct(accountA, accountB);
        return ConstraintOutcome.ADDED;
    }

    /** The complete current partition, ordered by cluster number, members sorted. */
    public ClustersOutput clusters() {
        List<Component> components = components();
        components.sort(Comparator.comparingInt(component -> clusterNumber(clusterIds.get(component.root()))));
        List<Cluster> clusters = new ArrayList<>(components.size());
        for (Component component : components) {
            clusters.add(new Cluster(clusterIds.get(component.root()), component.members(),
                    Scorer.roundConfidence(dsu.confidence(component.root()))));
        }
        return new ClustersOutput(clusters);
    }

    private record Component(int root, List<String> members) {
    }

    private List<Component> components() {
        Map<Integer, List<String>> byRoot = new HashMap<>();
        for (int index = 0; index < store.size(); index++) {
            byRoot.computeIfAbsent(dsu.find(index), ignored -> new ArrayList<>()).add(store.account(index).accountId());
        }
        List<Component> result = new ArrayList<>(byRoot.size());
        byRoot.forEach((root, members) -> {
            members.sort(Comparator.naturalOrder());
            result.add(new Component(root, members));
        });
        return result;
    }

    public static int clusterNumber(String clusterId) {
        if (clusterId == null || !clusterId.startsWith("c")) {
            return 0;
        }
        try {
            return Integer.parseInt(clusterId.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Checks the hard contract: one partition entry per account, unique cluster
     * IDs, and no verified-distinct pair inside any cluster.
     *
     * @throws LinkerException when any invariant is broken
     */
    public void validateInvariants() {
        if (dsu.size() != store.size()) {
            throw new LinkerException("internal partition size mismatch");
        }
        Set<Integer> seenRoots = new HashSet<>();
        Map<String, Integer> seenClusterIds = new HashMap<>();
        for (int index = 0; index < store.size(); index++) {
            int root = dsu.find(index);
            if (!seenRoots.add(root)) {
                continue;
            }
            String clusterId = clusterIds.get(root);
            if (clusterId == null || clusterId.isEmpty()) {
                throw new LinkerException("component rooted at " + root + " has no cluster ID");
            }
            Integer previousRoot = seenClusterIds.putIfAbsent(clusterId, root);
            if (previousRoot != null) {
                throw new LinkerException("cluster ID \"" + clusterId + "\" is assigned to roots "
                        + previousRoot + " and " + root);
            }
            if (!Integer.valueOf(root).equals(rootsByClusterId.get(clusterId))) {
                throw new LinkerException("cluster ID index is stale for \"" + clusterId + "\"");
            }
        }
        for (String accountId : constraints.accountIds()) {
            int left = store.indexOf(accountId);
            if (left < 0) {
                continue;
            }
            for (String forbiddenId : constraints.neighbors(accountId)) {
                int right = store.indexOf(forbiddenId);
                if (right >= 0 && dsu.find(left) == dsu.find(right)) {
                    throw new LinkerException("constraint invariant violated: \"" + accountId + "\" and \""
                            + forbiddenId + "\" share a cluster");
                }
            }
        }
    }
}
