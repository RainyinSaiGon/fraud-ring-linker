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
import java.util.Set;

/**
 * The live linking result: built by {@link BatchLinker} and then extended one
 * account at a time by the streaming linker. Not thread-safe.
 */
public final class LinkState {
    private final SignalStore store;
    private final DisjointSet dsu;
    private final Constraints constraints;
    private final ClusteringConfig config;

    /** Cluster ID by component root. */
    private final Map<Integer, String> clusterIds = new HashMap<>();
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

    /** Creates a new cluster ID ("c" + next number) for a new singleton root. */
    public String newClusterId(int root) {
        String clusterId = "c" + nextClusterNumber++;
        clusterIds.put(root, clusterId);
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
        clusterIds.put(newRoot, clusterId);
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
            clusterIds.put(components.get(i).root(), "c" + (i + 1));
        }
        nextClusterNumber = components.size() + 1;
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
