package io.github.rainyinsaigon.fraudlinker.clustering;

import io.github.rainyinsaigon.fraudlinker.index.SignalStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Union-find (disjoint set union) that also keeps each component's members and
 * its bottleneck confidence, so a constraint covering the whole component can
 * be checked before two components are joined.
 */
public final class DisjointSet {
    private final List<Integer> parent = new ArrayList<>();
    private final List<Integer> size = new ArrayList<>();
    private final List<List<Integer>> members = new ArrayList<>();
    private final List<Double> confidence = new ArrayList<>();

    public DisjointSet(int count) {
        for (int i = 0; i < count; i++) {
            add();
        }
    }

    /** Adds a new singleton and returns its index. */
    public int add() {
        int index = parent.size();
        parent.add(index);
        size.add(1);
        List<Integer> own = new ArrayList<>();
        own.add(index);
        members.add(own);
        confidence.add(1.0);
        return index;
    }

    public int size() {
        return parent.size();
    }

    /** Returns the component root, compressing the path as it goes. */
    public int find(int index) {
        int root = index;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        while (parent.get(index) != index) {
            int next = parent.get(index);
            parent.set(index, root);
            index = next;
        }
        return root;
    }

    public List<Integer> members(int index) {
        return Collections.unmodifiableList(members.get(find(index)));
    }

    /** The weakest accepted edge that built this component (1.0 for a singleton). */
    public double confidence(int index) {
        return confidence.get(find(index));
    }

    /**
     * Reports whether joining the two components would put any verified-distinct
     * pair in one cluster. Scans the smaller component's constraints only.
     */
    public boolean canMerge(int left, int right, SignalStore store, Constraints constraints) {
        int leftRoot = find(left);
        int rightRoot = find(right);
        if (leftRoot == rightRoot) {
            return true;
        }
        int smallRoot = leftRoot;
        int otherRoot = rightRoot;
        if (size.get(smallRoot) > size.get(otherRoot)) {
            smallRoot = rightRoot;
            otherRoot = leftRoot;
        }
        for (int member : members.get(smallRoot)) {
            for (String forbiddenId : constraints.neighbors(store.account(member).accountId())) {
                int forbiddenIndex = store.indexOf(forbiddenId);
                if (forbiddenIndex >= 0 && forbiddenIndex < parent.size() && find(forbiddenIndex) == otherRoot) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Joins two components (union by size) and returns the surviving root.
     * Callers must check {@link #canMerge} first.
     */
    public int union(int left, int right, double edgeConfidence) {
        int leftRoot = find(left);
        int rightRoot = find(right);
        if (leftRoot == rightRoot) {
            return leftRoot;
        }
        if (size.get(leftRoot) < size.get(rightRoot)) {
            int swap = leftRoot;
            leftRoot = rightRoot;
            rightRoot = swap;
        }
        parent.set(rightRoot, leftRoot);
        size.set(leftRoot, size.get(leftRoot) + size.get(rightRoot));
        members.get(leftRoot).addAll(members.get(rightRoot));
        members.set(rightRoot, List.of());
        confidence.set(leftRoot, Math.min(Math.min(confidence.get(leftRoot), confidence.get(rightRoot)), edgeConfidence));
        return leftRoot;
    }
}
